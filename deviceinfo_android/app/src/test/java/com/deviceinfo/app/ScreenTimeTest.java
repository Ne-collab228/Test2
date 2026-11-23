package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ScreenTimeTest {
    static final long M = 60_000L, H = 60 * M, D = 24 * H;
    /** 2026-11-20 00:00 UTC */
    static final long DAY0 = 1795132800000L;
    TimeZone tz;

    @Before public void setUp() {
        tz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ScreenTime.reset(RuntimeEnvironment.getApplication());
        IdleNet.reset(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() { TimeZone.setDefault(tz); }

    @Test public void dayKeyAndMidnight() {
        assertEquals(20261120, ScreenTime.dayKey(DAY0 + 10 * H));
        assertEquals(DAY0 + D, ScreenTime.nextMidnight(DAY0 + 10 * H));
        assertEquals(DAY0 + D, ScreenTime.nextMidnight(DAY0));
    }

    @Test public void simpleSpanAndCount() {
        long el = 1000 * M;
        ScreenTime.event(el, DAY0 + 10 * H, true);
        ScreenTime.event(el + 10 * M, DAY0 + 10 * H + 10 * M, true); // повтор «вкл» — не новое включение
        ScreenTime.event(el + 90 * M, DAY0 + 11 * H + 30 * M, false);
        ScreenTime.event(el + 95 * M, DAY0 + 11 * H + 35 * M, false);
        long[] d = ScreenTime.days.get(20261120);
        assertEquals(90 * M, d[0]);
        assertEquals(1, d[1]);
        // идущий отрезок виден в снимке, но не в сохранённых днях
        ScreenTime.event(el + 2 * H, DAY0 + 12 * H, true);
        assertEquals(90 * M + 30 * M, ScreenTime.msOn(el + 2 * H + 30 * M, DAY0 + 12 * H + 30 * M, DAY0 + 12 * H));
        assertEquals(90 * M, ScreenTime.days.get(20261120)[0]);
        assertEquals(2, ScreenTime.days.get(20261120)[1]);
    }

    @Test public void spanAcrossMidnightIsSplit() {
        long el = 5000 * M;
        ScreenTime.event(el, DAY0 + 23 * H, true);
        ScreenTime.event(el + 2 * H, DAY0 + D + H, false);
        assertEquals(H, ScreenTime.days.get(20261120)[0]);
        assertEquals(H, ScreenTime.days.get(20261121)[0]);
        assertEquals(1, ScreenTime.days.get(20261120)[1]);
        assertEquals(0, ScreenTime.days.get(20261121)[1]);
    }

    @Test public void durationByElapsedNotWallClock() {
        long el = 100 * M;
        ScreenTime.event(el, DAY0 + 10 * H, true);
        // часы перевели на час вперёд — экран был включён всего 10 мин
        ScreenTime.event(el + 10 * M, DAY0 + 11 * H + 10 * M, false);
        assertEquals(10 * M, ScreenTime.days.get(20261120)[0]);
    }

    @Test public void keepsEightDays() {
        long el = 100 * M;
        for (int i = 0; i < 12; i++) {
            ScreenTime.event(el, DAY0 + i * D + 10 * H, true);
            el += H;
            ScreenTime.event(el, DAY0 + i * D + 11 * H, false);
            el += H;
        }
        assertEquals(ScreenTime.KEEP_DAYS, ScreenTime.days.size());
        assertEquals(20261124, (int) ScreenTime.days.firstKey());
        assertEquals(H, ScreenTime.avgPast(el, DAY0 + 11 * D + 12 * H));
        assertEquals(8, ScreenTime.rows(el, DAY0 + 11 * D + 12 * H).size());
        assertEquals("сегодня", ScreenTime.rows(el, DAY0 + 11 * D + 12 * H).get(0)[0]);
        assertEquals("вчера", ScreenTime.rows(el, DAY0 + 11 * D + 12 * H).get(1)[0]);
    }

    @Test public void summaryText() {
        long el = 100 * M;
        assertNull(ScreenTime.summary(el, DAY0 + 12 * H));
        ScreenTime.event(el, DAY0 - 3 * H, true);
        ScreenTime.event(el + 2 * H + 5 * M, DAY0 - 55 * M, false);
        ScreenTime.event(el + 3 * H, DAY0 + 2 * H, true);
        ScreenTime.event(el + 3 * H + 40 * M, DAY0 + 2 * H + 40 * M, false);
        assertEquals("сегодня 40 мин (включали 1 раз) · вчера 2 ч 5 мин", ScreenTime.summary(el + 4 * H, DAY0 + 3 * H));
    }

    @Test public void saveLoadAndStaleOpenSpan() {
        Context c = RuntimeEnvironment.getApplication();
        long el = 1000 * M;
        ScreenTime.event(el, DAY0 + 10 * H, true);
        ScreenTime.event(el + 30 * M, DAY0 + 10 * H + 30 * M, false);
        ScreenTime.event(el + H, DAY0 + 11 * H, true);
        ScreenTime.save(c, el + H + 5 * M, DAY0 + 11 * H + 5 * M);
        // перезапуск вскоре — идущий отрезок продолжается
        ScreenTime.days.clear(); ScreenTime.onEl = -1;
        ScreenTime.load(c, el + H + 7 * M);
        assertEquals(30 * M, ScreenTime.days.get(20261120)[0]);
        assertEquals(el + H, ScreenTime.onEl);
        // служба не работала 2 ч — отрезок закрыт на последнем сохранении
        ScreenTime.load(c, el + 3 * H);
        assertEquals(-1, ScreenTime.onEl);
        assertEquals(35 * M, ScreenTime.days.get(20261120)[0]);
        // перезагрузка (elapsed меньше сохранённого) — отрезок отброшен
        ScreenTime.load(c, 10 * M);
        assertEquals(-1, ScreenTime.onEl);
        assertEquals(30 * M, ScreenTime.days.get(20261120)[0]);
    }

    @Test public void idleNetCountsOnlyScreenOff() {
        long el = 1000 * M;
        IdleNet.update(el, true, IdleNet.WIFI, false);
        IdleNet.update(el + H, false, null, null);            // час экрана — не считается
        IdleNet.update(el + 3 * H, null, IdleNet.MOBILE, true); // 2 ч Wi-Fi
        IdleNet.update(el + 4 * H, null, null, false);          // 1 ч мобильной со слабым сигналом
        long[] a = IdleNet.snapshot(false, el + 5 * H);          // ещё 1 ч мобильной (идущий)
        assertEquals(2 * H, a[IdleNet.WIFI]);
        assertEquals(2 * H, a[IdleNet.MOBILE]);
        assertEquals(H, a[4]);
        assertEquals("Wi-Fi 2 ч 0 мин · мобильная 2 ч 0 мин (слабый сигнал 1 ч 0 мин)", IdleNet.text(a));
        assertNull(IdleNet.hint(a));
        assertTrue(IdleNet.hint(new long[]{0, H, 2 * H, 0, 2 * H}).contains("слабым"));
        assertTrue(IdleNet.hint(new long[]{0, H, 3 * H, 0, 0}).contains("мобильной"));
        IdleNet.resetPeriod(el + 5 * H);
        assertEquals(0, IdleNet.snapshot(true, el + 5 * H)[IdleNet.MOBILE]);
        assertEquals(2 * H, IdleNet.snapshot(false, el + 5 * H)[IdleNet.MOBILE]);
        IdleNet.save(RuntimeEnvironment.getApplication());
        IdleNet.resetAll();
        IdleNet.load(RuntimeEnvironment.getApplication());
        assertEquals(2 * H, IdleNet.total[IdleNet.MOBILE]);
        assertEquals(0, IdleNet.period[IdleNet.MOBILE]);
        assertNull(IdleNet.text(new long[5]));
        long[] wifi = {0, 5 * H, 0, 0, 0};
        assertNull(IdleNet.hint(wifi));
    }

    @Test public void hourlyOverdue() {
        assertFalse(HourlyReport.overdue(0, DAY0));
        assertFalse(HourlyReport.overdue(DAY0, DAY0 + 60 * M));
        assertTrue(HourlyReport.overdue(DAY0, DAY0 + 66 * M));
        assertTrue(HourlyReport.overdue(DAY0, DAY0 - 2 * D)); // часы ушли назад
    }

    @Test public void extrasHaveSections() {
        Context c = RuntimeEnvironment.getApplication();
        long el = 1000 * M;
        ScreenTime.event(el, DAY0 + 10 * H, true);
        ScreenTime.event(el + H, DAY0 + 11 * H, false);
        String x = HourlyReport.extras(c, DAY0 + 10 * H, DAY0 + 11 * H + 30 * M, el + 90 * M, true);
        assertTrue(x, x.contains("[Экран за день]\nсегодня 1 ч 0 мин (включали 1 раз)"));
        assertTrue(x, x.contains("[Почему не спал / кто будил]"));
    }
}
