package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class IdleHistoryTest {
    static final long M = 60_000L, H = 60 * M;
    /** 2026-11-20 00:00 UTC */
    static final long DAY0 = 1795132800000L;
    TimeZone tz;

    @Before public void setUp() {
        tz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        IdleHistory.reset(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() { TimeZone.setDefault(tz); }

    @Test public void dropIsSpreadOverIdleTime() {
        long el = 1000 * M, w = DAY0 + 10 * H;
        IdleHistory.update(el, w, 90, false, false, IdleNet.WIFI);                       // 10:00 экран выкл
        IdleHistory.update(el + 2 * H, w + 2 * H, 89, false, false, IdleNet.WIFI);       // 12:00 −1 %
        double[] h10 = IdleHistory.hours.get(DAY0 + 10 * H), h11 = IdleHistory.hours.get(DAY0 + 11 * H);
        assertEquals(H, h10[IdleHistory.OFF], 1);
        assertEquals(H, h10[IdleHistory.WIFI], 1);
        assertEquals(0.5, h10[IdleHistory.OFF_PCT], 1e-9);
        assertEquals(0.5, h11[IdleHistory.OFF_PCT], 1e-9);
    }

    @Test public void screenAndChargeNotIdle() {
        long el = 1000 * M, w = DAY0 + 10 * H;
        IdleHistory.update(el, w, 80, true, false, IdleNet.WIFI);
        IdleHistory.update(el + 30 * M, w + 30 * M, 75, false, false, IdleNet.WIFI);     // экран вкл 30 мин, −5 % не в простое
        IdleHistory.update(el + 40 * M, w + 40 * M, 75, false, true, IdleNet.WIFI);      // 10 мин простоя, зарядка
        IdleHistory.update(el + 60 * M, w + 60 * M, 78, false, false, IdleNet.WIFI);
        double[] h = IdleHistory.hours.get(DAY0 + 10 * H);
        assertEquals(30 * M, h[IdleHistory.SCREEN], 1);
        assertEquals(10 * M, h[IdleHistory.OFF], 1);
        assertEquals(20 * M, h[IdleHistory.CHARGE], 1);
        assertEquals(0, h[IdleHistory.OFF_PCT], 1e-9);
    }

    @Test public void dropFrom100Ignored() {
        long el = 1000 * M, w = DAY0 + 1 * H;
        IdleHistory.update(el, w, 100, false, false, IdleNet.WIFI);
        IdleHistory.update(el + 3 * H, w + 3 * H, 99, false, false, IdleNet.WIFI);
        double[] h = IdleHistory.hours.get(DAY0 + 1 * H);
        assertEquals(H, h[IdleHistory.FULL], 1);
        assertEquals(0, h[IdleHistory.OFF_PCT], 1e-9);
    }

    @Test public void barsKindsAndRates() {
        long el = 1000 * M, w = DAY0 + 8 * H;
        IdleHistory.update(el, w, 90, false, false, IdleNet.WIFI);
        IdleHistory.update(el + 3 * H, w + 3 * H, 88, false, false, IdleNet.MOBILE);    // 8–11 Wi-Fi, −2 %
        IdleHistory.update(el + 5 * H, w + 5 * H, 83, false, false, IdleNet.MOBILE);    // 11–13 мобильная, −5 %
        IdleHistory.update(el + 6 * H, w + 6 * H, 83, true, false, IdleNet.MOBILE);     // 13–14 мобильная, 0 %
        IdleHistory.update(el + 7 * H, w + 7 * H, 80, false, true, IdleNet.MOBILE);     // 14–15 экран
        IdleHistory.Bars b = IdleHistory.bars(el + 8 * H, w + 8 * H, 10);              // 15–16 зарядка (идёт)
        assertEquals("7", b.labels[0]);
        assertEquals("16", b.labels[9]);
        assertEquals(HoursView.NONE, b.kinds[0]);
        assertEquals(HoursView.WIFI, b.kinds[1]);
        assertEquals(2 / 3.0, b.values[1], 1e-9);
        assertEquals(HoursView.MOBILE, b.kinds[4]);
        assertEquals(2.5, b.values[4], 1e-9);
        assertEquals(0, b.values[6], 1e-9);
        assertEquals(HoursView.SCREEN, b.kinds[7]);
        assertEquals(HoursView.CHARGE, b.kinds[8]);
        assertEquals(2 / 3.0, b.wifiRate, 1e-9);
        assertEquals(5 / 3.0, b.mobileRate, 1e-9);
    }

    @Test public void netChangeSplitsTime() {
        long el = 1000 * M, w = DAY0 + 10 * H;
        IdleHistory.update(el, w, 70, false, false, IdleNet.WIFI);
        IdleHistory.net(el + 20 * M, w + 20 * M, IdleNet.MOBILE);
        IdleHistory.update(el + 60 * M, w + 60 * M, 69, false, false, IdleNet.MOBILE);
        double[] h = IdleHistory.hours.get(DAY0 + 10 * H);
        assertEquals(20 * M, h[IdleHistory.WIFI], 1);
        assertEquals(40 * M, h[IdleHistory.MOBILE], 1);
        assertEquals(1, h[IdleHistory.OFF_PCT], 1e-9);       // уровень держался весь час
    }

    @Test public void saveLoadAndTrim() {
        long el = 1000 * M, w = DAY0;
        IdleHistory.update(el, w, 90, false, false, IdleNet.WIFI);
        for (int i = 1; i <= 60; i++) IdleHistory.update(el + i * H, w + i * H, 90, false, false, IdleNet.WIFI);
        assertEquals(IdleHistory.KEEP_HOURS - 1, IdleHistory.hours.size()); // текущий час ещё пуст
        IdleHistory.save(RuntimeEnvironment.getApplication());
        IdleHistory.hours.clear();
        IdleHistory.load(RuntimeEnvironment.getApplication());
        assertEquals(IdleHistory.KEEP_HOURS - 1, IdleHistory.hours.size()); // текущий час ещё пуст
        assertEquals(-1, IdleHistory.lastEl);
    }

    @Test public void wakeChartAndText() {
        List<WakeCauses.App> apps = new ArrayList<>();
        WakeCauses.App max = new WakeCauses.App("ru.max"); max.label = "MAX"; max.notifications = 30; max.bgBytes = 1_600_000;
        WakeCauses.App fit = new WakeCauses.App("fit"); fit.label = "Mi Fitness"; fit.fgsStarts = 14;
        WakeCauses.App tg = new WakeCauses.App("tg"); tg.label = "Telegram"; tg.notifications = 5; tg.bgBytes = 11_700_000;
        WakeCauses.App tanks = new WakeCauses.App("tanks"); tanks.label = "Tanks"; tanks.bgBytes = 2_300_000;
        WakeCauses.App tiny = new WakeCauses.App("tiny"); tiny.label = "Tiny"; tiny.bgBytes = 50_000;
        apps.addAll(Arrays.asList(tiny, tanks, tg, fit, max));
        WakeCauses.Chart c = WakeCauses.chart(apps, 8);
        assertEquals(49, c.total);
        assertEquals("Будили 49 раз · чаще всех MAX (30)", c.headline());
        assertEquals(4, c.rows.size());
        assertArrayEquals(new String[]{"MAX", "30", "1.5 МБ"}, c.rows.get(0));
        assertArrayEquals(new String[]{"Tanks", "", "2.2 МБ"}, c.rows.get(3));
        assertEquals(0, c.values[3], 0);
        assertEquals("Будили 49 раз: MAX 30, Mi Fitness 14, Telegram 5; в фоне скачали: MAX 1.5 МБ, Telegram 11.2 МБ, Tanks 2.2 МБ", c.line());
        assertEquals("Не будили ни разу", WakeCauses.chart(new ArrayList<>(), 8).headline());
        assertEquals("Будили 2 раза · чаще всех A (2)", one(2).headline());
        assertEquals("Будили 1 раз · чаще всех A (1)", one(1).headline());
    }

    private static WakeCauses.Chart one(int n) {
        WakeCauses.App a = new WakeCauses.App("a"); a.label = "A"; a.notifications = n;
        return WakeCauses.chart(new ArrayList<>(Arrays.asList(a)), 8);
    }

    @Test public void sectionText() {
        Section s = new Section();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"MAX", "30", "1.5 МБ"});
        rows.add(new String[]{"Tanks", "", "2.2 МБ"});
        s.hbars("Кто будил", "Будили 30 раз", rows, new double[]{30, 0}, Section.NORMAL);
        s.hours("По часам", "Wi-Fi ≈ 0.5 %/ч", new String[]{"1", "2", "3"}, new double[]{0.5, Double.NaN, Double.NaN},
                new int[]{HoursView.WIFI, HoursView.CHARGE, HoursView.NONE});
        String t = s.toText();
        assertTrue(t, t.contains("Кто будил: Будили 30 раз\n  MAX — 30 · 1.5 МБ\n  Tanks —  · 2.2 МБ\n"));
        assertTrue(t, t.contains("По часам: Wi-Fi ≈ 0.5 %/ч\n  1 0.5 %/ч Wi-Fi\n  2 на зарядке\n"));
        assertEquals("Ядро системы", WakeCauses.systemName(0, null));
        assertEquals("Сетевые службы системы", WakeCauses.systemName(-253, null));
        assertEquals("Удалённое приложение", WakeCauses.systemName(10243, null));
    }
}
