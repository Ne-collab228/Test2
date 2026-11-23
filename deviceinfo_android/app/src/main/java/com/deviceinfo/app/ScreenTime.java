package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Время работы экрана по дням (по местному времени): сколько часов экран был включён и сколько раз
 * его включали. Считается по событиям «экран вкл/выкл» службы слежения; длительность — по
 * elapsedRealtime (не зависит от перевода часов), отрезок через полночь делится между днями.
 */
final class ScreenTime {
    private ScreenTime() {}

    static final String PREFS = "screen_time";
    static final int KEEP_DAYS = 8;
    /** Отрезок длиннее — сбой (служба не видела выключения), берём не больше этого. */
    static final long MAX_SPAN_MS = 16 * 3600_000L;
    /** Служба не сохраняла состояние дольше — идущий отрезок закрывается на последнем сохранении. */
    static final long STALE_MS = 10 * 60_000L;

    /** день (yyyyMMdd) → {мс экрана, число включений}. */
    static final TreeMap<Integer, long[]> days = new TreeMap<>();
    /** Экран включён с (elapsed / wall), -1 — выключен или неизвестно. */
    static long onEl = -1, onWall;

    static int dayKey(long wall) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wall);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    static long nextMidnight(long wall) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wall);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static long[] day(int k) {
        long[] d = days.get(k);
        if (d == null) { d = new long[2]; days.put(k, d); }
        return d;
    }

    /** Добавить [endWall - dur, endWall] с разбиением по полуночам. */
    static void addSpan(TreeMap<Integer, long[]> to, long endWall, long dur) {
        long t = endWall - dur;
        while (dur > 0) {
            long cut = Math.min(endWall, nextMidnight(t));
            long part = Math.max(0, cut - t);
            if (part == 0) break;
            long[] d = to.get(dayKey(t));
            if (d == null) { d = new long[2]; to.put(dayKey(t), d); }
            d[0] += part;
            dur -= part; t = cut;
        }
    }

    /** Событие: экран сейчас включён/выключен. */
    static synchronized void event(long elapsed, long wall, boolean screenOn) {
        if (screenOn) {
            if (onEl < 0 || elapsed < onEl) {
                onEl = elapsed; onWall = wall;
                day(dayKey(wall))[1]++;
            }
        } else if (onEl >= 0) {
            closeSpan(elapsed, wall);
            onEl = -1;
        }
        trim(wall);
    }

    private static void closeSpan(long elapsed, long wall) {
        long dur = elapsed - onEl;
        if (dur > 0) addSpan(days, wall, Math.min(dur, MAX_SPAN_MS));
    }

    /** Служба остановлена: закрыть идущий отрезок. */
    static synchronized void stop(long elapsed, long wall) {
        if (onEl >= 0) closeSpan(elapsed, wall);
        onEl = -1;
    }

    private static void trim(long wall) {
        int min = dayKey(wall - (KEEP_DAYS - 1) * 86_400_000L);
        while (!days.isEmpty() && days.firstKey() < min) days.remove(days.firstKey());
    }

    /** Дни вместе с идущим отрезком (копия): день → {мс, включений}. */
    static synchronized TreeMap<Integer, long[]> snapshot(long elapsed, long wall) {
        TreeMap<Integer, long[]> c = new TreeMap<>();
        for (java.util.Map.Entry<Integer, long[]> e : days.entrySet()) c.put(e.getKey(), e.getValue().clone());
        if (onEl >= 0 && elapsed > onEl) addSpan(c, wall, Math.min(elapsed - onEl, MAX_SPAN_MS));
        return c;
    }

    /** Экран за день wall (с идущим отрезком), мс. */
    static long msOn(long elapsed, long wall, long dayWall) {
        long[] d = snapshot(elapsed, wall).get(dayKey(dayWall));
        return d == null ? 0 : d[0];
    }

    static String hm(long ms) {
        long m = Math.round(ms / 60_000.0);
        return m >= 60 ? (m / 60) + " ч " + (m % 60) + " мин" : m + " мин";
    }

    static String dayName(int key, long now) {
        if (key == dayKey(now)) return "сегодня";
        if (key == dayKey(now - 86_400_000L)) return "вчера";
        Calendar c = Calendar.getInstance();
        c.set(key / 10000, key / 100 % 100 - 1, key % 100, 12, 0, 0);
        return new SimpleDateFormat("EE dd.MM", new Locale("ru")).format(new Date(c.getTimeInMillis()));
    }

    /** Строки «день — время (включений)», новые сверху. */
    static List<String[]> rows(long elapsed, long wall) {
        TreeMap<Integer, long[]> s = snapshot(elapsed, wall);
        List<String[]> out = new ArrayList<>();
        for (Integer k : s.descendingKeySet()) {
            long[] d = s.get(k);
            out.add(new String[]{dayName(k, wall), hm(d[0]) + (d[1] > 0 ? " · включали " + d[1] + " раз" : "")});
        }
        return out;
    }

    /** Среднее за полные прошлые дни (не сегодня), мс; -1 — нет данных. */
    static long avgPast(long elapsed, long wall) {
        TreeMap<Integer, long[]> s = snapshot(elapsed, wall);
        int today = dayKey(wall);
        long sum = 0; int n = 0;
        for (java.util.Map.Entry<Integer, long[]> e : s.entrySet()) if (e.getKey() != today) { sum += e.getValue()[0]; n++; }
        return n == 0 ? -1 : sum / n;
    }

    /** Короткая строка для отчёта: «сегодня 3 ч 10 мин (вкл. 40 раз) · вчера 5 ч 2 мин». */
    static String summary(long elapsed, long wall) {
        TreeMap<Integer, long[]> s = snapshot(elapsed, wall);
        long[] t = s.get(dayKey(wall)), y = s.get(dayKey(wall - 86_400_000L));
        if (t == null && y == null) return null;
        StringBuilder b = new StringBuilder("сегодня ").append(hm(t == null ? 0 : t[0]));
        if (t != null && t[1] > 0) b.append(" (включали ").append(t[1]).append(" раз)");
        if (y != null) b.append(" · вчера ").append(hm(y[0]));
        return b.toString();
    }

    static synchronized void save(Context ctx) { save(ctx, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()); }

    static synchronized void save(Context ctx, long elapsed, long wall) {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<Integer, long[]> e : days.entrySet())
            b.append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]).append(';');
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("days", b.toString())
                .putLong("on_el", onEl).putLong("on_wall", onWall)
                .putLong("saved_el", elapsed).putLong("saved_wall", wall).apply();
    }

    /** Загрузить; nowElapsed меньше сохранённого — была перезагрузка, идущий отрезок неизвестен. */
    static synchronized void load(Context ctx, long nowElapsed) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        days.clear();
        for (String part : p.getString("days", "").split(";")) {
            String[] a = part.split(":");
            if (a.length != 3) continue;
            try { days.put(Integer.parseInt(a[0]), new long[]{Long.parseLong(a[1]), Long.parseLong(a[2])}); }
            catch (NumberFormatException ignored) { }
        }
        onEl = p.getLong("on_el", -1); onWall = p.getLong("on_wall", 0);
        long savedEl = p.getLong("saved_el", -1), savedWall = p.getLong("saved_wall", 0);
        if (onEl > nowElapsed || savedEl > nowElapsed || savedEl < 0) onEl = -1;          // перезагрузка
        else if (onEl >= 0 && nowElapsed - savedEl > STALE_MS) {
            // служба долго не работала: что было после последнего сохранения — неизвестно
            closeSpan(savedEl, savedWall);
            onEl = -1;
        }
    }

    static synchronized void reset(Context ctx) {
        days.clear(); onEl = -1;
        if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
