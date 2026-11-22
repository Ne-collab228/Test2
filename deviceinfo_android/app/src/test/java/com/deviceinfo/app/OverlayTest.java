package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class OverlayTest {
    @Test public void formatting() {
        assertEquals("−650 мА", OverlayService.fmtMa(-650.4));
        assertEquals("+1200 мА", OverlayService.fmtMa(1200));
        assertEquals("— мА", OverlayService.fmtMa(Double.NaN));
        assertEquals("2.6 Вт", OverlayService.fmtWatts(4.0, -650));
        assertEquals("— Вт", OverlayService.fmtWatts(Double.NaN, -650));
        assertEquals("36.5 °C", OverlayService.fmtTemp(36.5));
        assertEquals("1.70 ГГц", OverlayService.fmtGhz(1_700_000));
    }

    @Test public void freqAndModes() {
        List<long[]> c = new ArrayList<>();
        c.add(new long[]{0, 5, 1700000, 0});
        c.add(new long[]{6, 7, 2000000, 0});
        String f = OverlayService.freqText(c, new long[]{1700000, 2000000});
        assertEquals("0–5: 1.70 ГГц · 6–7: 2.00 ГГц", f);
        assertEquals("78% · −650 мА · 36.5 °C", OverlayService.format(78, -650, 4.0, 36.5, f, true));
        String full = OverlayService.format(78, -650, 4.0, 36.5, f, false);
        assertEquals("78% · −650 мА\n2.6 Вт · 36.5 °C\n" + f, full);
        assertEquals("—% · — мА · — °C", OverlayService.format(-1, Double.NaN, Double.NaN, Double.NaN, null, true));
    }

    @Test public void sensorThrottle() {
        assertTrue(SensorLive.due(-1, 100, 200));
        assertFalse(SensorLive.due(100, 250, 200));
        assertTrue(SensorLive.due(100, 300, 200));
        assertEquals(5.0, SensorLive.mag(new float[]{3, 4, 0}), 1e-9);
    }
}
