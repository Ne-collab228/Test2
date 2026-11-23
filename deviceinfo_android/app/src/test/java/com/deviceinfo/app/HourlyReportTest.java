package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HourlyReportTest {
    static final long M = 60_000L, H = 60 * M;

    @Test public void hourTextHasDeltasEventsAndSamples() {
        HourlyReport.reset();
        ChargeLog.reset();
        EnergyStats s = new EnergyStats();
        long w0 = 1_700_000_000_000L;
        s.event(0, 0, w0, 80, true, false, false);
        s.event(10 * M, 10 * M, w0 + 10 * M, 78, false, false, false);   // 10 мин экрана, −2 %
        HourlyReport.begin(s, w0, 0, 0, 80);                               // база: пусто (отрезок экрана ещё открыт? нет — закрыт выше)
        HourlyReport.event(w0 + 10 * M, 78, false, false, false, 30.5);
        HourlyReport.event(w0 + 40 * M, 77, false, false, true, 31.0);     // музыка
        HourlyReport.sample(500, 20, 32.0);
        HourlyReport.sample(700, 40, Double.NaN);
        String t = HourlyReport.text(s, "9.9", w0 + H, 60 * M, 12 * M, 76);
        assertTrue(t, t.contains("часовой отчёт"));
        assertTrue(t, t.contains("Заряд: 80 % → 76 % (-4 %)"));
        assertTrue(t, t.contains("экран выкл · 78 %"));
        assertTrue(t, t.contains("играет звук · 77 %"));
        assertTrue(t, t.contains("в среднем 600 мА, от 500 до 700 мА (2 замеров)"));
        assertTrue(t, t.contains("Загрузка CPU: в среднем 30 %, макс 40 %"));
        assertTrue(t, t.contains("30.5–32.0 °C"));
        assertTrue(t, t.contains("[Экран выключен]"));
        assertTrue(t, t.contains("Время: 50 мин"));                       // идущий отрезок экрана выкл
        assertTrue(t, t.contains("[С начала слежения]"));
    }

    @Test public void baseResetWhenStatsReset() {
        HourlyReport.reset();
        EnergyStats s = new EnergyStats();
        s.event(0, 0, 1, 80, false, false, false);
        s.event(3 * H, 10 * M, 2, 77, true, false, false);
        HourlyReport.begin(s, 1, 3 * H, 10 * M, 77);
        EnergyStats fresh = new EnergyStats();                            // статистику сбросили
        fresh.event(3 * H, 10 * M, 3, 77, false, false, false);
        String t = HourlyReport.text(fresh, "9.9", 3 * H + 40 * M, 3 * H + 40 * M, 12 * M, 76);
        assertTrue(t, t.contains("Время: 40 мин 0 с · ушло 1 %"));
    }

    @Test public void pruneKeepsNewest() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 50; i++) names.add(String.format("2026-11-%02d_10-00_v1.txt.gz", i % 28 + 1) + (i >= 28 ? "x" : ""));
        names.clear();
        for (int i = 10; i < 60; i++) names.add("2026-11-22_" + i + "-00_v1.txt.gz");
        names.add("readme.md");
        java.util.Collections.shuffle(names, new java.util.Random(1));
        List<String> del = HourlyReport.toDelete(names, 48);
        assertEquals(Arrays.asList("2026-11-22_10-00_v1.txt.gz", "2026-11-22_11-00_v1.txt.gz"), del);
        assertTrue(HourlyReport.toDelete(names.subList(0, 10), 48).isEmpty());
    }

    @Test public void gzipRoundTrip() throws Exception {
        String s = "Отчёт\n".repeat(500);
        byte[] g = HourlyReport.gzip(s);
        assertTrue(g.length < s.getBytes(StandardCharsets.UTF_8).length / 5);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(g))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            assertEquals(s, bo.toString("UTF-8"));
        }
    }

    @Test public void persistsAcrossRestart() {
        Context ctx = RuntimeEnvironment.getApplication();
        HourlyReport.reset();
        long now = System.currentTimeMillis();
        HourlyReport.begin(null, now - 20 * M, 0, 0, 70);
        HourlyReport.event(now - 10 * M, 69, true, false, false, 30);
        HourlyReport.sample(400, Double.NaN, Double.NaN);
        HourlyReport.save(ctx);
        HourlyReport.reset();
        java.lang.reflect.Field f;
        try { f = HourlyReport.class.getDeclaredField("loaded"); f.setAccessible(true); f.setBoolean(null, false); }
        catch (Exception e) { throw new RuntimeException(e); }
        HourlyReport.load(ctx, null);
        assertEquals(now - 20 * M, HourlyReport.startWall);
        assertEquals(2, HourlyReport.events.size());                     // + «служба перезапущена»
        assertEquals(1, HourlyReport.n);
        assertEquals(400, HourlyReport.sumMa, 1e-6);
    }

    @Test public void energyPageShowsUploadStatus() {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences(MainActivity.GH, Context.MODE_PRIVATE).edit().putString("token", "x").commit();
        EnergyService.running = true;
        try {
            Section sec = new Section();
            new EnergyPage().build(ctx, sec);
            assertTrue(sec.toText(), sec.toText().contains("Отчёты в GitHub: каждый час · ещё не было"));
            assertTrue(sec.toText(), sec.toText().contains("Отправить часовой отчёт сейчас"));
        } finally {
            EnergyService.running = false;
            ctx.getSharedPreferences(MainActivity.GH, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void alarmWithoutServiceIsHarmless() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyService.running = false;
        assertFalse(EnergyService.hourly(ctx, null));
        new HourlyReport.Alarm().onReceive(ctx, new android.content.Intent());
        HourlyReport.schedule(ctx);
        android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        assertNotNull(org.robolectric.Shadows.shadowOf(am).getNextScheduledAlarm());
        HourlyReport.cancel(ctx);
        assertNull(org.robolectric.Shadows.shadowOf(am).getNextScheduledAlarm());
    }

    /** Время на зарядке (с экраном и без) и предупреждение о малом числе замеров. */
    @Test public void chargeSpansAndLowSamples() {
        HourlyReport.reset();
        long w0 = 1_700_000_000_000L;
        HourlyReport.begin(null, w0, 0, 0, 50);
        HourlyReport.event(w0, 50, true, false, false, 30);              // экран вкл
        HourlyReport.event(w0 + 10 * M, 48, true, true, false, 30);      // зарядка, экран вкл
        HourlyReport.event(w0 + 20 * M, 52, false, true, false, 30);     // экран выкл на зарядке
        HourlyReport.event(w0 + 50 * M, 60, false, false, false, 30);    // зарядку сняли
        long[] sp = HourlyReport.spans(w0 + H);
        assertEquals(20 * M, sp[0]);   // экран вкл: 0–20 мин
        assertEquals(40 * M, sp[1]);   // на зарядке: 10–50 мин
        assertEquals(10 * M, sp[2]);   // на зарядке с экраном: 10–20 мин
        String t = HourlyReport.text(null, "9.9", w0 + H, 0, 0, 60);
        assertTrue(t, t.contains("На зарядке: 40 мин 0 с (экран вкл 10 мин 0 с / выкл 30 мин 0 с)"));
        // 20 мин экрана = ~120 замеров ожидается, было 5
        for (int i = 0; i < 5; i++) HourlyReport.sample(500, Double.NaN, Double.NaN);
        assertNotNull(HourlyReport.lowSamples(w0 + H));
        assertTrue(HourlyReport.text(null, "9.9", w0 + H, 0, 0, 60).contains("замеров мало (5 из ~120)"));
        for (int i = 0; i < 100; i++) HourlyReport.sample(500, Double.NaN, Double.NaN);
        assertNull(HourlyReport.lowSamples(w0 + H));
    }

    /** После перезапуска службы состояние не записывается заново — одна строка «перезапущена». */
    @Test public void restartNoDuplicateEvents() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        HourlyReport.reset();
        long now = System.currentTimeMillis();
        HourlyReport.begin(null, now - 20 * M, 0, 0, 70);
        HourlyReport.event(now - 15 * M, 70, true, true, true, 30);
        assertEquals(3, HourlyReport.events.size());                      // экран, зарядка, звук
        HourlyReport.save(ctx);
        HourlyReport.reset();
        java.lang.reflect.Field f = HourlyReport.class.getDeclaredField("loaded");
        f.setAccessible(true); f.setBoolean(null, false);
        HourlyReport.load(ctx, null);
        assertEquals(4, HourlyReport.events.size());
        assertTrue(HourlyReport.events.get(3).endsWith("служба слежения перезапущена"));
        HourlyReport.event(now, 70, true, true, true, 30);                // то же состояние — без новых строк
        assertEquals(4, HourlyReport.events.size());
        prefsClear(ctx);
    }

    /** Отчёт после неудачных отправок говорит, с какого времени не отправлялось; 100 % — расход не виден. */
    @Test public void failedPeriodAndFullCharge() {
        HourlyReport.reset();
        long w0 = 1_700_000_000_000L;
        HourlyReport.begin(null, w0, 0, 0, 100);
        HourlyReport.failSince = w0 + H;
        HourlyReport.failErr = "нет связи с GitHub";
        String t = HourlyReport.text(null, "9.9", w0 + 4 * H, 0, 0, 100);
        assertTrue(t, t.contains("Не удавалось отправить с "));
        assertTrue(t, t.contains("(нет связи с GitHub) — здесь весь период"));
        assertTrue(t, t.contains("Заряд держался на 100 %"));
        HourlyReport.reset();
        assertFalse(HourlyReport.text(null, "9.9", w0 + H, 0, 0, 90).contains("Не удавалось"));
    }

    /** Долгий простой целиком на 100 % помечается. */
    @Test public void lastNightAtFullMarked() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        EnergyStats e = new EnergyStats();
        e.event(0, 0, 1, 100, false, false, false);
        e.event(4 * H, 20 * M, 2, 100, true, false, false);
        assertEquals(100, e.lastNight[4], 1e-9);
        e.save(ctx);
        EnergyStats l = EnergyStats.load(ctx);
        assertEquals(100, l.lastNight[4], 1e-6);
        Section sec = new Section();
        new EnergyPage().build(ctx, sec);
        assertTrue(sec.toText(), sec.toText().contains("весь простой на 100 % — расход по процентам не виден"));
        EnergyStats.reset(ctx);
    }

    private static void prefsClear(Context ctx) {
        ctx.getSharedPreferences(HourlyReport.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
