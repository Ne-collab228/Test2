package com.deviceinfo.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Измерение реальной ёмкости батареи кулон-счётом (интеграл |I|·dt) между сменами уровня заряда,
 * пока приложение открыто. Ядро (step) — чистая математика без Android.
 */
public final class BatteryMeter {
    static final String PREFS = "battery_meter";
    static final int NEED_LEVELS = 5;
    static final long MAX_GAP_MS = 10_000;
    static final int KEEP = 12;

    /** Состояние текущего замера. */
    static final class MeterState {
        int lastLevel = -1;
        long lastMs = -1;
        int dir;            // -1 разряд, +1 заряд, 0 другое
        int plugged;
        boolean active;
        int startLevel = -1;
        double accMah;
        /** Для тестов: причина последнего прерывания. */
        String lastAbort;
    }

    /** Завершённый замер. */
    static final class Measurement {
        final double capacityMah; final long ts; final int dLevel; final int dir;
        Measurement(double c, long ts, int d, int dir) { capacityMah = c; this.ts = ts; dLevel = d; this.dir = dir; }
    }

    static int dirOfStatus(int status) { return status == 3 ? -1 : (status == 2 ? 1 : 0); }

    static Measurement step(MeterState s, long nowMs, int level, int dir, double currentMa, double designMah) {
        return step(s, nowMs, level, dir, s.plugged, currentMa, designMah);
    }

    /**
     * Один шаг. dir: -1 разряд, +1 заряд, 0 иное. Возвращает завершённый замер или null.
     * Сессия стартует строго на смене целого уровня; прерывается при паузе > 10 с,
     * смене направления / источника питания, скачке уровня > 1 % или отсутствии тока.
     */
    static Measurement step(MeterState s, long nowMs, int level, int dir, int plugged,
                            double currentMa, double designMah) {
        if (level < 0) return null;
        if (s.lastLevel < 0 || s.lastMs < 0) {
            s.lastLevel = level; s.lastMs = nowMs; s.dir = dir; s.plugged = plugged;
            return null;
        }
        long dt = nowMs - s.lastMs;
        boolean gap = dt > MAX_GAP_MS || dt < 0;
        boolean changed = dir != s.dir || plugged != s.plugged;
        boolean jump = Math.abs(level - s.lastLevel) > 1;
        boolean clean = !gap && !changed && !jump && dir != 0 && !Double.isNaN(currentMa);

        if (s.active) {
            if (!clean) abort(s, gap ? "gap" : (changed ? "state" : (jump ? "jump" : "nocurrent")));
            else s.accMah += Math.abs(currentMa) * dt / 3600000.0;
        }

        Measurement res = null;
        if (level != s.lastLevel) {
            if (s.active) {
                int signed = level - s.startLevel;
                if ((dir < 0 && signed > 0) || (dir > 0 && signed < 0)) {
                    abort(s, "direction");
                } else if (Math.abs(signed) >= NEED_LEVELS) {
                    double cap = s.accMah / Math.abs(signed) * 100.0;
                    if (designMah <= 0 || (cap >= 0.4 * designMah && cap <= 1.3 * designMah))
                        res = new Measurement(cap, System.currentTimeMillis(), Math.abs(signed), dir);
                    s.startLevel = level; s.accMah = 0; // новая сессия с этой границы
                }
            }
            if (!s.active && clean) {
                s.active = true; s.startLevel = level; s.accMah = 0;
            }
        }
        s.lastLevel = level; s.lastMs = nowMs; s.dir = dir; s.plugged = plugged;
        return res;
    }

    private static void abort(MeterState s, String why) {
        s.active = false; s.accMah = 0; s.startLevel = -1; s.lastAbort = why;
    }

