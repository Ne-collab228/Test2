package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Статистика расхода с экраном выключенным / включённым (чистая логика + хранение).
 *
 * Отрезок — время с одним состоянием экрана без зарядки. На границе отрезка (экран вкл/выкл,
 * зарядка, остановка слежения) к итогам добавляются: длительность, сколько процентов ушло,
 * сколько времени процессор не спал (uptime). Проценты на коротких отрезках квантуются по 1 %,
 * но на сумме многих отрезков ошибка усредняется; оценка показывается, когда накоплено
 * достаточно времени и процентов.
 *
 * Отрезки с выключенным экраном, во время которых шёл точный замер (он держит процессор
 * бодрым), в итоги не попадают — иначе расход в простое был бы завышен.
 */
public final class EnergyStats {
    static final String PREFS = "energy";
    /** Минимум для оценки: экран выключен — 2 ч и 2 %; экран включён — 30 мин и 3 %. */
    static final long MIN_OFF_MS = 2 * 3600_000L, MIN_ON_MS = 30 * 60_000L;
    static final double MIN_OFF_PCT = 2, MIN_ON_PCT = 3;
    /** Долгий отрезок простоя («ночь») — от 3 ч. */
    static final long NIGHT_MS = 3 * 3600_000L;

    /** Итоги по одному состоянию экрана. */
    static final class Totals {
        long ms; double pct; long awakeMs; int segments;
        double rate() { return ms <= 0 ? Double.NaN : pct / (ms / 3600_000.0); }
        double awakeShare() { return ms <= 0 ? Double.NaN : Math.min(1, Math.max(0, awakeMs / (double) ms)); }
    }

    /** Открытый отрезок. */
    static final class Segment {
        boolean screenOn; long startElapsed, startUptime, startWall; double startLevel; boolean tainted;
        double minLevel;
    }

    final Totals off = new Totals(), on = new Totals();
    Segment open;
    /** Последний долгий простой: {начало (wall), длительность мс, ушло %, доля бодрствования}. */
    double[] lastNight;
    long since;

    /**
     * Событие: состояние на момент t. screenOn — экран включён; plugged — подключена зарядка;
     * busy — сейчас идёт точный замер (держит wake lock). Закрывает старый отрезок, если
     * состояние сменилось, и открывает новый.
     */
    void event(long elapsed, long uptime, long wall, double level, boolean screenOn, boolean plugged, boolean busy) {
        if (since == 0) since = wall;
        if (open != null) {
            if (busy && !open.screenOn) open.tainted = true;
            if (!Double.isNaN(level)) open.minLevel = Math.min(open.minLevel, level);
            if (plugged || open.screenOn != screenOn || elapsed < open.startElapsed || level > open.startLevel + 0.5) {
                close(elapsed, uptime, level);
            }
        }
        if (open == null && !plugged && !Double.isNaN(level)) {
            Segment s = new Segment();
            s.screenOn = screenOn; s.startElapsed = elapsed; s.startUptime = uptime; s.startWall = wall;
            s.startLevel = level; s.minLevel = level; s.tainted = busy && !screenOn;
            open = s;
        }
    }

    /** Закрыть открытый отрезок (например, при остановке слежения). */
    void close(long elapsed, long uptime, double level) {
        Segment s = open;
        open = null;
        if (s == null) return;
        long dt = elapsed - s.startElapsed;
        if (dt <= 0) return;
        double end = Double.isNaN(level) || level > s.startLevel ? s.minLevel : Math.min(level, s.minLevel);
        double used = Math.max(0, s.startLevel - end);
        long awake = Math.max(0, Math.min(dt, uptime - s.startUptime));
        if (s.screenOn) add(on, dt, used, awake);
        else if (!s.tainted) {
            add(off, dt, used, awake);
            if (dt >= NIGHT_MS) lastNight = new double[]{s.startWall, dt, used, awake / (double) dt};
        }
    }

    private static void add(Totals t, long dt, double used, long awake) {
        t.ms += dt; t.pct += used; t.awakeMs += awake; t.segments++;
    }

    /** %/ч, если данных достаточно, иначе NaN. */
    double offRate() { return off.ms >= MIN_OFF_MS && off.pct >= MIN_OFF_PCT ? off.rate() : Double.NaN; }

