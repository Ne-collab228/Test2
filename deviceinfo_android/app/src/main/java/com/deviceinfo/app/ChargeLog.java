package com.deviceinfo.app;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Тест зарядки: точки текущей (или последней) зарядки — время, уровень, мощность у батареи.
 * Пишется из приложения (пока открыто) и из службы слежения за расходом (в фоне).
 */
public final class ChargeLog {
    static final String PREFS = "charge_log";
    static final int MAX = 720;
    static final long MIN_STEP_MS = 20_000;

    /** Точка: {время мс, уровень %, мощность Вт, ток мА}. */
    static final List<double[]> points = new ArrayList<>();
    static boolean charging;
    static long startMs;
    private static boolean loaded;

    /**
     * Отсчёт. При начале зарядки начинается новая сессия; пока зарядки нет, последняя сессия
     * хранится. Возвращает true, если точка добавлена.
     */
    static synchronized boolean add(long wallMs, double level, double volts, double ma, boolean plugged) {
        if (!plugged) { charging = false; return false; }
        if (!charging) {
            charging = true; startMs = wallMs; points.clear();
        }
        double w = Double.isNaN(volts) || Double.isNaN(ma) ? Double.NaN : volts * ma / 1000.0;
        if (!points.isEmpty()) {
            double[] last = points.get(points.size() - 1);
            if (wallMs - (long) last[0] < MIN_STEP_MS && Math.abs(level - last[1]) < 1) return false;
        }
        points.add(new double[]{wallMs, level, w, ma});
        if (points.size() > MAX) { // прореживание: каждая вторая старая точка
            List<double[]> k = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) if (i % 2 == 0 || i == points.size() - 1) k.add(points.get(i));
            points.clear(); points.addAll(k);
        }
        return true;
    }

    /** Сводка: {пиковая Вт, средняя Вт, от %, до %, длительность мс, точек}; null — нет данных. */
    static synchronized double[] summary() {
        if (points.size() < 2) return null;
        // пик по медиане из 3 соседних точек — одиночный выброс не считается
        double peak = Double.NaN, sum = 0; int n = 0;
        for (int i = 0; i < points.size(); i++) {
            double w = points.get(i)[2];
            if (Double.isNaN(w) || w <= 0) continue;
            sum += w; n++;
            if (i > 0 && i < points.size() - 1) {
                double a = points.get(i - 1)[2], b = points.get(i + 1)[2];
                if (!Double.isNaN(a) && !Double.isNaN(b)) {
                    double m = Math.max(Math.min(a, w), Math.min(Math.max(a, w), b));
                    if (Double.isNaN(peak) || m > peak) peak = m;
                }
            }
        }
        if (n == 0) return null;
        if (Double.isNaN(peak)) peak = sum / n;
        double[] f = points.get(0), l = points.get(points.size() - 1);
        return new double[]{peak, sum / n, f[1], l[1], l[0] - f[0], points.size()};
    }

    /** Вывод о зарядном устройстве по пиковой мощности у батареи. */
    static String verdict(double peakW) {
        if (Double.isNaN(peakW)) return null;
        if (peakW >= 24) return "быстрая зарядка работает (SuperCharge)";
        if (peakW >= 14) return "ускоренная зарядка (≈ 18–22 Вт у блока)";
        if (peakW >= 7) return "обычная зарядка (≈ 10 Вт) — блок или кабель без быстрой зарядки";
        return "медленная зарядка (USB компьютера или слабый блок)";
    }

    static synchronized double[] series(int idx) {
        double[] v = new double[points.size()];
        for (int i = 0; i < v.length; i++) v[i] = points.get(i)[idx];
        return v;
    }

    static synchronized void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("pts", "[]"));
            points.clear();
            for (int i = 0; i < a.length(); i++) {
                JSONArray o = a.getJSONArray(i);
                points.add(new double[]{o.getDouble(0), o.getDouble(1), o.optDouble(2, Double.NaN), o.optDouble(3, Double.NaN)});
            }
            startMs = p.getLong("start", 0);
            // зарядка продолжается после перезапуска приложения — не начинать сессию заново
            if (!points.isEmpty() && System.currentTimeMillis() - (long) points.get(points.size() - 1)[0] < 10 * 60_000L)
                charging = true;
        } catch (Throwable ignored) { }
    }

    static synchronized void save(Context ctx) {
        try {
            JSONArray a = new JSONArray();
            for (double[] d : points) {
                JSONArray o = new JSONArray();
                o.put((long) d[0]); o.put(d[1]);
                o.put(Double.isNaN(d[2]) ? 0 : Math.round(d[2] * 100) / 100.0);
                o.put(Double.isNaN(d[3]) ? 0 : Math.round(d[3]));
                a.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("pts", a.toString()).putLong("start", startMs).apply();
        } catch (Throwable ignored) { }
    }

    /** Тесты. */
    static synchronized void reset() { points.clear(); charging = false; startMs = 0; loaded = true; }
}
