package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Чистая логика точного замера и фактического лимита частоты (без Android). */
public class PreciseMeterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Разряд током ma при ёмкости cap: уровень = start − израсходовано/(cap/100). */
    private static PreciseMeter.Result run(PreciseMeter.State s, int start, double cap, double ma, long stepMs,
                                           int stopAtLevelPlug, long gapAtMs) {
        double used = 0; long t = 0;
        for (int i = 0; i < 100000; i++) {
            int level = start - (int) Math.floor(used / (cap / 100.0));
            boolean dis = !(stopAtLevelPlug > 0 && level <= stopAtLevelPlug);
            if (gapAtMs > 0 && t >= gapAtMs) { t += 200_000; gapAtMs = -1; }
            PreciseMeter.Result r = PreciseMeter.step(s, t, level, dis, -ma, false);
            if (r != null || s.done) return r;
            t += stepMs; used += ma * stepMs / 3600000.0;
        }
        return null;
    }

    @Test public void fullRunIsAccurate() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.Result r = run(s, 100, 4500, 600, 3000, 0, 0);
        assertNotNull(s.abort, r);
        assertEquals(4500, r.capacityMah, 4500 * 0.01);
        assertEquals(50, r.delta);
        assertTrue(r.accuracyPct > 3 && r.accuracyPct < 5);
    }

    @Test public void startsNotAbove97() {
        PreciseMeter.State s = new PreciseMeter.State();
        run(s, 100, 4500, 600, 3000, 0, 0);
        assertTrue("" + s.startLevel, s.startLevel <= PreciseMeter.MAX_START);
    }

    @Test public void lowStartShrinksTargetAndEndsAt10() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.Result r = run(s, 40, 5000, 400, 5000, 0, 0);
        assertNotNull(s.abort, r);
        assertEquals(29, r.delta);  // старт 39, конец 10
        assertEquals(5000, r.capacityMah, 5000 * 0.015);
    }

    @Test public void chargerAfter20GivesResult() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.Result r = run(s, 90, 4800, 500, 3000, 62, 0);
        assertNotNull(s.abort, r);
        assertEquals(26, r.delta);
        assertTrue(r.reason, r.reason.contains("зарядка"));
        assertEquals(4800, r.capacityMah, 4800 * 0.02);
    }

    @Test public void chargerBefore20Aborts() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.Result r = run(s, 90, 4800, 500, 3000, 80, 0);
        assertNull(r);
        assertTrue(s.done);
        assertTrue(s.abort, s.abort.contains("зарядка"));
    }

    @Test public void longGapAborts() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.Result r = run(s, 90, 4800, 500, 3000, 0, 600_000);
        assertNull(r);
        assertTrue(s.abort, s.abort.contains("пауза"));
    }

    @Test public void finishNowAfter20() {
        PreciseMeter.State s = new PreciseMeter.State();
        double used = 0; long t = 0; PreciseMeter.Result r = null;
        for (int i = 0; i < 100000 && r == null; i++) {
            int level = 90 - (int) Math.floor(used / 45.0);
            boolean fin = s.active && s.startLevel - s.edgeLevel >= 22;
            r = PreciseMeter.step(s, t, level, true, -500, fin);
            t += 3000; used += 500 * 3000 / 3600000.0;
        }
        assertNotNull(r);
        assertEquals(22, r.delta);
        assertTrue(r.reason.contains("вручную"));
        assertEquals(4500, r.capacityMah, 4500 * 0.02);
    }

    @Test public void finishNowTooEarlyAborts() {
        PreciseMeter.State s = new PreciseMeter.State();
        PreciseMeter.step(s, 0, 80, true, -500, false);
        PreciseMeter.step(s, 3000, 79, true, -500, false);
        assertTrue(s.active);
        assertNull(PreciseMeter.step(s, 6000, 79, true, -500, true));
        assertTrue(s.done);
        assertTrue(s.abort.contains("нужно"));
    }

    @Test public void accuracyShrinksWithDelta() {
        assertTrue(PreciseMeter.accuracy(5) > 25);
        assertTrue(PreciseMeter.accuracy(50) < 4);
    }

    private void write(File root, String path, String v) throws Exception {
        File f = new File(root, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), v.getBytes("UTF-8"));
    }

    @Test public void cpuLimitsDetectsThrottling() throws Exception {
        File root = tmp.getRoot();
        String b = "sys/devices/system/cpu/cpufreq/";
        write(root, b + "policy0/related_cpus", "0 1 2 3 4 5\n");
        write(root, b + "policy0/cpuinfo_max_freq", "1700000\n");
        write(root, b + "policy0/scaling_max_freq", "1700000\n");
        write(root, b + "policy6/related_cpus", "6 7\n");
        write(root, b + "policy6/cpuinfo_max_freq", "2000000\n");
        write(root, b + "policy6/scaling_max_freq", "1500000\n");
        String old = Util.root;
        try {
            Util.root = root.getAbsolutePath();
            List<long[]> l = ThermalPage.cpuLimits();
            assertEquals(2, l.size());
            assertArrayEquals(new long[]{0, 5, 1700000, 1700000}, l.get(0));
            assertArrayEquals(new long[]{6, 7, 2000000, 1500000}, l.get(1));
        } finally { Util.root = old; }
    }

    @Test public void peakFreqWindow() {
        ThermalPage.resetFreqs();
        ThermalPage.addFreq(6, 0, 2000000);
        ThermalPage.addFreq(6, 60_000, 1100000);
        assertEquals(2000000, ThermalPage.peakFreq(6, 100_000));
        ThermalPage.addFreq(6, 130_000, 900000);
        assertEquals(1100000, ThermalPage.peakFreq(6, 130_000));   // отсчёт 0 мс вышел из окна 2 мин
        assertEquals(0, ThermalPage.peakFreq(0, 130_000));
    }

    @Test public void closedLimitShowsPeak() throws Exception {
        File root = tmp.getRoot();
        String b = "sys/devices/system/cpu/";
        write(root, b + "cpufreq/policy6/related_cpus", "6 7\n");
        write(root, b + "cpufreq/policy6/cpuinfo_max_freq", "2000000\n");
        write(root, b + "cpu6/cpufreq/scaling_cur_freq", "2000000\n");
        write(root, b + "cpu7/cpufreq/scaling_cur_freq", "1500000\n");
        String old = Util.root;
        try {
            Util.root = root.getAbsolutePath();
            ThermalPage.resetFreqs();
            List<long[]> l = ThermalPage.cpuLimits();
            assertEquals(0, l.get(0)[3]);
            assertEquals(2000000, ThermalPage.curFreq(6, 7));
            ThermalPage.sampleFreqs(l, 1000);
            assertEquals(2000000, ThermalPage.peakFreq(6, 1000));
        } finally { Util.root = old; ThermalPage.resetFreqs(); }
    }
}
