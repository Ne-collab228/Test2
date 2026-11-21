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

    // ---------- Android-часть ----------
    private static final MeterState STATE = new MeterState();

    /** Фоновый вызов раз в 1.5 с (из BatteryPage.tick). */
    static synchronized void tick(Context ctx, double designMah) {
        try {
            Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) return;
            int level = it.getIntExtra("level", -1), scale = it.getIntExtra("scale", 100);
            if (scale <= 0) scale = 100;
            if (level < 0) return;
            int pct = (int) Math.round(100.0 * level / scale);
            int dir = dirOfStatus(it.getIntExtra("status", 1));
            int plugged = it.getIntExtra("plugged", 0);
            double ma = Double.NaN;
            try {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    ma = BatteryPage.currentToMa(raw, -1, true);
                }
            } catch (Throwable ignored) { }
            Measurement m = step(STATE, SystemClock.elapsedRealtime(), pct, dir, plugged, ma, designMah);
            if (m != null) save(ctx, m);
        } catch (Throwable ignored) { }
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
    }
}
