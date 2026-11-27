package com.deviceinfo.app;

import android.content.Context;

import java.util.Calendar;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сводка по часам (последние 48 ч): сколько минут экран был выключен / включён / телефон заряжался,
 * какая сеть была в простое и сколько % ушло в простое. Проценты целые, поэтому падение на 1 %
 * распределяется на всё время, пока уровень держался, — так по часам получается плавная картина.
 */
final class IdleHistory {
    private IdleHistory() {}

    static final String PREFS = "idle_history";
    static final int KEEP_HOURS = 48;
    static final long HOUR = 3600_000L;
    /** Индексы в записи часа. */
    static final int OFF = 0, OFF_PCT = 1, WIFI = 2, MOBILE = 3, CHARGE = 4, SCREEN = 5, FULL = 6, N = 7;

    /** начало часа (wall) → значения (мс, % — по индексам выше). */
    static final TreeMap<Long, double[]> hours = new TreeMap<>();
    static long lastEl = -1, lastWall, levelSinceWall;
    static double lastLevel = Double.NaN;
    static boolean lastScreen, lastPlugged;
    static int lastNet = -1;

    static long hourStart(long wall) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wall);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Разложить amount на промежуток [from, to] по часам пропорционально времени. */
    static void spread(TreeMap<Long, double[]> to, long from, long until, int idx, double amount) {
        if (until <= from || amount == 0) return;
        double total = until - from;
        long t = from;
        while (t < until) {
            long h = hourStart(t), end = Math.min(until, h + HOUR);
            if (end <= t) end = Math.min(until, t + HOUR); // перевод часов
            double[] v = to.get(h);
            if (v == null) { v = new double[N]; to.put(h, v); }
            v[idx] += amount * (end - t) / total;
            t = end;
        }
    }

    /** Время последнего промежутка (до elapsed/wall) по состоянию на прошлом событии. */
    private static void addTime(TreeMap<Long, double[]> to, long el, long wall) {
        if (lastEl < 0 || el <= lastEl || el - lastEl > 12 * HOUR) return;
        long dt = el - lastEl, from = wall - dt;
        if (lastPlugged) spread(to, from, wall, CHARGE, dt);
        else if (lastScreen) spread(to, from, wall, SCREEN, dt);
        else {
            spread(to, from, wall, OFF, dt);
            if (lastNet == IdleNet.WIFI) spread(to, from, wall, WIFI, dt);
            else if (lastNet == IdleNet.MOBILE) spread(to, from, wall, MOBILE, dt);
            if (lastLevel >= 99.5) spread(to, from, wall, FULL, dt);
        }
    }

    /** Событие службы (экран / батарея / зарядка / сеть). level NaN — не изменился; net < 0 — не известен. */
    static synchronized void update(long el, long wall, double level, boolean screen, boolean plugged, int net) {
        addTime(hours, el, wall);
        boolean gap = lastEl < 0 || el <= lastEl || el - lastEl > 12 * HOUR;
        if (!gap && !Double.isNaN(level) && !Double.isNaN(lastLevel) && !lastPlugged && !lastScreen && level < lastLevel - 0.01
                && lastLevel < 99.5) { // первый процент после 100 % ненадёжен: Honor держит 100 % дольше
            // ушло в простое — на всё время, пока держался прежний уровень
            long from = Math.max(levelSinceWall, wall - 12 * HOUR);
            spread(hours, from, wall, OFF_PCT, lastLevel - level);
        }
        boolean stateChanged = gap || screen != lastScreen || plugged != lastPlugged;
        if (stateChanged || (!Double.isNaN(level) && level != lastLevel)) levelSinceWall = wall;
        lastEl = el; lastWall = wall;
        if (!Double.isNaN(level)) lastLevel = level;
        lastScreen = screen; lastPlugged = plugged;
        if (net >= 0) lastNet = net;
        trim(wall);
    }

    /** Только смена сети (без события батареи). */
    static synchronized void net(long el, long wall, int net) {
        if (lastEl < 0) return;
        addTime(hours, el, wall);
        lastEl = el; lastWall = wall; lastNet = net;
    }

    private static void trim(long wall) {
        long min = hourStart(wall) - (KEEP_HOURS - 1) * HOUR;
        while (!hours.isEmpty() && hours.firstKey() < min) hours.remove(hours.firstKey());
    }

    /** Копия с идущим промежутком до el/wall. */
    static synchronized TreeMap<Long, double[]> snapshot(long el, long wall) {
        TreeMap<Long, double[]> c = new TreeMap<>();
        for (Map.Entry<Long, double[]> e : hours.entrySet()) c.put(e.getKey(), e.getValue().clone());
        addTime(c, el, wall);
        return c;
    }

    /** Данные для столбиков за последние count часов (по текущий). */
    static final class Bars {
        String[] labels; double[] values; int[] kinds;
        /** Средний расход в простое на Wi-Fi и мобильной сети, %/ч (NaN — мало данных). */
        double wifiRate = Double.NaN, mobileRate = Double.NaN;
    }

    /** Час с простоем меньше этого — столбик не рисуется (по 1–2 минутам расход не понять). */
    static final long MIN_OFF_MS = 15 * 60_000L;

    static Bars bars(long el, long wall, int count) {
        TreeMap<Long, double[]> s = snapshot(el, wall);
        Bars b = new Bars();
        b.labels = new String[count]; b.values = new double[count]; b.kinds = new int[count];
        long h0 = hourStart(wall);
        double wMs = 0, wPct = 0, mMs = 0, mPct = 0;
        for (int i = 0; i < count; i++) {
            long h = hourStart(h0 - (count - 1 - i) * HOUR + HOUR / 2);
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(h);
            b.labels[i] = String.valueOf(c.get(Calendar.HOUR_OF_DAY));
            double[] v = s.get(h);
            b.values[i] = Double.NaN;
            b.kinds[i] = HoursView.NONE;
            if (v == null) continue;
            double busy = Math.max(v[CHARGE], v[SCREEN]);
            if (v[OFF] >= MIN_OFF_MS && v[OFF] - v[FULL] >= MIN_OFF_MS) {
                b.values[i] = v[OFF_PCT] / ((v[OFF] - v[FULL]) / HOUR);
                boolean mob = v[MOBILE] > v[WIFI];
                b.kinds[i] = mob ? HoursView.MOBILE : HoursView.WIFI;
                if (mob) { mMs += v[OFF] - v[FULL]; mPct += v[OFF_PCT]; } else { wMs += v[OFF] - v[FULL]; wPct += v[OFF_PCT]; }
            } else if (v[OFF] >= MIN_OFF_MS && v[FULL] > v[OFF] / 2) {
                b.kinds[i] = HoursView.FULL;
            } else if (busy > 0) {
                b.kinds[i] = v[CHARGE] >= v[SCREEN] ? HoursView.CHARGE : HoursView.SCREEN;
            }
        }
        if (wMs >= 2 * HOUR) b.wifiRate = wPct / (wMs / HOUR);
        if (mMs >= 2 * HOUR) b.mobileRate = mPct / (mMs / HOUR);
        return b;
    }

    static long savedEl = -1;

    /** Сохранять не чаще раза в 5 минут (события батареи при включённом экране идут часто). */
    static void maybeSave(Context ctx, long el) {
        if (savedEl >= 0 && el - savedEl < 5 * 60_000L && el >= savedEl) return;
        savedEl = el;
        save(ctx);
    }

    static synchronized void save(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, double[]> e : hours.entrySet()) {
            sb.append(e.getKey());
            for (double x : e.getValue()) sb.append(',').append((long) Math.round(x * 1000) / 1000.0);
            sb.append(';');
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("h", sb.toString()).apply();
    }

    static synchronized void load(Context ctx) {
        hours.clear();
        for (String part : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("h", "").split(";")) {
            String[] a = part.split(",");
            if (a.length != N + 1) continue;
            try {
                double[] v = new double[N];
                for (int i = 0; i < N; i++) v[i] = Double.parseDouble(a[i + 1]);
                hours.put(Long.parseLong(a[0]), v);
            } catch (NumberFormatException ignored) { }
        }
        lastEl = -1; lastLevel = Double.NaN; lastNet = -1; // что было без службы — неизвестно
    }

    static synchronized void reset(Context ctx) {
        hours.clear(); lastEl = -1; lastLevel = Double.NaN; lastNet = -1; levelSinceWall = 0;
        if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
