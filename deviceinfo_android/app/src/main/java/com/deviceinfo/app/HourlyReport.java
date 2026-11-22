package com.deviceinfo.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Автоотчёты в GitHub, пока идёт слежение за расходом: каждый час — короткий (что было за час),
 * раз в сутки — полный. Файлы сжаты (.txt.gz); в каждой папке хранится не больше KEEP файлов,
 * лишние (самые старые) удаляются.
 *
 * За час копятся: события (экран, зарядка, музыка), ток / загрузка процессора / температура
 * при включённом экране (раз в 10 с из службы слежения) и итоги EnergyStats на начало периода.
 */
public final class HourlyReport {
    static final String PREFS = "hourly";
    static final String PREF_ON = "hourly_upload";
    static final int KEEP = 48;
    static final int MAX_EVENTS = 300;
    static final long DAY_MS = 24 * 3600_000L;
    static final int REQ_ALARM = 30;

    private HourlyReport() { }

    // ---------- накопление за период ----------
    static long startWall;
    static double startLevel = Double.NaN;
    /** Итоги на начало периода: {offMs, offPct, offAwake, onMs, onPct, auMs, auPct}. */
    static double[] base;
    static final List<String> events = new ArrayList<>();
    static int n; static double sumMa, minMa = Double.NaN, maxMa = Double.NaN;
    static int nLoad; static double sumLoad, maxLoad = Double.NaN;
    static double minT = Double.NaN, maxT = Double.NaN;
    private static Boolean lastScreen, lastPlugged, lastAudio;
    private static boolean loaded;

    static boolean enabled(Context c) {
        return c.getSharedPreferences(MainActivity.GH, Context.MODE_PRIVATE).getBoolean(PREF_ON, true);
    }

    /** Итоги EnergyStats вместе с идущим отрезком. */
    static double[] snapshot(EnergyStats s, long el, long upt, double lvl) {
        EnergyStats.Totals off = s.withOpen(false, el, upt, lvl), on = s.withOpen(true, el, upt, lvl);
        return new double[]{off.ms, off.pct, off.awakeMs, on.ms, on.pct, s.offAudio.ms + s.openAudioMs(el), s.offAudio.pct};
    }

    /** Начать новый период. */
    static synchronized void begin(EnergyStats s, long wall, long el, long upt, double lvl) {
        startWall = wall; startLevel = lvl;
        base = s == null ? null : snapshot(s, el, upt, lvl);
        events.clear();
        n = 0; sumMa = 0; minMa = Double.NaN; maxMa = Double.NaN;
        nLoad = 0; sumLoad = 0; maxLoad = Double.NaN;
        minT = Double.NaN; maxT = Double.NaN;
    }

