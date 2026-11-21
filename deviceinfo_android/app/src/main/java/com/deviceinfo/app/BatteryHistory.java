package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * История замеров батареи (порт BatteryHistory из aida_py). Хранится в SharedPreferences
 * "battery_history" (ключ "data" — JSON). Расчёты — чистые статические методы над списком замеров.
 */
public final class BatteryHistory {
    public static final String PREFS = "battery_history";
    static final int MAX_SAMPLES = 1500, MAX_SESSIONS = 50;
    static final long MIN_INTERVAL_MS = 60_000;

    /** Замер: время (мс), уровень %, состояние зарядки (заряжается/полный), температура °C, напряжение В. */
    public static final class Sample {
        public final long t; public final double level; public final boolean charging;
        public final double temp, volt, ma; public final int status;
        public Sample(long t, double level, int status, boolean charging, double temp, double volt, double ma) {
            this.t = t; this.level = level; this.status = status; this.charging = charging;
            this.temp = temp; this.volt = volt; this.ma = ma;
        }
        public Sample(long t, double level, int status, boolean charging, double temp, double volt) {
            this(t, level, status, charging, temp, volt, Double.NaN);
        }
    }

    /** Сеанс зарядки; end/to = NaN/0, пока идёт. */
    public static final class Session {
        public long start, end; public double from, to; public boolean open;
    }

    public final List<Sample> samples = new ArrayList<>();
    public final List<Session> sessions = new ArrayList<>();
    public Session open;

    private final SharedPreferences prefs;

    public BatteryHistory(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** Для тестов без Android. */
    BatteryHistory() { prefs = null; }

    private void load() {
        try {
            JSONObject o = new JSONObject(prefs.getString("data", "{}"));
            JSONArray a = o.optJSONArray("samples");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                samples.add(new Sample(s.getLong("t"), s.getDouble("l"), s.optInt("st", 0), s.getBoolean("c"),
                        s.optDouble("tc", Double.NaN), s.optDouble("v", Double.NaN), s.optDouble("i", Double.NaN)));
            }
            a = o.optJSONArray("sessions");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                Session x = new Session();
                x.start = s.getLong("s"); x.end = s.getLong("e"); x.from = s.getDouble("f"); x.to = s.getDouble("to");
                sessions.add(x);
            }
            JSONObject op = o.optJSONObject("open");
            if (op != null) { open = new Session(); open.start = op.getLong("s"); open.from = op.getDouble("f"); open.open = true; }
        } catch (Throwable ignored) { }
    }

    private void save() {
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (Sample s : samples) {
                JSONObject j = new JSONObject();
                j.put("t", s.t); j.put("l", s.level); j.put("st", s.status); j.put("c", s.charging);
                if (!Double.isNaN(s.temp)) j.put("tc", s.temp);
                if (!Double.isNaN(s.volt)) j.put("v", s.volt);
                if (!Double.isNaN(s.ma)) j.put("i", s.ma);
                a.put(j);
            }
            o.put("samples", a);
            JSONArray ss = new JSONArray();
            for (Session s : sessions) {
                JSONObject j = new JSONObject();
                j.put("s", s.start); j.put("e", s.end); j.put("f", s.from); j.put("to", s.to);
                ss.put(j);
            }
            o.put("sessions", ss);
            if (open != null) {
                JSONObject j = new JSONObject();
                j.put("s", open.start); j.put("f", open.from);
                o.put("open", j);
            }
            prefs.edit().putString("data", o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Добавить замер (не чаще раза в 60 с, кроме смены состояния). true, если добавлен. */
    public boolean add(long now, double level, int status, boolean charging, double temp, double volt) {
        return add(now, level, status, charging, temp, volt, Double.NaN);
    }

    /** То же с током (мА, знак: + в батарею), усреднённым за последнюю минуту (NaN — нет данных). */
    public boolean add(long now, double level, int status, boolean charging, double temp, double volt, double ma) {
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        boolean changed = last != null && last.status != status;
        if (!changed && last != null && now - last.t < MIN_INTERVAL_MS) return false;
        samples.add(new Sample(now, Math.round(level * 10) / 10.0, status, charging, temp, volt, ma));
        while (samples.size() > MAX_SAMPLES) samples.remove(0);
        if (charging && open == null) {
            open = new Session(); open.start = now; open.from = level; open.open = true;
        } else if (!charging && open != null) {
            open.end = now; open.to = level; open.open = false;
            if (open.end - open.start > 30_000) sessions.add(open);
            while (sessions.size() > MAX_SESSIONS) sessions.remove(0);
            open = null;
        }
        save();
        return true;
    }

    /** Сеансы, новые первыми (включая идущий). */
    public List<Session> allSessions() {
        List<Session> r = new ArrayList<>();
        if (open != null) r.add(open);
        for (int i = sessions.size() - 1; i >= 0; i--) r.add(sessions.get(i));
        return r;
    }

    static final long MAX_GAP_MS = 5 * 60_000L, MIN_SPAN_MS = 15 * 60_000L;
    static final double MIN_DLEVEL = 2.0;

    /**
     * Скорость по последнему непрерывному отрезку одного состояния (разрывы ≤ 5 мин, последний замер не старше 5 мин):
     * линейная регрессия уровня по времени. Возвращает {наклон %/ч (со знаком), длительность мин, Δуровня %}
     * или null, если отрезок короче 15 мин или уровень изменился менее чем на 2 %.
     */
    public static double[] regressionRate(List<Sample> all, long now, boolean charging) {
        int end = all.size() - 1;
        if (end < 1) return null;
        Sample last = all.get(end);
        if (last.charging != charging || now - last.t > MAX_GAP_MS) return null;
        int start = end;
        while (start > 0) {
            Sample p = all.get(start - 1), c = all.get(start);
            if (p.charging != charging || c.t - p.t > MAX_GAP_MS || c.t - p.t <= 0) break;
            start--;
        }
        if (end - start < 1) return null;
        long t0 = all.get(start).t;
        long span = last.t - t0;
        double dLevel = last.level - all.get(start).level;
        if (span < MIN_SPAN_MS || Math.abs(dLevel) < MIN_DLEVEL) return null;
        int n = end - start + 1;
        double mx = 0, my = 0;
        for (int i = start; i <= end; i++) { mx += (all.get(i).t - t0) / 3600000.0; my += all.get(i).level; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0;
        for (int i = start; i <= end; i++) {
            double x = (all.get(i).t - t0) / 3600000.0 - mx;
            sxy += x * (all.get(i).level - my); sxx += x * x;
        }
        if (sxx <= 0) return null;
        return new double[]{sxy / sxx, span / 60000.0, dLevel};
    }

    /** Средний расход %/ч (положительный) или NaN. */
    public static double dischargeRate(List<Sample> all, long now) {
        double[] r = regressionRate(all, now, false);
        return r == null || r[0] >= 0 ? Double.NaN : -r[0];
    }

    /** Средняя скорость зарядки %/ч или NaN. */
    public static double chargeRate(List<Sample> all, long now) {
        double[] r = regressionRate(all, now, true);
        return r == null || r[0] <= 0 ? Double.NaN : r[0];
    }

    /** Оставшиеся секунды: {kind (1 разряд, 2 зарядка, 0 нет), seconds}. */
    public static double[] estimate(List<Sample> all, long now, double level, boolean charging) {
        if (charging) {
            double rate = chargeRate(all, now);
            if (rate > 0.1) return new double[]{2, (100 - level) / rate * 3600};
        } else {
            double rate = dischargeRate(all, now);
            if (rate > 0.05) return new double[]{1, level / rate * 3600};
        }
        return new double[]{0, 0};
    }
}