    /** Медиана ёмкостей замеров (NaN, если пусто). */
    static double medianCapacity(List<Measurement> l) {
        if (l == null || l.isEmpty()) return Double.NaN;
        List<Double> v = new ArrayList<>();
        for (Measurement m : l) v.add(m.capacityMah);
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    // ---------- Потребление: кольцевой буфер тока ----------
    static final long BUCKET_MS = 10_000, RING_MS = 30 * 60_000L, WIN5_MS = 5 * 60_000L, WIN10_MS = 10 * 60_000L;
    static final int MIN_POINTS = 6;

    /** Буфер тока: усреднение по 10-секундным корзинам, хранится последние 30 мин (≤ 180 корзин). */
    static final class CurrentRing {
        final List<double[]> b = new ArrayList<>(); // {bucketStartMs, sum, n}

        void add(long tMs, double ma) {
            if (Double.isNaN(ma)) return;
            long k = tMs / BUCKET_MS * BUCKET_MS;
            double[] last = b.isEmpty() ? null : b.get(b.size() - 1);
            if (last != null && last[0] == k) { last[1] += ma; last[2]++; }
            else b.add(new double[]{k, ma, 1});
            while (!b.isEmpty() && b.get(0)[0] < tMs - RING_MS) b.remove(0);
        }

        /** Средние по корзинам за последние windowMs. */
        List<Double> points(long now, long windowMs) {
            List<Double> r = new ArrayList<>();
            for (double[] x : b) if (x[0] >= now - windowMs && x[0] <= now) r.add(x[1] / x[2]);
            return r;
        }

        /** Среднее по всем отсчётам за windowMs или NaN. */
        double avg(long now, long windowMs) {
            double s = 0, n = 0;
            for (double[] x : b) if (x[0] >= now - windowMs && x[0] <= now) { s += x[1]; n += x[2]; }
            return n == 0 ? Double.NaN : s / n;
        }
    }

    /** Коэффициент вариации (%) модулей значений; NaN при пустом списке или нулевом среднем. */
    static double cvPercent(List<Double> pts) {
        if (pts == null || pts.isEmpty()) return Double.NaN;
        double m = 0;
        for (double d : pts) m += Math.abs(d);
        m /= pts.size();
        if (m < 0.5) return Double.NaN;
        double q = 0;
        for (double d : pts) q += (Math.abs(d) - m) * (Math.abs(d) - m);
        return 100 * Math.sqrt(q / pts.size()) / m;
    }

    /** Ровность: 0 сбор данных (< 6 точек), 1 ровное (CV < 25 %), 2 умеренные колебания (< 60 %), 3 сильные скачки. */
    static int evennessGrade(List<Double> pts) {
        if (pts == null || pts.size() < MIN_POINTS) return 0;
        double cv = cvPercent(pts);
        if (Double.isNaN(cv)) return 1;
        return cv < 25 ? 1 : (cv < 60 ? 2 : 3);
    }

    private static final CurrentRing RING = new CurrentRing();
    private static volatile int currentSign = 1;

    /** Знак тока, определённый BatteryPage (−1 — прошивка инвертирует). */
    static void setSign(int sign) { currentSign = sign < 0 ? -1 : 1; }

    static int sign() { return currentSign; }

    static synchronized void recordCurrent(long wallMs, double ma) { RING.add(wallMs, ma); }
    static synchronized List<Double> currentPoints(long now, long windowMs) { return RING.points(now, windowMs); }
    static synchronized double currentAvg(long now, long windowMs) { return RING.avg(now, windowMs); }

    // ---------- Android-часть ----------
    private static final MeterState STATE = new MeterState();

    /** Фоновый вызов раз в 1.5 с (из BatteryPage.tick). */
    static synchronized void tick(Context ctx, double designMah) {
        try {
            Measurement m = sample(ctx, designMah);
            if (m != null) save(ctx, m);
        } catch (Throwable ignored) { }
    }

    /** Текущие {уровень %, статус, plugged, ток мА (NaN)} из системы или null. */
    static double[] read(Context ctx) {
        try {
            Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) return null;
            int level = it.getIntExtra("level", -1), scale = it.getIntExtra("scale", 100);
            if (scale <= 0) scale = 100;
            if (level < 0) return null;
            int pct = (int) Math.round(100.0 * level / scale);
            int status = it.getIntExtra("status", 1);
            int plugged = it.getIntExtra("plugged", 0);
            double ma = Double.NaN;
            try {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (raw != Integer.MIN_VALUE) ma = BatteryPage.currentToMa(raw, -1, true);
                }
            } catch (Throwable ignored) { }
            return new double[]{pct, status, plugged, ma};
        } catch (Throwable ignored) { return null; }
    }

    /** Читает уровень/статус/ток из системы и делает один шаг. Общая логика для приложения и службы. */
    static synchronized Measurement sample(Context ctx, double designMah) {
        double[] r = read(ctx);
        if (r == null) return null;
        return onSample(ctx, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                (int) r[0], (int) r[1], (int) r[2], r[3], designMah);
    }

    /** Один отсчёт (тестовая точка входа без Android-источников). Сохраняет завершённый замер. */
    static synchronized Measurement onSample(Context ctx, long wallMs, long nowMs, int pct, int status,
                                             int plugged, double ma, double designMah) {
        if (!Double.isNaN(ma)) recordCurrent(wallMs, ma * currentSign);
        Measurement m = step(STATE, nowMs, pct, dirOfStatus(status), plugged, ma, designMah);
        if (m != null) save(ctx, m);
        return m;
    }

    private static void save(Context ctx, Measurement m) {
        try {
            List<Measurement> l = load(ctx);
            l.add(m);
            while (l.size() > KEEP) l.remove(0);
            JSONArray a = new JSONArray();
            for (Measurement x : l) {
                JSONObject o = new JSONObject();
                o.put("c", x.capacityMah); o.put("t", x.ts); o.put("d", x.dLevel); o.put("r", x.dir);
                a.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static List<Measurement> load(Context ctx) {
        List<Measurement> l = new ArrayList<>();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Measurement(o.getDouble("c"), o.getLong("t"), o.getInt("d"), o.getInt("r")));
            }
        } catch (Throwable ignored) { }
        return l;
    }

    /** {active(0/1), накоплено мА·ч, пройдено % } для отображения. */
    static synchronized double[] progress() {
        if (!STATE.active) return new double[]{0, 0, 0};
        return new double[]{1, STATE.accMah, Math.abs(STATE.lastLevel - STATE.startLevel)};
    }

    /** Сброс состояния (тесты). */
    static synchronized void reset() {
        STATE.lastLevel = -1; STATE.lastMs = -1; STATE.active = false; STATE.accMah = 0; STATE.startLevel = -1;
        RING.b.clear(); currentSign = 1;
    }
}
