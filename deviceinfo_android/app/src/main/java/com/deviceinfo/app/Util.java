package com.deviceinfo.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Чтение файлов /sys и /proc, системных свойств и форматирование. */
public final class Util {
    private Util() {}

    /** Для тестов (имитация устройства): префикс к путям /sys и /proc и подмена свойств. */
    public static String root = "";
    public static Map<String, String> propOverride = null;

    private static String p(String path) { return root.isEmpty() ? path : root + path; }

    /** Список файлов каталога (имена) или пустой массив. */
    public static String[] list(String dir) {
        String[] r = new File(p(dir)).list();
        if (r == null) return new String[0];
        java.util.Arrays.sort(r);
        return r;
    }

    public static boolean exists(String path) { return new File(p(path)).exists(); }

    /** Содержимое файла (trim) или null, если нет файла / нет доступа (SELinux). */
    public static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(p(path)))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 1 << 20) break;
            }
            return sb.toString().trim();
        } catch (Throwable e) {
            return null;
        }
    }

    public static Long readLong(String path) {
        String s = read(path);
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.split("\\s+")[0]); } catch (Exception e) { return null; }
    }

    public static boolean canRead(String path) {
        return read(path) != null;
    }

    private static Map<String, String> props;
    private static Method spGet;

    /** Системное свойство (getprop). Сначала SystemProperties, затем вывод команды getprop. */
    public static synchronized String prop(String key) {
        if (propOverride != null) {
            String v = propOverride.get(key);
            return v == null || v.isEmpty() ? null : v;
        }
        try {
            if (spGet == null) {
                Class<?> c = Class.forName("android.os.SystemProperties");
                spGet = c.getMethod("get", String.class);
            }
            String v = (String) spGet.invoke(null, key);
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) { }
        String v = allProps().get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    public static String prop(String key, String def) {
        String v = prop(key);
        return v == null ? def : v;
    }

    /** Все свойства из вывода getprop (кэшируются). */
    public static synchronized Map<String, String> allProps() {
        if (propOverride != null) return propOverride;
        if (props == null) {
            props = new HashMap<>();
            Pattern p = Pattern.compile("\\[(.+?)\\]: \\[(.*)\\]");
            for (String line : exec("getprop").split("\n")) {
                Matcher m = p.matcher(line);
                if (m.find()) props.put(m.group(1), m.group(2));
            }
        }
        return props;
    }

    public static String exec(String... cmd) {
        try {
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            pr.waitFor();
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }

    public static String bytes(double b) {
        String[] u = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        int i = 0;
        while (Math.abs(b) >= 1024 && i < u.length - 1) { b /= 1024; i++; }
        return i == 0 ? String.format(Locale.US, "%.0f %s", b, u[i])
                : String.format(Locale.US, "%.2f %s", b, u[i]);
    }

    public static String khz(Long khz) {
        if (khz == null || khz <= 0) return null;
        double mhz = khz / 1000.0;
        return mhz >= 1000 ? String.format(Locale.US, "%.2f ГГц", mhz / 1000) : String.format(Locale.US, "%.0f МГц", mhz);
    }

    public static String duration(double seconds) {
        if (seconds < 0) return null;
        long s = (long) seconds;
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + " д " + h + " ч " + m + " мин";
        if (h > 0) return h + " ч " + m + " мин";
        if (m > 0) return m + " мин " + sec + " с";
        return sec + " с";
    }

    public static String f(String fmt, Object... args) { return String.format(Locale.US, fmt, args); }
}
