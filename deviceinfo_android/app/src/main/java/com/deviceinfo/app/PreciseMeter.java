package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Точный замер ёмкости: один длинный разряд (по умолчанию до 50 % шкалы).
 * Ошибка уровня заряда (±1 % на каждом конце) делится на весь пройденный отрезок,
 * поэтому на 40–50 % разряда она даёт ±3–4 %, а не ±15 % как на 5 %.
 *
 * Правила:
 *  - старт только на смене уровня при разряде и не выше 97 % (верх шкалы MagicOS «держит» 100 %);
 *  - ток интегрируется по трапеции |I|·dt, пауза между отсчётами до 120 с допускается;
 *  - итог считается строго на границах уровней: ёмкость = мА·ч(от старта до границы) / Δ% × 100;
 *  - конец: пройдено target %, или уровень ≤ 10 %, или «Завершить сейчас» (если Δ ≥ 20 %);
 *  - зарядка или пауза > 120 с: если уже Δ ≥ 20 % — итог по последней границе, иначе замер прерван.
 * Ядро — чистая математика без Android (step).
 */
public final class PreciseMeter {
    static final String PREFS = "battery_precise";
    static final int MIN_DELTA = 20, DEFAULT_TARGET = 50, MAX_START = 97, MIN_END = 10, KEEP = 10;
    static final long MAX_GAP_MS = 120_000;

    static final class State {
        int target = DEFAULT_TARGET;
        int lastLevel = -1; long lastMs = -1; double lastMa = Double.NaN;
        boolean active;
        int startLevel = -1; long startMs = -1; double acc;
        int edgeLevel = -1; double edgeAcc; long edgeMs = -1;
        /** Почему замер закончился без результата (для уведомления), иначе null. */
        String abort;
        boolean done;
    }

    static final class Result {
        final double capacityMah; final int delta; final long durationMs; final double accuracyPct;
        final String reason; final long ts;
        Result(double c, int d, long dur, String reason, long ts) {
            capacityMah = c; delta = d; durationMs = dur; accuracyPct = accuracy(d); this.reason = reason; this.ts = ts;
        }
    }

    /** Оценка погрешности, %: ±1 % уровня на каждом конце (вместе ≈1.41 %) делится на Δ, плюс ±2 % датчик тока. */
    static double accuracy(int delta) {
        if (delta <= 0) return Double.NaN;
        double gauge = 141.0 / delta;
        return Math.sqrt(gauge * gauge + 4.0);
    }

    /**
     * Один отсчёт. discharging — батарея разряжается и зарядка не подключена.
     * finishNow — пользователь нажал «Завершить сейчас». Возвращает результат или null.
     */
    static Result step(State s, long nowMs, int level, boolean discharging, double currentMa, boolean finishNow) {
        if (s.done || level < 0) return null;
        if (s.lastMs < 0) {
            s.lastLevel = level; s.lastMs = nowMs; s.lastMa = currentMa;
            return null;
        }
        long dt = nowMs - s.lastMs;
        boolean gap = dt < 0 || dt > MAX_GAP_MS;
        boolean haveI = !Double.isNaN(currentMa);

        if (s.active) {
            if (!discharging) return endEarly(s, "подключена зарядка");
            if (gap) return endEarly(s, "система приостановила замер (пауза " + (dt / 1000) + " с)");
            if (haveI) {
                double a = Math.abs(currentMa), b = Double.isNaN(s.lastMa) ? a : Math.abs(s.lastMa);
                s.acc += (a + b) / 2.0 * dt / 3600000.0;
            }
            if (level > s.lastLevel) return endEarly(s, "уровень заряда вырос");
            if (level < s.lastLevel) { s.edgeLevel = level; s.edgeAcc = s.acc; s.edgeMs = nowMs; }
            int d = s.startLevel - s.edgeLevel;
            if (d >= s.target || (s.edgeLevel <= MIN_END && d >= MIN_DELTA)) return finish(s, "пройдено " + d + " %");
            if (finishNow) return endEarly(s, "завершено вручную");
        } else if (discharging && !gap && level < s.lastLevel && level <= MAX_START) {
            s.active = true;
            s.startLevel = level; s.startMs = nowMs; s.acc = 0;
            s.edgeLevel = level; s.edgeAcc = 0; s.edgeMs = nowMs;
            s.target = Math.min(s.target, Math.max(MIN_DELTA, level - MIN_END));
        } else if (finishNow) {
            s.done = true; s.abort = "замер ещё не начался";
        }
        s.lastLevel = level; s.lastMs = nowMs; s.lastMa = currentMa;
        return null;
    }

    private static Result endEarly(State s, String why) {
        int d = s.startLevel - s.edgeLevel;
        if (d >= MIN_DELTA) return finish(s, why + ", пройдено " + d + " %");
        s.done = true; s.active = false;
        s.abort = why + " — пройдено только " + Math.max(0, d) + " % (нужно ≥ " + MIN_DELTA + " %)";
        return null;
    }

    private static Result finish(State s, String reason) {
        int d = s.startLevel - s.edgeLevel;
        s.done = true; s.active = false;
        return new Result(s.edgeAcc / d * 100.0, d, s.edgeMs - s.startMs, reason, System.currentTimeMillis());
    }

    /** {активен 0/1, пройдено %, цель %, накоплено мА·ч, оценка ёмкости по ходу (NaN)} */
    static double[] progress(State s) {
        if (s == null || !s.active) return new double[]{0, 0, s == null ? 0 : s.target, 0, Double.NaN};
        int d = s.startLevel - s.edgeLevel;
        return new double[]{1, d, s.target, s.acc, d > 0 ? s.edgeAcc / d * 100.0 : Double.NaN};
    }

    // ---------- хранение ----------
    static void save(Context ctx, Result r) {
        try {
            List<Result> l = load(ctx);
            l.add(r);
            while (l.size() > KEEP) l.remove(0);
            JSONArray a = new JSONArray();
            for (Result x : l) {
                JSONObject o = new JSONObject();
                o.put("c", x.capacityMah); o.put("d", x.delta); o.put("u", x.durationMs);
                o.put("r", x.reason); o.put("t", x.ts);
                a.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static List<Result> load(Context ctx) {
        List<Result> l = new ArrayList<>();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Result(o.getDouble("c"), o.getInt("d"), o.getLong("u"), o.optString("r", ""), o.getLong("t")));
            }
        } catch (Throwable ignored) { }
        return l;
    }

    static Result last(Context ctx) {
        List<Result> l = load(ctx);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    static void saveAbort(Context ctx, String why) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("abort", why).putLong("abort_t", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) { }
    }

    static String lastAbort(Context ctx) {
        try { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("abort", null); }
        catch (Throwable e) { return null; }
    }

    static void clearAbort(Context ctx) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("abort").apply(); }
        catch (Throwable ignored) { }
    }
}
