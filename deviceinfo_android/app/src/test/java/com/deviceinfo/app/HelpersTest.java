package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/** Чистые юнит-тесты статических помощников (без Android). */
public class HelpersTest {
    @Test public void soc_detect_honorX6c() throws Exception {
        Map<String, String> p = HonorX6cSimulationTest.props();
        SocDatabase.SocResult r = SocDatabase.detect(p, "NIC-LX2", "HNNIC-Q", "NICL", 1700000, 2000000);
        System.out.println("SoC: " + r);
        assertNotNull(r);
        assertTrue(r.toString(), r.name.contains("G81 Ultra"));
    }

    @Test public void soc_detect_nullProps() {
        SocDatabase.SocResult r = SocDatabase.detect(null, "NIC-LX2", null, null, 0, 0);
        assertNotNull(r);
        assertTrue(r.toString(), r.name.contains("G81"));
    }

    @Test public void soc_detect_otherChips() {
        Map<String, String> p = new HashMap<>();
        p.put("ro.soc.model", "MT6769Z");
        SocDatabase.SocResult r = SocDatabase.detect(p, "X", "x", "y", 1800000, 2000000);
        System.out.println("MT6769Z: " + r);
        assertTrue(r.toString(), r.name.contains("G85"));
        p.put("ro.soc.model", "MT6769T");
        r = SocDatabase.detect(p, "X", "x", "y", 1800000, 2000000);
        assertTrue(r.toString(), r.name.contains("G80"));
    }

    @Test public void battery_normalize_magicOs() {
        double[] r = BatteryPage.normalize(3950, 412, 76, true);
        assertEquals(3950, r[0], 0.01);
        assertEquals(412, r[1], 0.01);
        assertEquals(1, r[2], 0);
    }

    @Test public void battery_normalize_standardMicroAmps() {
        double[] r = BatteryPage.normalize(3950000, -412000, 76, false);
        assertEquals(3950, r[0], 0.01);
        assertEquals(-412, r[1], 0.01);
        assertEquals(0, r[2], 0);
    }

    @Test public void battery_normalize_x6cMixedUnits() {
        // Honor X6c: счётчик в µA·ч, ток в мА
        double[] r = BatteryPage.normalize(3500000, -675, 54, true);
        assertEquals(3500, r[0], 0.01);
        assertEquals(-675, r[1], 0.01);
        assertEquals(-675, BatteryPage.currentToMa(-675, 0, false), 0.01);
        assertEquals(-675, BatteryPage.currentToMa(-675000, 1, true), 0.01);
    }

    @Test public void battery_normalize_noData() {
        double[] r = BatteryPage.normalize(Integer.MIN_VALUE, Integer.MIN_VALUE, 76, true);
        assertTrue(Double.isNaN(r[0]));
        assertTrue(Double.isNaN(r[1]));
        assertEquals(-1, r[2], 0);
        assertTrue(Double.isNaN(BatteryPage.currentToMa(0, 1, true)));
    }

    @Test public void battery_signVote() {
        assertEquals(1, BatteryPage.signVote(-400, 3));
        assertEquals(-1, BatteryPage.signVote(400, 3));
        assertEquals(1, BatteryPage.signVote(400, 2));
        assertEquals(0, BatteryPage.signVote(2, 3));
        assertEquals(0, BatteryPage.signVote(Double.NaN, 3));
        assertEquals(0, BatteryPage.signVote(400, 5));
    }

    @Test public void battery_misc() {
        assertEquals(5300, BatteryPage.knownCapacity("NIC-LX2"));
        assertEquals(5300, BatteryPage.knownCapacity("honor x6c"));
        assertEquals(0, BatteryPage.knownCapacity("pixel 8"));
        assertEquals(0, BatteryPage.knownCapacity(null));
        assertEquals(3.0, BatteryPage.median(Arrays.asList(5.0, 1.0, 3.0)), 0);
    }

