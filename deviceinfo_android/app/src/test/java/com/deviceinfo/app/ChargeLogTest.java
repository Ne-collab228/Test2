package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeLogTest {
    private static final long MIN = 60_000L;

    @Before public void setUp() { ChargeLog.reset(); }

    @Test public void shortUnplugContinuesSession() {
        long t = 1_000_000L;
        ChargeLog.add(t, 8, 5.0, 3800, true);
        ChargeLog.add(t + MIN, 30, 5.0, 3800, true);
        ChargeLog.add(t + 2 * MIN, 60, 5.0, 3800, true);
        ChargeLog.add(t + 3 * MIN, 96, 5.0, 3800, true);
        ChargeLog.add(t + 3 * MIN + 10_000, 96, 5, 3800, false);
        ChargeLog.add(t + 3 * MIN + 30_000, 97, 5.0, 500, true);
        ChargeLog.add(t + 4 * MIN, 100, 5.0, 300, true);
        assertTrue(ChargeLog.charging);
        assertEquals(t, ChargeLog.startMs);
        double[] s = ChargeLog.summary();
        assertNotNull(s);
        assertEquals(8, s[2], 0);
        assertEquals(100, s[3], 0);
        assertTrue("peak " + s[0], s[0] >= 18.9);
    }

    @Test public void longUnplugStartsNewSession() {
        long t = 1_000_000L;
        ChargeLog.add(t, 8, 5.0, 3800, true);
        ChargeLog.add(t + MIN, 96, 5.0, 3800, true);
        ChargeLog.add(t + 2 * MIN, 96, 5.0, 3800, false);
        long t2 = t + MIN + 11 * MIN;
        ChargeLog.add(t2, 96, 5.0, 1000, true);
        ChargeLog.add(t2 + MIN, 97, 5.0, 1000, true);
        assertEquals(t2, ChargeLog.startMs);
        double[] s = ChargeLog.summary();
        assertEquals(2, s[5], 0);
        assertEquals(96, s[2], 0);
    }

    @Test public void levelDropStartsNewSession() {
        long t = 1_000_000L;
        ChargeLog.add(t, 90, 5.0, 2000, true);
        ChargeLog.add(t + MIN, 96, 5.0, 2000, true);
        ChargeLog.add(t + MIN + 5000, 96, 5, 0, false);
        ChargeLog.add(t + 2 * MIN, 50, 5.0, 2000, true);
        assertEquals(t + 2 * MIN, ChargeLog.startMs);
        assertEquals(1, ChargeLog.points.size());
    }

    @Test public void nonPositiveNeighboursGiveNoNegativePeak() {
        long t = 1_000_000L;
        double[] mas = {-200, 1500, -160, 1600, 0};
        for (int i = 0; i < mas.length; i++) ChargeLog.add(t + i * MIN, 50 + i, 5.0, mas[i], true);
        double[] s = ChargeLog.summary();
        assertNotNull(s);
        assertTrue("peak " + s[0], s[0] > 0);
    }

    @Test public void allNonPositiveReturnsNull() {
        long t = 1_000_000L;
        ChargeLog.add(t, 50, 5.0, -500, true);
        ChargeLog.add(t + MIN, 51, 5.0, -300, true);
        assertNull(ChargeLog.summary());
    }

    @Test public void normalPeakUsesMedian() {
        long t = 1_000_000L;
        double[] mas = {1000, 2000, 2000, 6000, 2000};
        for (int i = 0; i < mas.length; i++) ChargeLog.add(t + i * MIN, 10 + i, 5.0, mas[i], true);
        double[] s = ChargeLog.summary();
        assertEquals(10.0, s[0], 1e-9);
        assertEquals(5, s[5], 0);
    }
}
