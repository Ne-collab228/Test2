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

    /** Быстрая зарядка: снижение тока с 80 %; медленная: с ~92 % и слабее. */
    @Test public void chargeModel() {
        // быстрая: 5000 мА·ч, 30 %, 5000 мА (1C): 30→80 % = 0.5 ч; 80→100 % = 1000 / (5000·0.45)
        assertEquals(80, Remaining.taperLevel(5000, 5000), 1e-9);
        assertEquals(0.5 + 1000 / (5000 * 0.45), Remaining.hoursToFullFor(5000, 30, 5000), 1e-9);
        // медленная (как повербанк): 4692 мА·ч, 25 %, 844 мА → снижение с 92 %, итого ≈ 4.4 ч
        assertEquals(92, Remaining.taperLevel(4692, 844), 1e-9);
        double h = Remaining.hoursToFullFor(4692, 25, 844);
        assertEquals(4692 * 0.67 / 844 + 4692 * 0.08 / (844 * 0.65), h, 1e-9);
        assertTrue(h > 4.2 && h < 4.6);
        // уже выше уровня снижения: ток уже сниженный
        assertEquals(250 / (1000 * 0.7), Remaining.hoursToFullFor(5000, 95, 1000), 1e-9);
        assertEquals(0, Remaining.hoursToFullFor(5000, 100, 1000), 0);
        assertTrue(Double.isNaN(Remaining.hoursToFullFor(5000, 50, Double.NaN)));
        assertEquals("заряжен полностью", Remaining.chargeText(0));
    }

    /** Расход до зарядки запоминается — по нему оценивается, сколько телефон тратит во время зарядки. */
    @Test public void lastDrainKept() {
        Remaining.lastDrainMa = Double.NaN;
        Remaining.DRAIN.reset();
        Remaining.smooth(600, 1000);
        Remaining.reset();
        assertEquals(600, Remaining.lastDrainMa, 1e-9);
        Remaining.reset();                           // повторный сброс (зарядка идёт) не затирает
        assertEquals(600, Remaining.lastDrainMa, 1e-9);
        assertEquals(40, BatteryPage.phoneDrainMa(null, false, 0), 0);
        assertEquals(600, BatteryPage.phoneDrainMa(null, true, 0), 0);
    }

    /** Оценка зарядки берёт только точки зарядки (+), разрядные (−) в окне не мешают. */
    @Test public void chargeEstimateFromRing() {
        BatteryMeter.reset();
        Remaining.resetCharge();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 6; i++) BatteryMeter.recordCurrent(t + i * 10_000L, -600);   // разряд до зарядки
        assertTrue(Double.isNaN(Remaining.hoursToFull(5000, 30, t + 60_000L, 60_000L)));
        for (int i = 6; i < 10; i++) BatteryMeter.recordCurrent(t + i * 10_000L, 2500);  // 40 с зарядки
        double h = Remaining.hoursToFull(5000, 30, t + 100_000L, 100_000L);
        assertEquals(Remaining.hoursToFullFor(5000, 30, 2500), h, 1e-6);
        String txt = Remaining.chargeText(h);
        assertTrue(txt, txt.startsWith("≈ 1 ч 40 мин (ток зарядки ≈ 2500 мА, уточняется)"));
        // оценка разряда отдельно и не затронута
        assertTrue(Double.isNaN(Remaining.drainMa()) || Remaining.drainMa() > 0);
    }
}
