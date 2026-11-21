package com.deviceinfo.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GpuPage implements Page {
    @Override public String title() { return "GPU"; }

    /** Результат опроса OpenGL ES (один раз). */
    public static final class GlInfo {
        public boolean ok;
        public String vendor, renderer, version, extensions, glsl;
        public int maxTex, maxRb, maxVertexAttribs, maxVertexUniform, maxFragUniform,
                maxTexUnits, maxVarying, contextVersion;
    }

    private static GlInfo cache;

    public static synchronized GlInfo glInfo() {
        if (cache == null) {
            GlInfo g = new GlInfo();
            try { queryGl(g); } catch (Throwable e) { g.ok = false; }
            cache = g;
        }
        return cache;
    }

    private static void queryGl(GlInfo g) {
        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (dpy == null || dpy == EGL14.EGL_NO_DISPLAY) return;
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return;
        EGLContext ctx = null;
        EGLSurface surf = null;
        try {
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            for (int pass = 0; pass < 2 && ctx == null; pass++) {
                int type = pass == 0 ? (EGL14.EGL_OPENGL_ES2_BIT | 0x40 /* ES3_BIT */) : EGL14.EGL_OPENGL_ES2_BIT;
                int[] attr = {EGL14.EGL_RENDERABLE_TYPE, type, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE};
                num[0] = 0;
                if (!EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, num, 0) || num[0] < 1 || cfgs[0] == null) continue;
                for (int cv = (pass == 0 ? 3 : 2); cv >= 2 && ctx == null; cv--) {
                    int[] ca = {EGL14.EGL_CONTEXT_CLIENT_VERSION, cv, EGL14.EGL_NONE};
                    EGLContext c = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ca, 0);
                    if (c != null && c != EGL14.EGL_NO_CONTEXT) { ctx = c; g.contextVersion = cv; }
                }
                if (ctx != null) {
                    int[] sa = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
                    surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], sa, 0);
                }
            }
            if (ctx == null || surf == null || surf == EGL14.EGL_NO_SURFACE) return;
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return;
            g.vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            g.renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            g.version = GLES20.glGetString(GLES20.GL_VERSION);
            g.extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (g.contextVersion >= 3) {
                try { g.glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION); } catch (Throwable ignored) { }
            } else {
                g.glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION);
            }
            int[] v = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0); g.maxTex = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, v, 0); g.maxRb = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, v, 0); g.maxVertexAttribs = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, v, 0); g.maxVertexUniform = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, v, 0); g.maxFragUniform = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, v, 0); g.maxTexUnits = v[0];
            GLES20.glGetIntegerv(GLES20.GL_MAX_VARYING_VECTORS, v, 0); g.maxVarying = v[0];
            g.ok = g.renderer != null || g.version != null;
        } finally {
            try { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); } catch (Throwable ignored) { }
            try { if (surf != null) EGL14.eglDestroySurface(dpy, surf); } catch (Throwable ignored) { }
            try { if (ctx != null) EGL14.eglDestroyContext(dpy, ctx); } catch (Throwable ignored) { }
            try { EGL14.eglTerminate(dpy); } catch (Throwable ignored) { }
        }
    }

    // ---------- чистые функции (для тестов) ----------

    /** "OpenGL ES 3.2 v1.r32p1-01eac0.abc" -> "r32p1" (или null). */
    public static String parseMaliDriver(String glVersion) {
        if (glVersion == null) return null;
        Matcher m = Pattern.compile("v?\\d+\\.(r\\d+p\\d+)").matcher(glVersion);
        return m.find() ? m.group(1) : null;
    }

    /** "OpenGL ES 3.2 ..." -> "3.2" (или null). */
    public static String parseGlEsVersion(String glVersion) {
        if (glVersion == null) return null;
        Matcher m = Pattern.compile("OpenGL ES(?:-CM)?\\s+(\\d+\\.\\d+)").matcher(glVersion);
        return m.find() ? m.group(1) : null;
    }

    /** "Mali-G52 MC2" -> 2; без MC -> null. */
    public static Integer parseMaliCores(String renderer) {
        if (renderer == null) return null;
        Matcher m = Pattern.compile("MC(\\d+)").matcher(renderer);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Версия Vulkan из целого: major.minor.patch. */
    public static String decodeVulkan(int v) {
        return (v >>> 22) + "." + ((v >> 12) & 0x3ff) + "." + (v & 0xfff);
    }

    /** deqp level: (год<<16)|(месяц<<8)|день -> "2023-03-01". */
    public static String decodeDeqp(int v) {
        int y = v >>> 16, mo = (v >> 8) & 0xff, d = v & 0xff;
        if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31) return String.valueOf(v);
        return Util.f("%04d-%02d-%02d", y, mo, d);
    }

    /** Нормализация числа частоты к МГц: >=1e7 Гц, >=1000 кГц, иначе МГц. */
    public static double toMhz(double v) {
        if (v >= 1e7) return v / 1e6;
        if (v >= 1000) return v / 1000.0;
        return v;
    }

    /** Первое целое после ключа freq/cur_freq/... ("freq = 823000", "cur freq: 823000"). Результат МГц или null. */
    public static Double parseCurFreqMhz(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.matches("\\d+")) {
            double v = Double.parseDouble(t);
            return v > 0 ? toMhz(v) : null;
        }
        Matcher m = Pattern.compile("(?i)(?:cur(?:rent)?[_ ]*(?:opp[_ ]*)?(?:freq(?:uency)?|clk|clock)|\\bfreq(?:uency)?|clock)\\s*[=:]\\s*(\\d+)").matcher(t);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            if (v > 0) return toMhz(v);
        }
        return null;
    }

    /** Таблица OPP ("[00] freq = 823000, volt = ..." или "[00] 823000 ...", или список через пробел) -> МГц по убыванию порядка в файле. */
    public static List<Double> parseOppTable(String text) {
        List<Double> r = new ArrayList<>();
        if (text == null) return r;
        Pattern a = Pattern.compile("(?i)freq(?:uency)?\\s*[=:]\\s*(\\d+)");
        Pattern b = Pattern.compile("^\\s*\\[\\s*\\d+\\s*\\]\\s*(\\d{4,})");
        for (String line : text.split("\n")) {
            Matcher m = a.matcher(line);
            if (m.find()) { r.add(toMhz(Double.parseDouble(m.group(1)))); continue; }
            m = b.matcher(line);
            if (m.find()) r.add(toMhz(Double.parseDouble(m.group(1))));
        }
        if (r.isEmpty()) { // просто числа через пробел (available_frequencies)
            for (String s : text.trim().split("\\s+")) {
                if (s.matches("\\d{3,}")) r.add(toMhz(Double.parseDouble(s)));
            }
        }
        return r;
    }

    /** Загрузка в процентах из текста (первое число 0..100) или null. */
    public static Double parseUtilization(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            if (v >= 0 && v <= 100) return v;
        }
        return null;
    }

    /** Справка по SoC: {gpu, макс. МГц, ядра, архитектура, примечание}. null если не определён. */
    public static String[] referenceSpec(String socModel, String buildModel, String hardware) {
        String s = ((socModel == null ? "" : socModel) + " " + (hardware == null ? "" : hardware)).toUpperCase();
        String m = buildModel == null ? "" : buildModel.toUpperCase();
        String[] g81 = {"Mali-G52 MC2", "820", "2", "Bifrost", "Helio G81 / G81 Ultra (рабочая OPP ≈ 823 МГц)"};
        // Сначала модель устройства, затем точные номера, затем общие — порядок важен:
        // "MT6769Z" содержит "MT6769", поэтому общие правила идут последними.
        if (m.startsWith("NIC-")) return g81;
        if (s.contains("G85") || s.contains("G88") || s.contains("G91") || s.contains("MT6769Z")
                || s.contains("MT6769V/CZ") || s.contains("MT6769H") || s.contains("MT6769G") || s.contains("MT6769K"))
            return new String[]{"Mali-G52 MC2", "1000", "2", "Bifrost", "Helio G85 / G88 / G91"};
        if (s.contains("G80") || s.contains("MT6769T") || s.contains("MT6769V/CT") || s.contains("MT6769V/CU"))
            return new String[]{"Mali-G52 MC2", "950", "2", "Bifrost", "Helio G80"};
        if (s.contains("G81") || s.contains("MT6769J") || s.contains("MT6769L") || s.contains("MT6769S")
                || s.contains("MT6769V/CB")) return g81;
        if (s.contains("G99") || s.contains("MT6789")) return new String[]{"Mali-G57 MC2", "1100", "2", "Valhall", "Helio G99"};
        if (s.contains("MT6769") || s.contains("MT6768"))
            return new String[]{"Mali-G52 MC2", "820–1000", "2", "Bifrost",
                    "семейство Helio P65/G70–G91, точная модель чипа не определена"};
        return null;
    }

    private static String mhz(double v) { return Util.f("%.0f МГц", v); }

    // ---------- страница ----------

    @Override public void build(Context ctx, Section out) {
        GlInfo g = glInfo();
        String renderer = g.ok ? g.renderer : null;

        out.header("Графический процессор");
        out.row("Модель GPU (OpenGL)", renderer);
        out.row("Производитель", g.ok ? g.vendor : null);
        String drv = g.ok ? parseMaliDriver(g.version) : null;
        out.row("Версия драйвера Mali", drv);
        out.row("GL_VERSION", g.ok ? g.version : null);
        out.row("Версия GLSL ES", g.ok ? g.glsl : null);
        Integer cores = parseMaliCores(renderer);
        String[] ref = referenceSpec(Util.prop("ro.soc.model"), Build.MODEL, Util.prop("ro.hardware"));
        boolean coresRef = false;
        if (cores == null && ref != null && (renderer == null || renderer.contains(ref[0].substring(0, ref[0].indexOf(" MC"))))) {
            cores = Integer.valueOf(ref[2]);
            coresRef = true;
        }
        out.row("Шейдерные ядра", cores == null ? null : (cores + (coresRef ? " (по спецификации)" : "")));
        String arch = ref != null ? ref[3] : null;
        if (renderer != null && renderer.matches(".*Mali-G(31|51|52|71|72|76).*")) arch = "Bifrost";
        out.row("Архитектура", arch);

        out.header("Драйвер и свойства");
        out.row("ro.hardware.egl", Util.prop("ro.hardware.egl"));
        out.row("ro.hardware.vulkan", Util.prop("ro.hardware.vulkan"));
        out.row("ro.gfx.driver.0", Util.prop("ro.gfx.driver.0", "не задано"));
        out.row("ro.gfx.driver.1", Util.prop("ro.gfx.driver.1", "не задано"));

        out.header("OpenGL ES");
        String am = null;
        try {
            ActivityManager a = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (a != null) am = a.getDeviceConfigurationInfo().getGlEsVersion();
        } catch (Throwable ignored) { }
        out.row("Версия по ActivityManager", am);
        String ver = g.ok ? parseGlEsVersion(g.version) : null;
        out.row("Версия контекста", ver != null ? ver : (g.ok && g.contextVersion > 0 ? String.valueOf(g.contextVersion) : null));
        out.row("Макс. размер текстуры", g.ok && g.maxTex > 0 ? g.maxTex + " px" : null);
        out.row("Макс. размер renderbuffer", g.ok && g.maxRb > 0 ? g.maxRb + " px" : null);
        out.row("Макс. атрибутов вершин", g.ok && g.maxVertexAttribs > 0 ? g.maxVertexAttribs : null);
        out.row("Макс. vertex uniform (векторов)", g.ok && g.maxVertexUniform > 0 ? g.maxVertexUniform : null);
        out.row("Макс. fragment uniform (векторов)", g.ok && g.maxFragUniform > 0 ? g.maxFragUniform : null);
        out.row("Макс. texture image units", g.ok && g.maxTexUnits > 0 ? g.maxTexUnits : null);
        out.row("Макс. varying (векторов)", g.ok && g.maxVarying > 0 ? g.maxVarying : null);

        out.header("Vulkan и API");
        Integer vk = null, vkLevel = null, vkCompute = null, deqpGl = null, deqpVk = null;
        boolean aep = false;
        try {
            FeatureInfo[] fs = ctx.getPackageManager().getSystemAvailableFeatures();
            if (fs != null) for (FeatureInfo f : fs) {
                if (f.name == null) continue;
                switch (f.name) {
                    case "android.hardware.vulkan.version": vk = f.version; break;
                    case "android.hardware.vulkan.level": vkLevel = f.version; break;
                    case "android.hardware.vulkan.compute": vkCompute = f.version; break;
                    case "android.hardware.opengles.aep": aep = true; break;
                    case "android.software.opengles.deqp.level": deqpGl = f.version; break;
                    case "android.software.vulkan.deqp.level": deqpVk = f.version; break;
                    default: break;
                }
            }
        } catch (Throwable ignored) { }
        out.row("Vulkan", vk == null ? "Не поддерживается / нет данных" : decodeVulkan(vk));
        out.row("Vulkan: уровень", vkLevel);
        out.row("Vulkan compute (уровень)", vkCompute);
        out.row("OpenGL ES AEP", aep ? "Да" : "Нет");
        out.row("deqp (OpenGL ES)", deqpGl == null ? null : decodeDeqp(deqpGl));
        out.row("deqp (Vulkan)", deqpVk == null ? null : decodeDeqp(deqpVk));
        StringBuilder apis = new StringBuilder();
        if (ver != null || am != null) apis.append("OpenGL ES ").append(ver != null ? ver : am);
        if (vk != null) { if (apis.length() > 0) apis.append(", "); apis.append("Vulkan ").append(((vk >>> 22) + "." + ((vk >> 12) & 0x3ff))); }
        out.row("Поддерживаемые API", apis.length() == 0 ? null : apis.toString());

        buildLive(out, ref);
        buildExtensions(out, g);
    }

    private static String firstReadable(String... paths) {
        for (String p : paths) {
            String s = Util.read(p);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private void buildLive(Section out, String[] ref) {
        out.header("Частота и загрузка");
        Double cur = null;
        String src = null;
        String[] curPaths = {"/sys/kernel/ged/hal/current_freqency", "/proc/gpufreqv2/gpufreq_status",
                "/proc/gpufreq/gpufreq_var_dump", "/sys/class/misc/mali0/device/cur_freq",
                "/sys/kernel/gpu/gpu_clock"};
        for (String p : curPaths) {
            Double v = parseCurFreqMhz(Util.read(p));
            if (v != null) { cur = v; src = p; break; }
        }
        List<String> dev = new ArrayList<>();
        for (String d : Util.list("/sys/class/devfreq")) if (d.toLowerCase().contains("mali") || d.toLowerCase().contains("gpu")) dev.add(d);
        if (cur == null) {
            for (String d : dev) {
                Double v = parseCurFreqMhz(Util.read("/sys/class/devfreq/" + d + "/cur_freq"));
                if (v != null) { cur = v; src = "devfreq/" + d; break; }
            }
        }
        Double min = null, max = null;
        List<Double> opp = new ArrayList<>();
        String[] oppPaths = {"/proc/gpufreqv2/gpu_working_opp_table", "/proc/gpufreqv2/stack_working_opp_table",
                "/proc/gpufreq/gpufreq_opp_dump", "/sys/kernel/gpu/gpu_freq_table"};
        for (String p : oppPaths) {
            List<Double> t = parseOppTable(Util.read(p));
            if (!t.isEmpty()) { opp = t; break; }
        }
        for (String d : dev) {
            if (!opp.isEmpty()) break;
            opp = parseOppTable(Util.read("/sys/class/devfreq/" + d + "/available_frequencies"));
            Double mn = parseCurFreqMhz(Util.read("/sys/class/devfreq/" + d + "/min_freq"));
            Double mx = parseCurFreqMhz(Util.read("/sys/class/devfreq/" + d + "/max_freq"));
            if (mn != null) min = mn;
            if (mx != null) max = mx;
        }
        if (!opp.isEmpty()) {
            double lo = Double.MAX_VALUE, hi = 0;
            for (double v : opp) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (min == null) min = lo;
            if (max == null) max = hi;
        }
        Double gmax = parseCurFreqMhz(Util.read("/sys/kernel/gpu/gpu_max_clock"));
        Double gmin = parseCurFreqMhz(Util.read("/sys/kernel/gpu/gpu_min_clock"));
        if (gmax != null) max = gmax;
        if (gmin != null) min = gmin;

        Double load = null;
        String lp = firstReadable("/sys/kernel/ged/hal/gpu_utilization", "/sys/class/misc/mali0/device/utilization",
                "/sys/kernel/gpu/gpu_busy");
        if (lp != null) load = parseUtilization(lp);
        if (load == null) for (String d : dev) {
            load = parseUtilization(Util.read("/sys/class/devfreq/" + d + "/load"));
            if (load != null) break;
        }

        if (cur != null) {
            out.row("Текущая частота", mhz(cur), Section.INFO);
            out.row("Источник", src);
            if (max != null && max > 0) out.bar("Частота / макс.", mhz(cur) + " из " + mhz(max), cur / max * 100, Section.level(cur / max * 100, 80, 95));
        } else {
            out.row("Текущая частота", "Нет доступа (закрыто прошивкой)", Section.MUTED);
        }
        out.row("Мин. частота", min == null ? null : mhz(min));
        out.row("Макс. частота (по данным ядра)", max == null ? null : mhz(max));
        if (load != null) out.bar("Загрузка GPU", Util.f("%.0f%%", load), load, Section.level(load, 70, 90));
        else out.row("Загрузка GPU", null);
        if (!opp.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < opp.size(); i++) rows.add(new String[]{String.valueOf(i), mhz(opp.get(i))});
            out.table(new String[]{"OPP", "Частота"}, rows, new float[]{1, 2});
        }
        if (ref != null) {
            out.row("Макс. частота (по спецификации)", ref[1] + " МГц (" + ref[4] + ")");
        } else {
            out.row("Макс. частота (по спецификации)", null);
        }
        if (cur == null && load == null && opp.isEmpty()) {
            out.note("Прошивка закрывает узлы /sys/kernel/ged, /proc/gpufreq* политикой SELinux для обычных приложений, "
                    + "публичного API для частоты и загрузки GPU в Android нет. Показаны данные из OpenGL/Vulkan и справочные значения.");
        }
    }

    private void buildExtensions(Section out, GlInfo g) {
        out.header("Расширения OpenGL ES");
        if (!g.ok || g.extensions == null || g.extensions.trim().isEmpty()) {
            out.row("Количество", null);
            return;
        }
        String[] ex = g.extensions.trim().split("\\s+");
        Arrays.sort(ex);
        out.row("Количество", ex.length);
        StringBuilder sb = new StringBuilder();
        int n = Math.min(60, ex.length);
        for (int i = 0; i < n; i++) sb.append(ex[i]).append('\n');
        if (ex.length > n) sb.append("… и ещё ").append(ex.length - n);
        out.note(sb.toString().trim());
    }
}