    @Test public void memory_nominalGb() {
        long gb = 1L << 30;
        assertEquals(6, MemoryPage.nominalGb(5765432L * 1024));
        assertEquals(4, MemoryPage.nominalGb(4 * gb - 200L * 1024 * 1024));
        assertEquals(8, MemoryPage.nominalGb((long) (7.4 * gb)));
        assertEquals(12, MemoryPage.nominalGb((long) (11.2 * gb)));
        assertEquals(128, MemoryPage.nominalStorageGb(110_000_000_000L));
        assertEquals(256, MemoryPage.nominalStorageGb(230_000_000_000L));
    }

    @Test public void gpu_parsers() {
        String v = "OpenGL ES 3.2 v1.r32p1-01eac0.abcdef";
        assertEquals("r32p1", GpuPage.parseMaliDriver(v));
        assertEquals("3.2", GpuPage.parseGlEsVersion(v));
        assertNull(GpuPage.parseMaliDriver(null));
        assertEquals(Integer.valueOf(2), GpuPage.parseMaliCores("Mali-G52 MC2"));
        assertNull(GpuPage.parseMaliCores("Mali-G52"));
        assertEquals("1.1.0", GpuPage.decodeVulkan((1 << 22) | (1 << 12)));
        assertEquals("2023-03-01", GpuPage.decodeDeqp((2023 << 16) | (3 << 8) | 1));
        assertEquals(823.0, GpuPage.toMhz(823000), 0);
        assertEquals(823.0, GpuPage.toMhz(823000000), 0);
        assertEquals(823.0, GpuPage.toMhz(823), 0);
        assertEquals(823.0, GpuPage.parseCurFreqMhz("freq = 823000"), 0);
        assertEquals(823.0, GpuPage.parseCurFreqMhz("823000"), 0);
        assertNull(GpuPage.parseCurFreqMhz("garbage"));
        assertEquals(3, GpuPage.parseOppTable("[00] freq = 823000, volt = 1\n[01] freq = 700000\n[02] freq = 500000").size());
        assertEquals(Double.valueOf(37), GpuPage.parseUtilization("37 %"));
        assertNull(GpuPage.parseUtilization("250"));
        String[] spec = GpuPage.referenceSpec("MT6769", "NIC-LX2", "mt6768");
        assertNotNull(spec);
        assertTrue(spec[0], spec[0].contains("Mali-G52"));
    }

    @Test public void sensors_rateHz() {
        assertEquals("100 Гц", SensorsPage.rateHz(10000));
        assertEquals("по событию", SensorsPage.rateHz(0));
        assertEquals("—", SensorsPage.rateHz(-1));
    }

    @Test public void cpu_ranges_and_names() {
        TreeSet<Integer> s = CpuInfo.parseRanges("0-3,6");
        assertEquals(new TreeSet<>(Arrays.asList(0, 1, 2, 3, 6)), s);
        assertTrue(CpuInfo.coreName(0x41, 0xd05).contains("A55"));
        assertTrue(CpuInfo.coreName(0x41, 0xd0a).contains("A75"));
        assertNotNull(CpuInfo.implementerName(0x41));
    }

    @Test public void sensorLive_formatting() {
        assertEquals("портрет", SensorLive.orientationName(0));
        assertEquals("альбомная вправо", SensorLive.orientationName(3));
        assertTrue(SensorLive.orientationName(9).contains("9"));
        assertTrue(SensorLive.format(android.hardware.Sensor.TYPE_ACCELEROMETER, new float[]{0, 0, 9.81f}, 20, 1).contains("9.81"));
        assertTrue(SensorLive.format(android.hardware.Sensor.TYPE_PROXIMITY, new float[]{0}, 5, 1).contains("близко"));
        assertTrue(SensorLive.format(android.hardware.Sensor.TYPE_PROXIMITY, new float[]{5}, 5, 1).contains("далеко"));
        assertEquals("120 лк", SensorLive.format(android.hardware.Sensor.TYPE_LIGHT, new float[]{120}, 100, 1));
    }

    @Test public void battery_stubDetection() {
        assertTrue(BatteryPage.implausibleCounter(3500, 52, 5300));
        assertFalse(BatteryPage.implausibleCounter(2800, 52, 5300));
        assertFalse(BatteryPage.implausibleCounter(3500, 52, 0));
        assertTrue(BatteryPage.implausibleCounter(300, 52, 5300));
    }

