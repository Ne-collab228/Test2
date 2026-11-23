package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EnergyAudioTest {
    static final long M = 60_000L, H = 60 * M;
    static final long T = 1_000_000, UP = 500_000;

    @Test public void audioSegmentGoesToOffAudioOnly() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false, true);
        e.event(T + 4 * H, UP + 30 * M, 2, 86, true, false, false, true); // экран включён
        assertEquals(0, e.off.ms);
        assertEquals(0, e.off.segments);
        assertEquals(4 * H, e.offAudio.ms);
        assertEquals(4, e.offAudio.pct, 1e-9);
        assertEquals(30 * M, e.offAudio.awakeMs);
        assertEquals(1, e.offAudio.segments);
        assertNull(e.lastNight);
        assertEquals(0, e.longOffMs);
    }

    @Test public void shortAudioDoesNotMakeAudioSegment() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false, false);
        assertFalse(e.open.lastAudio);
        e.event(T + 20 * M, UP + M, 2, 90, false, false, false, true);
        assertTrue(e.open.lastAudio);
        e.event(T + 40 * M, UP + 2 * M, 3, 90, false, false, false, false);
        assertEquals(20 * M, e.open.audioMs(T + H));
        e.event(T + H, UP + 3 * M, 4, 90, true, false, false, false);
        assertEquals(H, e.off.ms);            // звук 20 из 60 мин — обычный простой
        assertEquals(0, e.offAudio.ms);
        assertFalse(e.open.lastAudio);        // экранный отрезок звук игнорирует
    }

    @Test public void mostlyAudioIsAudioSegment() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false, false);
        e.event(T + 20 * M, UP + M, 2, 90, false, false, false, true);
        e.event(T + H, UP + 3 * M, 3, 90, true, false, false, false);
        assertEquals(0, e.off.ms);             // звук 40 из 60 мин
        assertEquals(H, e.offAudio.ms);
    }

    /** Ошибка из отчёта: в момент выключения экрана ещё играл звук, потом весь простой — тишина. */
    @Test public void audioOnlyAtScreenOffIsIgnored() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, true, false, false, true);
        e.event(T + M, UP + M, 2, 89, false, false, false, true);           // экран выкл, звук ещё играет
        e.event(T + 2 * M, UP + M + 5_000, 3, 89, false, false, false, false); // звук закончился
        e.event(T + 3 * H, UP + 10 * M, 4, 87, false, false, false, false);
        assertEquals(0, e.openAudioMs(T + 3 * H));
        assertEquals(3 * H - M, e.openMs(false, T + 3 * H));
        e.event(T + 4 * H, UP + 12 * M, 5, 86, true, false, false, false);
        assertEquals(4 * H - M, e.off.ms);
        assertEquals(0, e.offAudio.ms);
        assertNotNull(e.lastNight);
    }

    @Test public void screenOnIgnoresAudio() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, true, false, false, true);
        e.event(T + H, UP + H, 2, 80, false, false, false, false);
        assertEquals(H, e.on.ms);
        assertEquals(0, e.offAudio.ms);
    }

    @Test public void longSegmentsOnly() {
        EnergyStats e = new EnergyStats();
        long t = T, up = UP;
        // короткий (5 мин) — не считается
        e.event(t, up, 1, 90, false, false, false); t += 5 * M; up += 4 * M;
        e.event(t, up, 2, 90, true, false, false); t += M; up += M;
        // длинный 20 мин, бодрствовал 2 мин
        e.event(t, up, 3, 90, false, false, false); t += 20 * M; up += 2 * M;
        e.event(t, up, 4, 90, true, false, false); t += M; up += M;
        // длинный со звуком — не считается
        e.event(t, up, 5, 90, false, false, false, true); t += 60 * M; up += 30 * M;
        e.event(t, up, 6, 89, true, false, false); t += M; up += M;
        // длинный с замером — не считается
        e.event(t, up, 7, 89, false, false, true); t += 60 * M; up += 30 * M;
        e.event(t, up, 8, 89, true, false, false); t += M; up += M;
        assertEquals(20 * M, e.longOffMs);
        assertEquals(2 * M, e.longOffAwakeMs);
        assertTrue(Double.isNaN(e.longSleepShare()));
        // ещё 10 мин ровно: 30 мин накоплено
        e.event(t, up, 9, 89, false, false, false); t += 10 * M; up += 5 * M;
        e.event(t, up, 10, 89, true, false, false);
        assertEquals(30 * M, e.longOffMs);
        assertEquals(7 * M, e.longOffAwakeMs);
        assertEquals(1 - 7 / 30.0, e.longSleepShare(), 1e-9);
    }

    @Test public void longSleepShareNaNBelow30Min() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false);
        e.event(T + 29 * M, UP, 2, 90, true, false, false);
        assertEquals(29 * M, e.longOffMs);
        assertTrue(Double.isNaN(e.longSleepShare()));
    }

    @Test public void openSegmentAccessors() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false, true);
        assertEquals(0, e.openMs(false, T + H));
        assertEquals(H, e.openAudioMs(T + H));
        assertEquals(0, e.openMs(true, T + H));
        assertEquals(0, e.withOpen(false, T + H, UP + M, 88).ms);
        // не-аудио отрезок
        EnergyStats f = new EnergyStats();
        f.event(T, UP, 1, 90, false, false, false);
        assertEquals(H, f.openMs(false, T + H));
        assertEquals(0, f.openAudioMs(T + H));
        assertEquals(H, f.withOpen(false, T + H, UP + M, 88).ms);
        assertEquals(2, f.withOpen(false, T + H, UP + M, 88).pct, 1e-9);
        // нет отрезка
        assertEquals(0, new EnergyStats().openAudioMs(T));
    }

    @Test public void saveLoadRoundTrip() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false, true);
        e.event(T + 2 * H, UP + 10 * M, 2, 88, true, false, false);
        e.event(T + 3 * H, UP + 20 * M, 3, 80, false, false, false);
        e.event(T + 4 * H, UP + 25 * M, 4, 79, true, false, false);
        e.event(T + 5 * H, UP + 40 * M, 5, 70, false, false, false, true); // открытый аудио-отрезок
        e.save(ctx);
        EnergyStats l = EnergyStats.load(ctx);
        assertEquals(2 * H, l.offAudio.ms);
        assertEquals(2, l.offAudio.pct, 1e-6);
        assertEquals(10 * M, l.offAudio.awakeMs);
        assertEquals(1, l.offAudio.segments);
        assertEquals(H, l.longOffMs);
        assertEquals(5 * M, l.longOffAwakeMs);
        assertNotNull(l.open);
        assertTrue(l.open.lastAudio);
        assertEquals(H, l.open.audioMs(T + 6 * H));
        EnergyStats.reset(ctx);
        EnergyStats z = EnergyStats.load(ctx);
        assertEquals(0, z.offAudio.ms);
        assertEquals(0, z.longOffMs);
        assertNull(z.open);
    }

    @Test public void oldPrefsWithoutNewKeys() {
        Context ctx = RuntimeEnvironment.getApplication();
        EnergyStats.reset(ctx);
        ctx.getSharedPreferences(EnergyStats.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("off_ms", H).putLong("seg_el", 5).putLong("seg_up", 1).putLong("seg_wall", 1)
                .putFloat("seg_lvl", 80).commit();
        EnergyStats l = EnergyStats.load(ctx);
        assertEquals(H, l.off.ms);
        assertEquals(0, l.offAudio.ms);
        assertEquals(0, l.longOffMs);
        assertNotNull(l.open);
        assertFalse(l.open.lastAudio);
    }

    @Test public void oldSevenArgEventStillWorks() {
        EnergyStats e = new EnergyStats();
        e.event(T, UP, 1, 90, false, false, false);
        e.event(T + 3 * H, UP + 10 * M, 2, 88, true, false, false);
        assertEquals(3 * H, e.off.ms);
        assertEquals(0, e.offAudio.ms);
        assertNotNull(e.lastNight);
        assertEquals(3 * H, e.longOffMs);
    }
}
