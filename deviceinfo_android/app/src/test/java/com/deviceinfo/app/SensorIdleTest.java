package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Датчики слушаются только пока вкладка «Датчики» на экране; музыка не портит оценку простоя. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SensorIdleTest {

    @Test public void sensorsNotRegisteredWhenNotAllowed() {
        Context ctx = RuntimeEnvironment.getApplication();
        android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        org.robolectric.Shadows.shadowOf(sm).addSensor(
                org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER));
        SensorLive.stop();
        SensorLive.allowed = false;
        Section sec = new Section();
        new SensorsPage().build(ctx, sec);
        assertFalse(SensorLive.active());
        assertTrue(sec.toText(), sec.toText().contains("только пока эта вкладка открыта"));

        SensorLive.allowed = true;
        new SensorsPage().build(ctx, new Section());
        assertTrue(SensorLive.active());

        SensorLive.allowed = false;                 // ушли с вкладки — следующий build выключает
        new SensorsPage().build(ctx, new Section());
        assertFalse(SensorLive.active());
    }

    @Test public void pauseDisallowsSensors() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse(SensorLive.allowed);            // по умолчанию открыта не вкладка «Датчики»
        c.pause();
        assertFalse(SensorLive.allowed);
        c.stop().destroy();
    }

    @Test public void softerSleepGrades() {
        assertEquals(1, EnergyStats.sleepGrade(0.9));
        assertEquals(3, EnergyStats.sleepGrade(0.49));
        assertEquals(4, EnergyStats.sleepGrade(0.2));
    }

    @Test public void energyPageShowsMusicSeparately() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        ctx.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", 60).putExtra("scale", 100)
                .putExtra("status", 3).putExtra("plugged", 0).putExtra("voltage", 3900));
        EnergyStats e = new EnergyStats();
        long H = 3600_000L;
        e.event(0, 0, 1, 90, false, false, false, true);           // музыка, экран выключен
        e.event(2 * H, H, 2, 84, true, false, false, false);
        e.event(3 * H, 2 * H, 3, 80, false, false, false, false);  // простой без музыки
        e.event(6 * H, 2 * H + 18 * 60_000L, 4, 78, true, false, false, false);
        e.save(ctx);
        Section sec = new Section();
        new EnergyPage().build(ctx, sec);
        String t = sec.toText();
        assertTrue(t, t.contains("Экран выключен, играла музыка"));
        assertTrue(t, t.contains("Глубокий сон в простое"));
        assertTrue(t, t.contains("90 %"));                         // 18 мин бодрствования за 3 ч
        assertTrue(t, t.contains("Отлично"));
        EnergyStats.reset(ctx);
    }
}