    @Test public void battery_meter_discharge600mA() {
        BatteryMeter.MeterState s = new BatteryMeter.MeterState();
        double design = 5300, ma = 600;
        double msPerLevel = design * 0.01 / ma * 3600000.0;
        BatteryMeter.Measurement m = null;
        long t = 0;
        double lvlExact = 80.5; // уровень падает на 1 % каждые msPerLevel
        long end = (long) (msPerLevel * 8);
        for (; t <= end && m == null; t += 1500) {
            int lvl = (int) Math.floor(80.9999 - t / msPerLevel);
            m = BatteryMeter.step(s, t, lvl, -1, -ma, design);
        }
        assertNotNull("measurement after >=5 levels", m);
        assertEquals(5300, m.capacityMah, 5300 * 0.03);
        assertEquals(5, m.dLevel);
    }

    @Test public void battery_meter_abortsOnGap() {
        BatteryMeter.MeterState s = new BatteryMeter.MeterState();
        long t = 0;
        BatteryMeter.step(s, t, 80, -1, -600, 5300);
        t += 1500; BatteryMeter.step(s, t, 79, -1, -600, 5300);
        assertTrue(s.active);
        t += 1500; BatteryMeter.step(s, t, 79, -1, -600, 5300);
        t += 30000; BatteryMeter.step(s, t, 79, -1, -600, 5300);
        assertFalse(s.active);
        assertEquals("gap", s.lastAbort);
        // уровень сменился в тот же шаг, что и пауза — граница неточная, сессия не стартует
        t += 30000; BatteryMeter.step(s, t, 78, -1, -600, 5300);
        assertFalse(s.active);
        t += 1500; BatteryMeter.step(s, t, 77, -1, -600, 5300);
        assertTrue(s.active);
        // смена направления прерывает
        t += 1500; BatteryMeter.step(s, t, 77, 1, 600, 5300);
        assertFalse(s.active);
    }

    @Test public void battery_meter_rejectsImplausibleAndMedian() {
        BatteryMeter.MeterState s = new BatteryMeter.MeterState();
        long t = 0; BatteryMeter.Measurement m = null;
        // 5 % за 1 мин при 600 мА = ~ 50 мА·ч => 1000 мА·ч, отвергается (< 0.4 × 5300)
        int lvl = 80; BatteryMeter.step(s, t, lvl, -1, -600, 5300);
        for (int i = 0; i < 60 * 2 * 7 && m == null; i++) {
            t += 500; if (i % 80 == 79) lvl--;
            BatteryMeter.step(s, t, lvl, -1, -600, 5300);
        }
        assertNull(m);
        java.util.List<BatteryMeter.Measurement> l = new java.util.ArrayList<>();
        l.add(new BatteryMeter.Measurement(5000, 0, 5, -1));
        l.add(new BatteryMeter.Measurement(5200, 0, 5, -1));
        l.add(new BatteryMeter.Measurement(5100, 0, 5, -1));
        assertEquals(5100, BatteryMeter.medianCapacity(l), 0);
        assertTrue(Double.isNaN(BatteryMeter.medianCapacity(new java.util.ArrayList<>())));
    }

    private static java.util.List<BatteryHistory.Sample> steps(int minutes, int everyMin, double from, double stepPct, boolean ch) {
        java.util.List<BatteryHistory.Sample> l = new java.util.ArrayList<>();
        for (int m = 0; m <= minutes; m++)
            l.add(new BatteryHistory.Sample(m * 60_000L, from + stepPct * (m / everyMin), ch ? 2 : 3, ch, 30, 4.0));
        return l;
    }

    @Test public void regressionRate_integerSteps_6perHour() {
        java.util.List<BatteryHistory.Sample> l = steps(40, 10, 80, -1, false);
        long now = 40 * 60_000L;
        assertEquals(6.0, BatteryHistory.dischargeRate(l, now), 0.5);
        double[] e = BatteryHistory.estimate(l, now, 76, false);
        assertEquals(1, e[0], 0);
        assertEquals(76 / 6.0 * 3600, e[1], 0.1 * 76 / 6.0 * 3600);
    }