    double onRate() { return on.ms >= MIN_ON_MS && on.pct >= MIN_ON_PCT ? on.rate() : Double.NaN; }

    /** Оценка расхода в простое по %/ч: 1 отлично, 2 нормально, 3 повышенный, 4 что-то будит телефон. */
    static int offGrade(double ratePctH) {
        if (Double.isNaN(ratePctH)) return 0;
        if (ratePctH <= 0.8) return 1;
        if (ratePctH <= 1.5) return 2;
        if (ratePctH <= 3) return 3;
        return 4;
    }

    /** Оценка глубокого сна в простое: доля времени, когда процессор спал. */
    static int sleepGrade(double sleepShare) {
        if (Double.isNaN(sleepShare)) return 0;
        if (sleepShare >= 0.9) return 1;
        if (sleepShare >= 0.75) return 2;
        if (sleepShare >= 0.5) return 3;
        return 4;
    }

    /** Ток, мА, по %/ч и ёмкости. */
    static double ma(double ratePctH, double capMah) {
        return Double.isNaN(ratePctH) || capMah <= 0 ? Double.NaN : ratePctH * capMah / 100.0;
    }

    /** Часов работы на полной батарее при данном %/ч. */
    static double hoursFull(double ratePctH) {
        return Double.isNaN(ratePctH) || ratePctH <= 0.01 ? Double.NaN : 100.0 / ratePctH;
    }

    // ---------- хранение ----------
    static synchronized EnergyStats load(Context ctx) {
        EnergyStats e = new EnergyStats();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            e.since = p.getLong("since", 0);
            read(p, "off", e.off); read(p, "on", e.on);
            if (p.contains("night_t"))
                e.lastNight = new double[]{p.getLong("night_t", 0), p.getLong("night_ms", 0),
                        p.getFloat("night_pct", 0), p.getFloat("night_awake", 0)};
            if (p.contains("seg_el")) {
                Segment s = new Segment();
                s.screenOn = p.getBoolean("seg_on", false);
                s.startElapsed = p.getLong("seg_el", 0); s.startUptime = p.getLong("seg_up", 0);
                s.startWall = p.getLong("seg_wall", 0); s.startLevel = p.getFloat("seg_lvl", 0);
                s.minLevel = p.getFloat("seg_min", (float) s.startLevel); s.tainted = p.getBoolean("seg_busy", false);
                e.open = s;
            }
        } catch (Throwable ignored) { }
        return e;
    }

    private static void read(SharedPreferences p, String k, Totals t) {
        t.ms = p.getLong(k + "_ms", 0); t.pct = p.getFloat(k + "_pct", 0);
        t.awakeMs = p.getLong(k + "_awake", 0); t.segments = p.getInt(k + "_n", 0);
    }

    synchronized void save(Context ctx) {
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putLong("since", since);
            write(ed, "off", off); write(ed, "on", on);
            if (lastNight != null) ed.putLong("night_t", (long) lastNight[0]).putLong("night_ms", (long) lastNight[1])
                    .putFloat("night_pct", (float) lastNight[2]).putFloat("night_awake", (float) lastNight[3]);
            if (open != null) ed.putBoolean("seg_on", open.screenOn).putLong("seg_el", open.startElapsed)
                    .putLong("seg_up", open.startUptime).putLong("seg_wall", open.startWall)
                    .putFloat("seg_lvl", (float) open.startLevel).putFloat("seg_min", (float) open.minLevel)
                    .putBoolean("seg_busy", open.tainted);
            else ed.remove("seg_on").remove("seg_el").remove("seg_up").remove("seg_wall").remove("seg_lvl")
                    .remove("seg_min").remove("seg_busy");
            ed.apply();
        } catch (Throwable ignored) { }
    }

    private static void write(SharedPreferences.Editor ed, String k, Totals t) {
        ed.putLong(k + "_ms", t.ms).putFloat(k + "_pct", (float) t.pct).putLong(k + "_awake", t.awakeMs).putInt(k + "_n", t.segments);
    }

    static synchronized void reset(Context ctx) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply(); } catch (Throwable ignored) { }
    }
}
