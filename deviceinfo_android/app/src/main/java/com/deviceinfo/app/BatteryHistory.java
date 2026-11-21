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
        public final double temp, volt; public final int status;
        public Sample(long t, double level, int status, boolean charging, double temp, double volt) {
            this.t = t; this.level = level; this.status = status; this.charging = charging;
            this.temp = temp; this.volt = volt;
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
                        s.optDouble("tc", Double.NaN), s.optDouble("v", Double.NaN)));
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
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        boolean changed = last != null && last.status != status;
        if (!changed && last != null && now - last.t < MIN_INTERVAL_MS) return false;
        samples.add(new Sample(now, Math.round(level * 10) / 10.0, status, charging, temp, volt));
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

    /** Скорости %/ч по соседним замерам одного состояния за последние hours часов. */
    public static List<Double> segmentRates(List<Sample> all, long now, boolean charging, int hours) {
        List<Double> rates = new ArrayList<>();
        List<Sample> pts = new ArrayList<>();
        for (Sample s : all) if (s.t >= now - hours * 3600_000L) pts.add(s);
        for (int i = 1; i < pts.size(); i++) {
            Sample a = pts.get(i - 1), b = pts.get(i);
            double dt = (b.t - a.t) / 3600000.0;
            if (dt <= 0 || dt > 2) continue;
            if (a.charging == charging && b.charging == charging) {
                double rate = (b.level - a.level) / dt;
                if ((charging ? rate < -1 : rate > 1) || Math.abs(rate) > 100) continue;
                rates.add(rate);
            }
        }
        return rates;
    }

    static double mean(List<Double> l) {
        double s = 0; for (double d : l) s += d; return s / l.size();
    }

    /** Средний расход %/ч (положительный) или NaN. */
    public static double dischargeRate(List<Sample> all, long now) {
        List<Double> r = segmentRates(all, now, false, 12);
        if (r.isEmpty()) return Double.NaN;
        return -mean(r);
    }

    /** Средняя скорость зарядки %/ч или NaN. */
    public static double chargeRate(List<Sample> all, long now) {
        List<Double> r = segmentRates(all, now, true, 12);
        return r.isEmpty() ? Double.NaN : mean(r);
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

    /** Стабильность разряда: {grade 0 сбор/1 стабильная/2 умеренная/3 нестабильная, cv%, mean, jumps, voltSdMv (NaN)}. */
    public static double[] stability(List<Sample> all, long now) {
        List<Double> rates = new ArrayList<>();
        for (double r : segmentRates(all, now, false, 12)) rates.add(-r);
        if (rates.size() < 3) return new double[]{0, Double.NaN, Double.NaN, 0, Double.NaN};
        double m = mean(rates), sd = 0;
        for (double r : rates) sd += (r - m) * (r - m);
        sd = Math.sqrt(sd / rates.size());
        double cv = Math.abs(m) > 0.01 ? 100 * sd / Math.abs(m) : 0;
        int jumps = 0;
        for (double r : rates) if (r > Math.max(3 * Math.abs(m), 15)) jumps++;
        double grade = (cv < 40 && jumps == 0) ? 1 : (cv < 90 && jumps <= 1 ? 2 : 3);
        List<Double> v = new ArrayList<>();
        for (Sample s : all) if (s.t >= now - 6 * 3600_000L && !Double.isNaN(s.volt) && s.volt > 0) v.add(s.volt);
        double vsd = Double.NaN;
        if (v.size() > 2) {
            double vm = mean(v), q = 0;
            for (double x : v) q += (x - vm) * (x - vm);
            vsd = Math.sqrt(q / v.size()) * 1000;
        }
        return new double[]{grade, cv, m, jumps, vsd};
    }
}
