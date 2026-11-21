package com.deviceinfo.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ThermalPage implements Page {
    @Override public String title() { return "Температуры"; }

    private static final String[] STATUS = {"Норма", "Лёгкий", "Умеренный", "Сильный", "Критический", "Аварийный", "Отключение"};
    private static final String[] CPU_TYPES = {"mtktscpu", "cpu-little", "cpu-big", "soc_max", "cpu", "tsens", "apc", "cluster", "cpuss"};

    private static long headroomAt = -1;
    private static float headroomVal = Float.NaN;

    /** Сырое значение -> °C (миллиградусы, децградусы или градусы); NaN если некорректно. */
    public static double normalizeTemp(long raw) {
        double c;
        long a = Math.abs(raw);
        if (a >= 1000) c = raw / 1000.0;
        else if (a >= 150) c = raw / 10.0;
        else c = raw;
        if (Double.isNaN(c) || c <= -40 || c > 150 || c == 0) return Double.NaN;
        return c;
    }

    public static boolean isCpuType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        for (String k : CPU_TYPES) if (t.contains(k)) return true;
        return false;
    }

    public static boolean isGpuType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("mtktsgpu") || t.contains("gpu");
    }

    public static boolean isBatteryType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("battery") || t.equals("bat") || t.contains("mtktsbattery");
    }

    private static String fmt(double c) { return Util.f("%.1f °C", c); }

    @Override public void build(Context ctx, Section out) {
        try { buildImpl(ctx, out); } catch (Throwable t) {
            out.header("Температуры");
            out.note("Ошибка: " + t);
        }
    }

    private void buildImpl(Context ctx, Section out) {
        // --- зоны ---
        List<String[]> rows = new ArrayList<>();
        double cpu = Double.NaN, gpu = Double.NaN, bat = Double.NaN;
        String cpuName = null, gpuName = null;
        int valid = 0;
        for (String z : Util.list("/sys/class/thermal")) {
            if (!z.startsWith("thermal_zone")) continue;
            String type = Util.read("/sys/class/thermal/" + z + "/type");
            Long raw = Util.readLong("/sys/class/thermal/" + z + "/temp");
            if (raw == null) continue;
            double c = normalizeTemp(raw);
            if (Double.isNaN(c)) continue;
            valid++;
            rows.add(new String[]{z.replace("thermal_zone", "zone"), type == null ? "—" : type, fmt(c)});
            if (type == null) continue;
            if (isCpuType(type) && (Double.isNaN(cpu) || c > cpu)) { cpu = c; cpuName = type; }
            if (isGpuType(type) && (Double.isNaN(gpu) || c > gpu)) { gpu = c; gpuName = type; }
            if (isBatteryType(type) && (Double.isNaN(bat) || c > bat)) bat = c;
        }

        // батарея из интента
        double batIntent = Double.NaN;
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t != Integer.MIN_VALUE) batIntent = t / 10.0;
            }
        } catch (Throwable ignored) { }

        out.header("Сводка");
        if (!Double.isNaN(cpu)) out.row("Процессор", fmt(cpu) + " (" + cpuName + ")", Section.temp(cpu));
        else out.row("Процессор", "нет данных", Section.MUTED);
        if (!Double.isNaN(gpu)) out.row("GPU", fmt(gpu) + " (" + gpuName + ")", Section.temp(gpu));
        if (!Double.isNaN(batIntent)) out.row("Батарея", fmt(batIntent), Section.temp(batIntent));
        else if (!Double.isNaN(bat)) out.row("Батарея", fmt(bat) + " (датчик зоны)", Section.temp(bat));
        else out.row("Батарея", "нет данных", Section.MUTED);

        // --- состояние системы ---
        out.header("Тепловое состояние системы");
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 29) {
                int st = pm.getCurrentThermalStatus();
                String name = st >= 0 && st < STATUS.length ? STATUS[st] : "неизвестно (" + st + ")";
                out.row("Статус", name, st <= 0 ? Section.OK : st <= 2 ? Section.WARN : Section.CRIT);
            } else out.row("Статус", "недоступно (нужен Android 10+)", Section.MUTED);
            if (pm != null && Build.VERSION.SDK_INT >= 30) {
                float h = headroom(pm);
                if (Float.isNaN(h)) out.row("До троттлинга", "нет данных (HAL не отдаёт запас)", Section.MUTED);
                else {
                    double pct = h * 100.0;
                    out.bar("Тепловой запас (headroom)", Util.f("%.2f  (%.0f%%)", h, pct), pct,
                            Section.level(pct, 85, 100));
                    // AOSP ThermalManagerService: headroom = (T − T_severe + 30) / 30,
                    // где T — прогноз температуры датчика корпуса (SKIN) через 10 с,
                    // T_severe — порог статуса «Сильный» из Thermal HAL. Значит, 1.0 — порог,
                    // а каждые 0.1 — это 3 °C.
                    double toLimit = (1.0 - h) * 30.0;
                    out.row("До порога троттлинга", toLimit > 0
                            ? Util.f("≈ %.0f °C (прогноз на 10 с)", toLimit)
                            : Util.f("порог превышен на ≈ %.0f °C", -toLimit),
                            toLimit > 3 ? Section.OK : Section.WARN);
                    if (Build.VERSION.SDK_INT >= 35) {
                        try {
                            java.util.Map<Integer, Float> th = pm.getThermalHeadroomThresholds();
                            if (th != null && !th.isEmpty()) {
                                java.util.List<String[]> thRows = new java.util.ArrayList<>();
                                for (java.util.Map.Entry<Integer, Float> e : new java.util.TreeMap<>(th).entrySet()) {
                                    int k = e.getKey();
                                    thRows.add(new String[]{k >= 0 && k < STATUS.length ? STATUS[k] : "" + k,
                                            Util.f("%.2f", e.getValue())});
                                }
                                out.table(new String[]{"Статус", "Порог headroom"}, thRows, new float[]{0.6f, 0.4f});
                            }
                        } catch (Throwable ignored) { }
                    }
                    out.note("Запас считает сама система (Thermal HAL): у неё есть доступ ко всем датчикам, "
                            + "в том числе к закрытым для приложений. Берётся датчик корпуса (SKIN), а не "
                            + "процессора: 1.00 — порог, с которого Android начинает снижать частоты, "
                            + "шаг 0.10 ≈ 3 °C. Саму температуру система приложениям не отдаёт.");
                }
            }
        } catch (Throwable t) {
            out.row("Статус", "недоступно", Section.MUTED);
        }

        // --- таблица зон ---
        out.header("Датчики thermal_zone");
        if (valid == 0) {
            out.note("Датчики закрыты прошивкой (SELinux)");
        } else {
            out.table(new String[]{"Зона", "Тип", "Температура"}, rows, new float[]{0.25f, 0.45f, 0.30f});
        }

        // --- hwmon ---
        List<String[]> hw = new ArrayList<>();
        for (String h : Util.list("/sys/class/hwmon")) {
            String name = Util.read("/sys/class/hwmon/" + h + "/name");
            for (String f : Util.list("/sys/class/hwmon/" + h)) {
                if (!f.matches("temp\\d+_input")) continue;
                Long raw = Util.readLong("/sys/class/hwmon/" + h + "/" + f);
                if (raw == null) continue;
                double c = normalizeTemp(raw);
                if (Double.isNaN(c)) continue;
                hw.add(new String[]{h, name == null ? "—" : name, fmt(c)});
            }
        }
        if (!hw.isEmpty()) {
            out.header("hwmon");
            out.table(new String[]{"Устройство", "Имя", "Температура"}, hw, new float[]{0.25f, 0.45f, 0.30f});
        }
    }

    private static synchronized float headroom(PowerManager pm) {
        long now = SystemClock.elapsedRealtime();
        if (headroomAt < 0 || now - headroomAt >= 2000) {
            headroomAt = now;
            try { headroomVal = pm.getThermalHeadroom(10); } catch (Throwable t) { headroomVal = Float.NaN; }
        }
        return headroomVal;
    }
}