    @Test public void regressionRate_needsSpanAndChange() {
        // 10 мин и 1 % — мало
        assertTrue(Double.isNaN(BatteryHistory.dischargeRate(steps(10, 10, 80, -1, false), 10 * 60_000L)));
        // 20 мин, но уровень не менялся
        assertTrue(Double.isNaN(BatteryHistory.dischargeRate(steps(20, 100, 80, -1, false), 20 * 60_000L)));
        // устаревший последний замер
        assertTrue(Double.isNaN(BatteryHistory.dischargeRate(steps(40, 10, 80, -1, false), 60 * 60_000L)));
    }

    @Test public void regressionRate_gapResetsSegment() {
        java.util.List<BatteryHistory.Sample> l = new java.util.ArrayList<>();
        for (int m = 0; m <= 10; m++) l.add(new BatteryHistory.Sample(m * 60_000L, 90 - m / 5, 3, false, 30, 4.0));
        for (int m = 0; m <= 10; m++) l.add(new BatteryHistory.Sample((60 + m) * 60_000L, 70, 3, false, 30, 4.0));
        assertTrue(Double.isNaN(BatteryHistory.dischargeRate(l, 70 * 60_000L)));
    }

    @Test public void regressionRate_charging() {
        java.util.List<BatteryHistory.Sample> l = steps(30, 3, 20, 1, true); // 1 % / 3 мин = 20 %/ч
        assertEquals(20.0, BatteryHistory.chargeRate(l, 30 * 60_000L), 1.5);
    }

    @Test public void history_oldJsonWithoutCurrent_isNaN() {
        BatteryHistory.Sample s = new BatteryHistory.Sample(1, 50, 3, false, 30, 4.0);
        assertTrue(Double.isNaN(s.ma));
    }

    @Test public void evenness_grading() {
        java.util.List<Double> flat = new java.util.ArrayList<>(), mid = new java.util.ArrayList<>(), wild = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            flat.add(-300.0 - (i % 2) * 20);       // CV ~3 %
            mid.add(i % 2 == 0 ? -300.0 : -600.0); // CV ~33 %
            wild.add(i % 2 == 0 ? -100.0 : -900.0);// CV ~80 %
        }
        assertEquals(1, BatteryMeter.evennessGrade(flat));
        assertEquals(2, BatteryMeter.evennessGrade(mid));
        assertEquals(3, BatteryMeter.evennessGrade(wild));
        assertEquals(0, BatteryMeter.evennessGrade(flat.subList(0, 5)));
    }

    @Test public void currentRing_tenSecondBuckets() {
        BatteryMeter.CurrentRing r = new BatteryMeter.CurrentRing();
        long t0 = 1_000_000_000L;
        for (int i = 0; i < 60; i++) r.add(t0 + i * 1500L, -300 - (i % 7)); // 90 с ~ 9 корзин
        java.util.List<Double> p = r.points(t0 + 90_000, BatteryMeter.WIN5_MS);
        assertTrue(p.size() >= 8 && p.size() <= 10);
        assertEquals(-303, r.avg(t0 + 90_000, BatteryMeter.WIN5_MS), 3);
        r.add(t0 + 3 * 3600_000L, -100);
        assertEquals(1, r.b.size()); // старое вытеснено
    }

    @Test public void github_redactIps() {
        String r = "Bluetooth: Да\nIP-адреса: wlan0: fe80::98e0:5aff:fe5f:766d\nwlan0: 10.104.41.22\nDNS: 10.104.41.162\nBluetooth LE: Да\n";
        String o = GitHubUploader.redact(r);
        assertFalse(o, o.contains("10.104"));
        assertFalse(o, o.contains("fe80"));
        assertTrue(o, o.contains("IP-адреса: [скрыто]"));
        assertTrue(o, o.contains("Bluetooth LE: Да"));
        assertTrue(o, o.contains("Bluetooth: Да"));
    }
}
