package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EnergyTest {
    static final long H = 3600_000L;

    /** Ночь: 8 ч с выключенным экраном, −6 %, процессор бодрствовал 30 мин; днём 1 ч экрана, −12 %. */
    @Test public void screenOffAndOnRates() {
        EnergyStats e = new EnergyStats();
        long t = 1_000_000, up = 500_000;
        e.event(t, up, 1, 90, false, false, false);           // экран выключен
        e.event(t + 4 * H, up + 15 * 60_000, 2, 87, false, false, false); // батарея изменилась, отрезок тот же
        e.event(t + 8 * H, up + 30 * 60_000, 3, 84, true, false, false);  // экран включён
        e.event(t + 9 * H, up + 30 * 60_000 + H, 4, 72, false, false, false);
        assertEquals(8 * H, e.off.ms);
        assertEquals(6, e.off.pct, 1e-9);
        assertEquals(0.75, e.offRate(), 1e-9);
        assertEquals(1, EnergyStats.offGrade(e.offRate()));
        assertEquals(1 - 30 / 480.0, 1 - e.off.awakeShare(), 1e-9);
        assertEquals(1, EnergyStats.sleepGrade(1 - e.off.awakeShare()));
        assertNotNull(e.lastNight);
        assertEquals(12, e.onRate(), 1e-9);
        assertEquals(8.33, EnergyStats.hoursFull(e.onRate()), 0.01);
        assertEquals(516, EnergyStats.ma(12, 4300), 1e-9);
    }

    /** Зарядка закрывает отрезок; простой во время точного замера не учитывается; мало данных — NaN. */
    @Test public void chargingAndBusyExcluded() {
        EnergyStats e = new EnergyStats();
        e.event(0, 0, 1, 80, false, false, true);            // идёт точный замер
        e.event(3 * H, 3 * H, 2, 70, true, false, true);     // отрезок «испорчен» — не в итогах
        assertEquals(0, e.off.ms);
        e.event(3 * H + 60_000, 3 * H + 60_000, 3, 70, true, true, false); // зарядка
        assertNull(e.open);
        assertEquals(60_000, e.on.ms);
        assertTrue(Double.isNaN(e.onRate()));
        assertTrue(Double.isNaN(e.offRate()));
        assertEquals(4, EnergyStats.offGrade(5));
        assertEquals(0, EnergyStats.offGrade(Double.NaN));
    }

    @Test public void persistence() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        EnergyStats e = new EnergyStats();
        e.event(0, 0, 10, 90, false, false, false);
        e.event(3 * H, 60_000, 20, 88, true, false, false);
        e.save(ctx);
        EnergyStats l = EnergyStats.load(ctx);
        assertEquals(3 * H, l.off.ms);
        assertEquals(2, l.off.pct, 1e-6);
        assertNotNull(l.open);
        assertTrue(l.open.screenOn);
        assertNotNull(l.lastNight);
    }

    @Test public void chargeLog() {
        ChargeLog.reset();
        assertFalse(ChargeLog.add(0, 50, 4.0, -500, false));
        assertTrue(ChargeLog.add(0, 30, 4.0, 5000, true));       // 20 Вт
        assertFalse(ChargeLog.add(5_000, 30, 4.0, 5000, true));  // слишком часто
        assertTrue(ChargeLog.add(30_000, 31, 4.1, 7000, true));  // 28.7 Вт
        assertTrue(ChargeLog.add(60_000, 32, 4.1, 6800, true));
        assertTrue(ChargeLog.add(90_000, 33, 4.2, 1000, true));  // выброс вниз
        double[] s = ChargeLog.summary();
        assertNotNull(s);
        assertEquals(27.88, s[0], 0.01);   // пик по медиане соседей
        assertEquals(30, s[2], 1e-9);
        assertEquals(33, s[3], 1e-9);
        assertEquals(90_000, s[4], 1e-9);
        assertTrue(ChargeLog.verdict(s[0]).startsWith("быстрая"));
        assertTrue(ChargeLog.verdict(9).startsWith("обычная"));
        ChargeLog.add(100_000, 33, 4.2, -300, false);             // отключили — сессия сохранена
        assertEquals(4, ChargeLog.points.size());
        ChargeLog.add(200_000, 40, 4.0, 3000, true);              // новая зарядка — новая сессия
        assertEquals(1, ChargeLog.points.size());
        ChargeLog.reset();
    }

    @Test public void energyPageAndChargeText() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        ctx.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", 60).putExtra("scale", 100)
                .putExtra("status", 3).putExtra("plugged", 0).putExtra("voltage", 3900));
        Section sec = new Section();
        new EnergyPage().build(ctx, sec);
        String t = sec.toText();
        assertTrue(t, t.contains("[кнопка] Включить слежение"));
        assertTrue(t, t.contains("Экран выключен (простой)"));
        assertTrue(BatteryPage.chargeText(95, 4300, 5300).contains("4085"));
        assertTrue(BatteryPage.chargeText(95, Double.NaN, 5300).contains("5035"));
        assertEquals(12, EnergyPage.processNm("MT6769V/CB mt6768"));
    }
}