    private static String hm(long wall) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(wall)); }

    private static void ev(long wall, String what, double lvl) {
        if (events.size() >= MAX_EVENTS) return;
        events.add(hm(wall) + " " + what + (Double.isNaN(lvl) ? "" : Util.f(" · %.0f %%", lvl)));
    }

    /** Событие из службы слежения (экран / батарея / зарядка). Записываются только изменения. */
    static synchronized void event(long wall, double lvl, boolean screen, boolean plugged, boolean audio, double tempC) {
        if (startWall == 0) startWall = wall;
        if (Double.isNaN(startLevel)) startLevel = lvl;
        temp(tempC);
        if (lastScreen == null || lastScreen != screen) ev(wall, screen ? "экран вкл" : "экран выкл", lvl);
        if (lastPlugged == null || lastPlugged != plugged) {
            if (lastPlugged != null || plugged) ev(wall, plugged ? "зарядка подключена" : "зарядка отключена", lvl);
        }
        if (lastAudio == null || lastAudio != audio) {
            if (lastAudio != null || audio) ev(wall, audio ? "играет музыка" : "музыка выкл", lvl);
        }
        lastScreen = screen; lastPlugged = plugged; lastAudio = audio;
    }

    private static void temp(double t) {
        if (Double.isNaN(t) || t < -20 || t > 90) return;
        minT = Double.isNaN(minT) ? t : Math.min(minT, t);
        maxT = Double.isNaN(maxT) ? t : Math.max(maxT, t);
    }

    /** Замер при включённом экране (раз в 10 с): ток разряда мА, загрузка CPU %, температура °C. */
    static synchronized void sample(double drainMa, double loadPct, double tempC) {
        if (!Double.isNaN(drainMa) && drainMa > 0 && drainMa < 8000) {
            n++; sumMa += drainMa;
            minMa = Double.isNaN(minMa) ? drainMa : Math.min(minMa, drainMa);
            maxMa = Double.isNaN(maxMa) ? drainMa : Math.max(maxMa, drainMa);
        }
        if (!Double.isNaN(loadPct)) {
            nLoad++; sumLoad += loadPct;
            maxLoad = Double.isNaN(maxLoad) ? loadPct : Math.max(maxLoad, loadPct);
        }
        temp(tempC);
    }

    /** Замер из службы: ток, последняя загрузка CPU (если свежая), температура батареи. */
    static void sample(Context ctx) {
        try {
            double[] r = BatteryMeter.read(ctx);
            double ma = r == null || r[2] != 0 ? Double.NaN : -r[3] * BatteryMeter.sign();
            long now = SystemClock.elapsedRealtime();
            long lt = CpuPage.LOAD.lastT();
            double load = lt < 0 || now - lt > 30_000 ? Double.NaN : CpuPage.LOAD.last();
            Intent b = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            double t = b == null ? Double.NaN : b.getIntExtra("temperature", Integer.MIN_VALUE) / 10.0;
            sample(ma, load, t);
        } catch (Throwable ignored) { }
    }

    // ---------- текст ----------
    static String fmtPeriod(long from, long to) {
        SimpleDateFormat d = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        return d.format(new Date(from)) + " – " + hm(to) + " (" + Util.duration((to - from) / 1000.0) + ")"
                + new SimpleDateFormat(", Z", Locale.US).format(new Date(to));
    }

    /** Короткий отчёт за период до момента wall. */
    static synchronized String text(EnergyStats s, String version, long wall, long el, long upt, double lvl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device Info ").append(version).append(" — часовой отчёт\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        long from = startWall > 0 ? startWall : wall;
        sb.append("Период: ").append(fmtPeriod(from, wall)).append('\n');
        if (!Double.isNaN(startLevel) && !Double.isNaN(lvl))
            sb.append(Util.f("Заряд: %.0f %% → %.0f %% (%+.0f %%)\n", startLevel, lvl, lvl - startLevel));
        if (s != null) {
            double[] now = snapshot(s, el, upt, lvl);
            double[] b0 = base != null && base.length == now.length ? base : new double[now.length];
            if (now[0] < b0[0] || now[3] < b0[3] || now[5] < b0[5]) b0 = new double[now.length]; // статистику сбросили
            double offMs = Math.max(0, now[0] - b0[0]), offPct = Math.max(0, now[1] - b0[1]), offAw = Math.max(0, now[2] - b0[2]);
            double onMs = Math.max(0, now[3] - b0[3]), onPct = Math.max(0, now[4] - b0[4]);
            double auMs = Math.max(0, now[5] - b0[5]), auPct = Math.max(0, now[6] - b0[6]);
            sb.append("\n[Экран выключен]\n");
            sb.append("Время: ").append(Util.duration(offMs / 1000.0)).append(Util.f(" · ушло %.0f %%", offPct));
            if (offMs >= 30 * 60_000) sb.append(Util.f(" (≈ %.1f %%/ч)", offPct / (offMs / 3600_000.0)));
            sb.append('\n');
            if (offMs >= 5 * 60_000) sb.append(Util.f("Процессор спал: %.0f %% времени\n", 100 * (1 - Math.min(1, offAw / offMs))));
            if (auMs > 0) sb.append("Играла музыка: ").append(Util.duration(auMs / 1000.0))
                    .append(Util.f(" · ушло %.0f %% (в простой не входит)\n", auPct));
            sb.append("\n[Экран включён]\n");
            sb.append("Время: ").append(Util.duration(onMs / 1000.0)).append(Util.f(" · ушло %.0f %%", onPct));
            if (onMs >= 10 * 60_000) sb.append(Util.f(" (≈ %.0f %%/ч)", onPct / (onMs / 3600_000.0)));
            sb.append('\n');
        }
        if (n > 0) sb.append(Util.f("Ток: в среднем %.0f мА, от %.0f до %.0f мА (%d замеров)\n", sumMa / n, minMa, maxMa, n));
        if (nLoad > 0) sb.append(Util.f("Загрузка CPU: в среднем %.0f %%, макс %.0f %%\n", sumLoad / nLoad, maxLoad));
        if (!Double.isNaN(minT)) sb.append(Util.f("\nТемпература батареи: %.1f–%.1f °C\n", minT, maxT));
        double[] cs = ChargeLog.startMs >= from ? ChargeLog.summary() : null;
        if (cs != null) {
            sb.append("\n[Зарядка]\n").append(Util.f("%.0f → %.0f %% за %s · пик %.1f Вт, в среднем %.1f Вт\n",
                    cs[2], cs[3], Util.duration(cs[4] / 1000.0), cs[0], cs[1]));
            String v = ChargeLog.verdict(cs[0]);
            if (v != null) sb.append(v).append('\n');
        }
        sb.append("\n[События]\n");
        if (events.isEmpty()) sb.append("нет\n");
        for (String e : events) sb.append(e).append('\n');
        if (events.size() >= MAX_EVENTS) sb.append("… (дальше не записано)\n");
        if (s != null) {
            sb.append("\n[С начала слежения]\n");
            if (s.since > 0) sb.append("Слежение с ").append(new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(s.since))).append('\n');
            EnergyStats.Totals off = s.withOpen(false, el, upt, lvl), on = s.withOpen(true, el, upt, lvl);
            double offR = EnergyStats.offRate(off), onR = EnergyStats.onRate(on), sl = s.longSleepShare();
            sb.append("Простой: ").append(Double.isNaN(offR) ? Util.f("сбор данных (%.1f из 2 ч)", off.ms / 3600_000.0)
                    : Util.f("%.2f %%/ч · %s", offR, EnergyPage.GRADE[EnergyStats.offGrade(offR)])).append('\n');
            sb.append("Экран: ").append(Double.isNaN(onR) ? Util.f("сбор данных (%.0f из 30 мин)", on.ms / 60_000.0)
                    : Util.f("%.1f %%/ч", onR)).append('\n');
            sb.append("Глубокий сон: ").append(Double.isNaN(sl) ? "сбор данных"
                    : Util.f("%.0f %% · %s", sl * 100, EnergyPage.SLEEP[EnergyStats.sleepGrade(sl)])).append('\n');
            if (s.offAudio.ms > 0) sb.append("Музыка при выключенном экране: ").append(Util.duration(s.offAudio.ms / 1000.0))
                    .append(Util.f(" · ушло %.0f %%\n", s.offAudio.pct));
        }
        return sb.toString();
    }

    /** Полный отчёт по всем вкладкам (раз в сутки). */
    static String fullText(Context app, String version) {
        List<Page> pages = Pages.create();
        for (Page p : pages) { try { synchronized (p) { p.tick(app); } } catch (Throwable ignored) { } }
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        for (Page p : pages) { try { synchronized (p) { p.tick(app); } } catch (Throwable ignored) { } }
        StringBuilder sb = new StringBuilder();
        sb.append("Device Info ").append(version).append(" — суточный отчёт (полный)\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n')
                .append("Снят в фоне службой слежения\n");
        for (Page p : pages) sb.append("\n===== ").append(p.title()).append(" =====\n").append(MainActivity.buildSafe(p, app).toText());
        return GitHubUploader.redact(sb.toString());
    }

    static byte[] gzip(String s) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
            return bo.toByteArray();
        } catch (Exception e) { return s.getBytes(StandardCharsets.UTF_8); }
    }

    /** Какие файлы удалить, чтобы осталось keep самых новых (имена начинаются с даты — сортируются по времени). */
    static List<String> toDelete(List<String> names, int keep) {
        List<String> l = new ArrayList<>();
        for (String s : names) if (s.endsWith(".txt.gz")) l.add(s);
        Collections.sort(l);
        return l.size() <= keep ? new ArrayList<>() : new ArrayList<>(l.subList(0, l.size() - keep));
    }

    // ---------- отправка ----------
    static String dir(String kind) {
        return "device-reports/" + Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_") + "/" + kind;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Загрузить файл и убрать лишние старые. Возвращает null или текст ошибки. */
    static String push(String token, String repo, String branch, String kind, String name, byte[] data, String msg) {
        String d = dir(kind);
        GitHubUploader.Result r = GitHubUploader.upload(token, repo, branch, d + "/" + name, data, msg);
        if (!r.ok) return r.message;
        List<GitHubUploader.Entry> list = GitHubUploader.list(token, repo, branch, d);
        if (list != null) {
            List<String> names = new ArrayList<>();
            for (GitHubUploader.Entry e : list) names.add(e.name);
            List<String> del = toDelete(names, KEEP);
            int k = 0;
            for (String nm : del) {
                if (k++ >= 5) break;
                for (GitHubUploader.Entry e : list) if (e.name.equals(nm)) {
                    GitHubUploader.delete(token, repo, branch, e.path, e.sha, "Удалён старый автоотчёт " + nm);
                    break;
                }
            }
        }
        return null;
    }

    /** Часовой запуск (в фоне, из службы). */
    static void run(Context ctx, EnergyStats s, String version) {
        SharedPreferences gh = ctx.getSharedPreferences(MainActivity.GH, Context.MODE_PRIVATE);
        String token = gh.getString("token", "");
        if (token.isEmpty() || !enabled(ctx)) return;
        String repo = gh.getString("repo", MainActivity.DEFAULT_REPO), branch = gh.getString("branch", MainActivity.DEFAULT_BRANCH);
        PowerManager.WakeLock wl = null;
        try {
            wl = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deviceinfo:hourly");
            wl.acquire(120_000);
        } catch (Throwable ignored) { }
        try {
            long wall = System.currentTimeMillis(), el = SystemClock.elapsedRealtime(), upt = SystemClock.uptimeMillis();
            double[] r = BatteryMeter.read(ctx);
            double lvl = r == null ? Double.NaN : r[0];
            String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date(wall));
            String text = text(s, version, wall, el, upt, lvl);
            String err = push(token, repo, branch, "hourly", ts + "_v" + version + ".txt.gz", gzip(text),
                    "Часовой отчёт Device Info " + version + ": " + Build.MODEL);
            SharedPreferences.Editor ed = prefs(ctx).edit();
            if (err == null) {
                begin(s, wall, el, upt, lvl);
                ed.putLong("last_ok", wall).remove("last_err");
            } else ed.putString("last_err", hm(wall) + " " + err);
            long lastDaily = prefs(ctx).getLong("last_daily", 0);
            if (err == null && (wall - lastDaily >= DAY_MS - 30 * 60_000L || wall < lastDaily)) {
                String e2 = push(token, repo, branch, "daily", ts + "_v" + version + ".txt.gz", gzip(fullText(ctx, version)),
                        "Суточный отчёт Device Info " + version + ": " + Build.MODEL);
                if (e2 == null) ed.putLong("last_daily", wall);
                else ed.putString("last_err", hm(wall) + " полный: " + e2);
            }
            ed.apply();
            save(ctx);
        } catch (Throwable ignored) {
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
        }
    }

    /** Следующий запуск — в начале следующего часа по часам телефона. */
    static void schedule(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            long next = (now / 3600_000L + 1) * 3600_000L + 30_000; // :00:30
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + (next - now), alarmIntent(ctx));
        } catch (Throwable ignored) { }
    }

    static void cancel(Context ctx) {
        try { ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(ctx)); } catch (Throwable ignored) { }
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, Alarm.class);
        return PendingIntent.getBroadcast(ctx, REQ_ALARM, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Будильник раз в час. Службу слежения отсюда не запускаем (из фона Android этого не даёт),
     * а передаём работу уже идущей службе. Wake lock берётся сразу, чтобы телефон не уснул
     * до начала отправки.
     */
    public static class Alarm extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PowerManager.WakeLock wl = null;
            try {
                wl = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deviceinfo:hourly-alarm");
                wl.acquire(150_000);
            } catch (Throwable ignored) { }
            EnergyService.hourly(c.getApplicationContext(), wl);
        }
    }

    /** Строка состояния для вкладки «Энергия». */
    static String status(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long ok = p.getLong("last_ok", 0);
        String err = p.getString("last_err", null);
        String s = ok > 0 ? "последний " + new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(ok)) : "ещё не было";
        return err != null ? s + " · ошибка " + err : s;
    }

    // ---------- хранение (переживает перезапуск службы) ----------
    static synchronized void save(Context ctx) {
        try {
            JSONArray ev = new JSONArray();
            for (String e : events) ev.put(e);
            JSONArray b = new JSONArray();
            if (base != null) for (double d : base) b.put(d);
            prefs(ctx).edit().putLong("start", startWall).putFloat("start_lvl", (float) startLevel)
                    .putString("events", ev.toString()).putString("base", b.toString())
                    .putInt("n", n).putFloat("sum_ma", (float) sumMa).putFloat("min_ma", (float) minMa).putFloat("max_ma", (float) maxMa)
                    .putInt("n_load", nLoad).putFloat("sum_load", (float) sumLoad).putFloat("max_load", (float) maxLoad)
                    .putFloat("min_t", (float) minT).putFloat("max_t", (float) maxT).apply();
        } catch (Throwable ignored) { }
    }

    static synchronized void load(Context ctx, EnergyStats s) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences p = prefs(ctx);
            if (!p.contains("start") || System.currentTimeMillis() - p.getLong("start", 0) > 3 * 3600_000L) {
                long el = SystemClock.elapsedRealtime();
                double[] r = BatteryMeter.read(ctx);
                begin(s, System.currentTimeMillis(), el, SystemClock.uptimeMillis(), r == null ? Double.NaN : r[0]);
                return;
            }
            startWall = p.getLong("start", 0); startLevel = p.getFloat("start_lvl", Float.NaN);
            events.clear();
            JSONArray ev = new JSONArray(p.getString("events", "[]"));
            for (int i = 0; i < ev.length(); i++) events.add(ev.getString(i));
            JSONArray b = new JSONArray(p.getString("base", "[]"));
            base = b.length() == 0 ? null : new double[b.length()];
            for (int i = 0; i < b.length(); i++) base[i] = b.getDouble(i);
            n = p.getInt("n", 0); sumMa = p.getFloat("sum_ma", 0); minMa = p.getFloat("min_ma", Float.NaN); maxMa = p.getFloat("max_ma", Float.NaN);
            nLoad = p.getInt("n_load", 0); sumLoad = p.getFloat("sum_load", 0); maxLoad = p.getFloat("max_load", Float.NaN);
            minT = p.getFloat("min_t", Float.NaN); maxT = p.getFloat("max_t", Float.NaN);
        } catch (Throwable ignored) { }
    }

    /** Тесты. */
    static synchronized void reset() {
        begin(null, 0, 0, 0, Double.NaN);
        startWall = 0; lastScreen = null; lastPlugged = null; lastAudio = null; loaded = true;
    }
}
