package com.deviceinfo.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.view.Display;
import android.widget.Button;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBatteryManager;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Симуляция Honor X6c (NIC-LX2, Helio G81 Ultra, MagicOS 9) на JVM через Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h802dp-xhdpi", shadows = HonorX6cSimulationTest.ShadowPowerProfile.class)
public class HonorX6cSimulationTest {
    /** Паспортная ёмкость Honor X6c (power_profile.xml: battery.capacity = 5300). */
    @org.robolectric.annotation.Implements(className = "com.android.internal.os.PowerProfile", isInAndroidSdk = false)
    public static class ShadowPowerProfile {
        @org.robolectric.annotation.Implementation protected void __constructor__(Context c) { }
        @org.robolectric.annotation.Implementation protected double getBatteryCapacity() { return 5300.0; }
    }

    private Context ctx;
    private static final Map<String, String> reports = new LinkedHashMap<>();

    // ---------- окружение ----------
    static File resDir(String name) throws Exception {
        java.net.URL u = HonorX6cSimulationTest.class.getClassLoader().getResource("honor_x6c/" + name);
        assertNotNull("fixture missing: " + name, u);
        return new File(u.toURI());
    }

    static Map<String, String> props() throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String l : Files.readAllLines(new File(resDir("props.txt").getPath()).toPath(), StandardCharsets.UTF_8)) {
            int i = l.indexOf('=');
            if (i > 0) m.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
        }
        return m;
    }

    private void setup(String sysroot) throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        Util.root = resDir(sysroot).getAbsolutePath();
        Util.propOverride = props();
        ReflectionHelpers.setStaticField(Build.class, "MODEL", "NIC-LX2");
        ReflectionHelpers.setStaticField(Build.class, "MANUFACTURER", "HONOR");
        ReflectionHelpers.setStaticField(Build.class, "BRAND", "HONOR");
        ReflectionHelpers.setStaticField(Build.class, "DEVICE", "HNNIC-Q");
        ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "NIC-LX2");
        ReflectionHelpers.setStaticField(Build.class, "HARDWARE", "mt6768");
        ReflectionHelpers.setStaticField(Build.class, "BOARD", "NICL");
        try { ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "MT6769"); } catch (Throwable ignored) { }
        try { ReflectionHelpers.setStaticField(Build.class, "SOC_MANUFACTURER", "Mediatek"); } catch (Throwable ignored) { }

        // батарея
        Intent b = new Intent(Intent.ACTION_BATTERY_CHANGED);
        b.putExtra(BatteryManager.EXTRA_LEVEL, 76);
        b.putExtra(BatteryManager.EXTRA_SCALE, 100);
        b.putExtra(BatteryManager.EXTRA_STATUS, 3);
        b.putExtra(BatteryManager.EXTRA_PLUGGED, 0);
        b.putExtra(BatteryManager.EXTRA_HEALTH, 2);
        b.putExtra(BatteryManager.EXTRA_TEMPERATURE, 312);
        b.putExtra(BatteryManager.EXTRA_VOLTAGE, 3912);
        b.putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-poly");
        b.putExtra(BatteryManager.EXTRA_PRESENT, true);
        b.putExtra("android.os.extra.CYCLE_COUNT", 0);
        ctx.sendStickyBroadcast(b);
        ShadowBatteryManager bm = shadowOf((BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE));
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 3950);
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 412);
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 405);
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 76);
        shadowOf(ctx.getPackageManager()).setSystemFeature("com.hihonor.system.feature", true);

        // экран
        Display d = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        ShadowDisplay sd = shadowOf(d);
        sd.setWidth(720);
        sd.setHeight(1604);
        sd.setDensityDpi(320);
        sd.setXdpi(320);
        sd.setYdpi(320);
        sd.setRefreshRate(120f);
    }

    @Before public void before() { }

    @After public void after() { Util.root = ""; Util.propOverride = null; }

    // ---------- вспомогательное ----------
    private static Section build(Page p, Context c) throws Exception {
        Section s = new Section();
        p.build(c, s);
        return s;
    }

    /** Строит все вкладки дважды; возвращает тексты по названию вкладки. */
    private Map<String, String> buildAll(String tag) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Page p : Pages.create()) {
            Section s1 = build(p, ctx);
            Thread.sleep(300);
            Section s2 = build(p, ctx);
            for (Section s : new Section[]{s1, s2}) {
                assertFalse(p.title() + ": empty", s.items.isEmpty());
                for (Section.Item it : s.items)
                    assertFalse(p.title() + ": section 'Ошибка'", it.type == 0 && "Ошибка".equals(it.key));
            }
            String t = s2.toText();
            System.out.println("\n===== [" + tag + "] " + p.title() + " =====" + t);
            out.put(p.title(), t);
        }
        return out;
    }

    private static String page(Map<String, String> all, String... titleParts) {
        for (Map.Entry<String, String> e : all.entrySet()) {
            String k = e.getKey().toLowerCase();
            for (String tp : titleParts) if (k.contains(tp.toLowerCase())) return e.getValue();
        }
        fail("no page " + java.util.Arrays.toString(titleParts) + " in " + all.keySet());
        return null;
    }

    private static void writeReport(String name, Map<String, String> all) throws Exception {
        reports.put(name, null);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet())
            sb.append("===== ").append(e.getKey()).append(" =====").append(e.getValue()).append('\n');
        File f = new File("build/honor_x6c_report" + (name.equals("closed") ? "" : "_" + name) + ".txt");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- тесты ----------
    @Test public void allPages_closedThermal() throws Exception {
        setup("sysroot");
        Map<String, String> all = buildAll("closed");
        writeReport("closed", all);

        String cpu = page(all, "cpu", "проц");
        assertTrue(cpu, cpu.contains("G81 Ultra"));
        assertTrue(cpu, cpu.contains("Cortex-A75"));
        assertTrue(cpu, cpu.contains("Cortex-A55"));
        assertTrue(cpu, cpu.contains("2.00 ГГц"));
        assertTrue(cpu, cpu.contains("1.70 ГГц"));
    }

    @Test public void battery() throws Exception {
        setup("sysroot");
        Page bp = null;
        for (Page p : Pages.create()) if (p instanceof BatteryPage) bp = p;
        assertNotNull(bp);
        Section s = null;
        for (int i = 0; i < 4; i++) { s = build(bp, ctx); Thread.sleep(250); }
        String t = s.toText();
        System.out.println("BATTERY:\n" + t);
        assertTrue("design 5300\n" + t, t.contains("5300"));
        assertTrue("temp 31.2\n" + t, t.contains("31.2"));
        assertTrue("voltage 3.912\n" + t, t.contains("3.912"));
        assertFalse("412000\n" + t, t.contains("412000"));
        assertFalse("0 мА\n" + t, t.matches("(?s).*[^0-9.]0 мА.*"));
        assertTrue("current ~412 mA\n" + t, t.matches("(?s).*[-−]?412(\\.\\d+)? мА.*"));
        assertTrue("MagicOS units mA\n" + t, t.contains("мА") && (t.contains("MagicOS") || t.contains("мА·ч")));
    }

    @Test public void display() throws Exception {
        setup("sysroot");
        String t = textOf(DisplayPage.class);
        System.out.println("DISPLAY:\n" + t);
        assertTrue(t, t.contains("720 × 1604") || t.contains("720×1604"));
        assertTrue(t, t.matches("(?s).*20(\\.\\d)?:9.*"));
        assertFalse("wrong diagonal from xdpi\n" + t, t.contains("5.5″") || t.contains("5.50″"));
    }

    /** Строгая проверка: маркетинговое соотношение 20:9 (а не 20.1:9). */
    @Test public void display_aspectStrict20to9() throws Exception {
        setup("sysroot");
        String t = textOf(DisplayPage.class);
        assertTrue(t, t.contains("20:9"));
    }

    @Test public void memory() throws Exception {
        setup("sysroot");
        String t = textOf(MemoryPage.class);
        System.out.println("MEMORY:\n" + t);
        assertTrue(t, t.contains("6 ГБ"));
    }

    @Test public void system() throws Exception {
        setup("sysroot");
        String t = textOf(SystemPage.class);
        System.out.println("SYSTEM:\n" + t);
        assertTrue(t, t.contains("HONOR X6c") || t.contains("NIC-LX2"));
        assertTrue(t, t.contains("MagicOS"));
    }

    @Test public void thermal_closed() throws Exception {
        setup("sysroot");
        String t = textOf(ThermalPage.class);
        System.out.println("THERMAL(closed):\n" + t);
        assertTrue(t, t.toLowerCase().contains("закрыт"));
    }

    @Test public void thermal_open() throws Exception {
        setup("sysroot_open");
        String t = textOf(ThermalPage.class);
        System.out.println("THERMAL(open):\n" + t);
        assertTrue(t, t.contains("47") || t.contains("45"));
    }

    @Test public void allPages_openThermal() throws Exception {
        setup("sysroot_open");
        writeReport("open", buildAll("open"));
    }

    @Test public void cpuWithoutProcStat() throws Exception {
        setup("sysroot");
        assertFalse(Util.exists("/proc/stat"));
        String t = textOf(CpuPage.class);
        System.out.println("CPU:\n" + t);
        assertFalse(t.isEmpty());
    }

    @Test public void mainActivityTabs() throws Exception {
        setup("sysroot");
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        Activity a = c.get();
        List<Button> btns = new java.util.ArrayList<>();
        collect(a.getWindow().getDecorView(), btns);
        int tabs = 0;
        for (Page p : Pages.create()) {
            for (Button b : btns) {
                if (p.title().contentEquals(b.getText())) {
                    b.performClick();
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                    Thread.sleep(400);
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                    tabs++;
                }
            }
        }
        assertEquals(Pages.create().size(), tabs);
        c.pause().stop().destroy();
    }

    private static void collect(android.view.View v, List<Button> out) {
        if (v instanceof Button) out.add((Button) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private String textOf(Class<? extends Page> cls) throws Exception {
        Page p = cls.getDeclaredConstructor().newInstance();
        build(p, ctx);
        Thread.sleep(300);
        Section s = build(p, ctx);
        for (Section.Item it : s.items) assertFalse(it.type == 0 && "Ошибка".equals(it.key));
        assertFalse(s.items.isEmpty());
        return s.toText();
    }
}
