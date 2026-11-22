package com.deviceinfo.app;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RemainingTest {
    @Test public void robustIgnoresSpikesAndGlitches() {
        Remaining.reset();
        List<Double> p = new ArrayList<>();
        for (int i = 0; i < 40; i++) p.add(-500.0);
        p.add(-3000.0); p.add(-2800.0);          // скачки (запуск приложения)
        p.add(-135000.0);                          // сбой датчика
        p.add(+1500.0);                            // зарядка
        p.add(-100.0);
        assertEquals(500, Remaining.robustDrainMa(p), 1e-9);
        assertTrue(Remaining.settling());          // меньше 10 мин данных
    }

    /** Первая оценка — уже по 3 корзинам (30 с); по 2 — ещё нет. */
    @Test public void quickFirstEstimate() {
        List<Double> p = new ArrayList<>();
        p.add(-600.0); p.add(-650.0);
        assertTrue(Double.isNaN(Remaining.robustDrainMa(p)));
        p.add(-700.0);
        assertEquals(650, Remaining.robustDrainMa(p), 1e-9);
        List<Double> full = new ArrayList<>();
        for (int i = 0; i < 60; i++) full.add(-500.0);
        Remaining.robustDrainMa(full);
        assertFalse(Remaining.settling());
    }

    @Test public void smoothAndStable() {
        Remaining.reset();
        List<Double> full = new ArrayList<>();
        for (int i = 0; i < 60; i++) full.add(-500.0);
        Remaining.robustDrainMa(full);             // полное окно — медленное сглаживание
        assertEquals(500, Remaining.smooth(500, 0), 1e-9);
        double v = Remaining.smooth(2000, 1000);   // одна секунда скачка почти не влияет
        assertTrue(v < 510);
        assertEquals(4.0, Remaining.stable(4.0), 1e-9);
        assertEquals(4.0, Remaining.stable(3.7), 1e-9);  // < 10 % — число не меняется
        assertEquals(3.5, Remaining.stable(3.5), 1e-9);
        Remaining.reset();
    }

    @Test public void format() {
        assertEquals("≈ 4 ч 10 мин", Remaining.fmt(4.15));
        assertEquals("≈ 25 мин", Remaining.fmt(0.41));
        assertEquals("≈ 5 мин", Remaining.fmt(0.01));
        assertEquals("≈ 12 ч 30 мин", Remaining.fmt(12.4));
        assertNull(Remaining.fmt(Double.NaN));
    }
}
