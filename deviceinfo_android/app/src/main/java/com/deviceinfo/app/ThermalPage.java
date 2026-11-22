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

    static final Series BAT_TEMP = new Series(300, 120_000);
    static final Series HEAD = new Series(300, 120_000);
    private static volatile long batAt = -1, headSampleAt = -1;

    static void recordBat(double c) {
        if (Double.isNaN(c)) return;
        long t = SystemClock.elapsedRealtime();
        if (batAt >= 0 && t - batAt < 800) return;
        batAt = t; BAT_TEMP.add(t, c);
    }

    static void recordHead(double h) {
        if (Double.isNaN(h)) return;
        long t = SystemClock.elapsedRealtime();
        if (headSampleAt >= 0 && t - headSampleAt < 800) return;
        headSampleAt = t; HEAD.add(t, h);
    }

    static double batteryTemp(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t != Integer.MIN_VALUE) return t / 10.0;
            }
        } catch (Throwable ignored) { }
        return Double.NaN;
    }

    /**
     * Статус по прогнозу шкалы: наивысший статус, чей порог <= headroom (порог "Сильный" = 1.00,
     * если система его не прислала); 0 = "Норма".
     */
    static int forecastStatus(float h, java.util.Map<Integer, Float> th) {
        if (Float.isNaN(h)) return 0;
        java.util.TreeMap<Integer, Float> m = new java.util.TreeMap<>();
        if (th != null) for (java.util.Map.Entry<Integer, Float> e : th.entrySet())
            if (e.getKey() != null && e.getValue() != null && !Float.isNaN(e.getValue())) m.put(e.getKey(), e.getValue());
        if (!m.containsKey(3)) m.put(3, 1.0f);
        int best = 0;
        for (java.util.Map.Entry<Integer, Float> e : m.entrySet())
            if (e.getKey() > 0 && e.getValue() <= h + 1e-6f && e.getKey() > best) best = e.getKey();
        return best;
    }

    static String statusName(int st) {
        return st >= 0 && st < STATUS.length ? STATUS[st] : "неизвестно (" + st + ")";
    }

    static final String FORECAST_NOTE = "Шкала — прогноз Android на 10 секунд вперёд, а прошивка Honor управляет "
            + "нагревом сама и может не повышать статус.";

    /** Первое число из содержимого файла температуры -> °C или NaN. */
    static double parseTempText(String content) {
        if (content == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(content);
        if (!m.find()) return Double.NaN;
        try { return normalizeTemp(Long.parseLong(m.group())); } catch (Exception e) { return Double.NaN; }
    }

    /** Расширенные источники температуры (без рекурсивных обходов). Строки: {путь, тип, результат}. */
    static List<String[]> probeExtra(double[] cpuOut, String[] cpuName, java.util.Set<String> seenZones) {
        List<String[]> rows = new ArrayList<>();
        String vb = "/sys/devices/virtual/thermal";
        for (String z : Util.list(vb)) {
            if (!z.startsWith("thermal_zone") || seenZones.contains(z)) continue;
            String type = Util.read(vb + "/" + z + "/type");
            double c = parseTempText(Util.read(vb + "/" + z + "/temp"));
            rows.add(new String[]{vb + "/" + z + "/temp", type == null ? "—" : type, Double.isNaN(c) ? "нет доступа" : fmt(c)});
            offerCpu(cpuOut, cpuName, type, c);
        }
        if (Util.list(vb).length == 0)
            rows.add(new String[]{vb + "/thermal_zone*/temp", "—", "нет доступа"});
        String[] mt = Util.list("/proc/mtktz");
        if (mt.length == 0) rows.add(new String[]{"/proc/mtktz/mtktscpu", "—", "нет доступа"});
        for (int i = 0; i < mt.length && i < 20; i++) {
            double c = parseTempText(Util.read("/proc/mtktz/" + mt[i]));
            rows.add(new String[]{"/proc/mtktz/" + mt[i], mt[i], Double.isNaN(c) ? "нет доступа" : fmt(c)});
            offerCpu(cpuOut, cpuName, mt[i], c);
        }
        int n = 0;
        for (String d : Util.list("/sys/devices/platform")) {
            String ld = d.toLowerCase(Locale.ROOT);
            if (!(ld.contains("thermal") || ld.contains("tsens"))) continue;
            if (n++ >= 10) break;
            boolean any = false;
            for (String f : Util.list("/sys/devices/platform/" + d)) {
                if (!f.startsWith("temp") && !f.startsWith("cpu")) continue;
                double c = parseTempText(Util.read("/sys/devices/platform/" + d + "/" + f));
                if (Double.isNaN(c)) continue;
                any = true;
                rows.add(new String[]{"/sys/devices/platform/" + d + "/" + f, d, fmt(c)});
                offerCpu(cpuOut, cpuName, d + "/" + f, c);
            }
            if (!any) rows.add(new String[]{"/sys/devices/platform/" + d, d, "нет доступа"});
        }
        return rows;
    }

    private static void offerCpu(double[] cpuOut, String[] cpuName, String type, double c) {
        if (Double.isNaN(c) || !isCpuType(type)) return;
        if (Double.isNaN(cpuOut[0]) || c > cpuOut[0]) { cpuOut[0] = c; cpuName[0] = type; }
    }

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
        List<String[]> diag = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String z : Util.list("/sys/class/thermal")) {
            if (!z.startsWith("thermal_zone")) continue;
            seen.add(z);
            String type = Util.read("/sys/class/thermal/" + z + "/type");
            Long raw = Util.readLong("/sys/class/thermal/" + z + "/temp");
            double c = raw == null ? Double.NaN : normalizeTemp(raw);
            diag.add(new String[]{"/sys/class/thermal/" + z + "/temp", type == null ? "—" : type,
                    raw == null ? "нет доступа" : Double.isNaN(c) ? "не число (" + raw + ")" : fmt(c)});
            if (raw == null) continue;
            if (Double.isNaN(c)) continue;
            valid++;
            rows.add(new String[]{z.replace("thermal_zone", "zone"), type == null ? "—" : type, fmt(c)});
            if (type == null) continue;
            if (isCpuType(type) && (Double.isNaN(cpu) || c > cpu)) { cpu = c; cpuName = type; }
            if (isGpuType(type) && (Double.isNaN(gpu) || c > gpu)) { gpu = c; gpuName = type; }
            if (isBatteryType(type) && (Double.isNaN(bat) || c > bat)) bat = c;
        }

        // дополнительные источники
        double[] cpuBox = {cpu};
        String[] cpuNameBox = {cpuName};
        diag.addAll(probeExtra(cpuBox, cpuNameBox, seen));
        cpu = cpuBox[0]; cpuName = cpuNameBox[0];

        // батарея из интента
        double batIntent = batteryTemp(ctx);
        double batShown = !Double.isNaN(batIntent) ? batIntent : bat;
        recordBat(batShown);

        out.header("Сводка", Double.isNaN(batShown) ? null : fmt(batShown), Section.NORMAL);
        if (!Double.isNaN(cpu)) out.row("Процессор", fmt(cpu) + " (" + cpuName + ")", Section.temp(cpu));
        else out.row("Процессор", "закрыто прошивкой (другие приложения обычно показывают температуру батареи)",
                Section.MUTED);
        if (!Double.isNaN(gpu)) out.row("GPU", fmt(gpu) + " (" + gpuName + ")", Section.temp(gpu));
        if (!Double.isNaN(batIntent)) out.row("Батарея", fmt(batIntent), Section.temp(batIntent));
        else if (!Double.isNaN(bat)) out.row("Батарея", fmt(bat) + " (датчик зоны)", Section.temp(bat));
        else out.row("Батарея", "нет данных", Section.MUTED);
        out.chart("Температура батареи", Double.isNaN(batShown) ? "—" : fmt(batShown),
                BAT_TEMP.values(SystemClock.elapsedRealtime()), Double.NaN, Double.NaN, " °C", Section.NORMAL);

        // --- нагрев по оценке Android ---
        out.header("Нагрев телефона (оценка Android)");
        int curStatus = -1;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 29) {
                curStatus = pm.getCurrentThermalStatus();
                out.row("Статус сейчас (система)", statusName(curStatus),
                        curStatus <= 0 ? Section.OK : curStatus <= 2 ? Section.WARN : Section.CRIT);
            } else out.row("Статус сейчас (система)", "недоступно (нужен Android 10+)", Section.MUTED);
            if (pm != null && Build.VERSION.SDK_INT >= 30) {
                float h = headroom(pm);
                if (Float.isNaN(h)) out.row("Шкала нагрева", "нет данных (прошивка не отдаёт)", Section.MUTED);
                else {
                    java.util.Map<Integer, Float> th = null;
                    if (Build.VERSION.SDK_INT >= 35) {
                        try { th = pm.getThermalHeadroomThresholds(); } catch (Throwable ignored) { }
                    }
                    recordHead(h);
                    int fc = forecastStatus(h, th);
                    out.row("По прогнозу шкалы", statusName(fc), fc <= 0 ? Section.OK : fc <= 2 ? Section.WARN : Section.CRIT);
                    if (curStatus >= 0 && curStatus != fc) out.note(FORECAST_NOTE);
                    out.chart("Шкала нагрева", Util.f("%.2f", h), HEAD.values(SystemClock.elapsedRealtime()), 0, 1.2, "",
                            h >= 1.0f ? Section.CRIT : Section.NORMAL);
                    float light = lightThreshold(th);
                    // AOSP ThermalManagerService: headroom = (T − T_severe + 30) / 30,
                    // T — прогноз температуры корпуса (SKIN) через 10 с, T_severe — порог «Сильный».
                    out.bar("Шкала нагрева", Util.f("%.2f из 1.00", h), Math.min(100, h * 100.0),
                            h >= 1.0f ? Section.CRIT : h >= light ? Section.WARN : Section.OK);
                    if (light < 1.0f)
                        out.row("До статуса «Лёгкий»", toLimitText(h, light),
                                h >= light ? Section.WARN : (light - h) * 30 > 3 ? Section.OK : Section.WARN);
                    out.row("До статуса «Сильный»", toLimitText(h, 1.0f),
                            h >= 1.0f ? Section.CRIT : (1.0f - h) * 30 > 3 ? Section.OK : Section.WARN);
                    List<String[]> thRows = thresholdRows(th);
                    if (!thRows.isEmpty())
                        out.table(new String[]{"Статус", "С отметки шкалы"}, thRows, new float[]{0.6f, 0.4f});
                    out.note("Android сам оценивает нагрев корпуса телефона (по своим датчикам, приложениям они "
                            + "закрыты) с прогнозом на 10 секунд вперёд. 0 — холодный, 1.00 — статус «Сильный», "
                            + "каждые 0.10 ≈ 3 °C. По этим статусам игры и приложения сами снижают нагрузку. "
                            + "Частоты процессора прошивка ограничивает по своим правилам — фактическое "
                            + "ограничение показано ниже.");
                }
            }
        } catch (Throwable t) {
            out.row("Статус нагрева", "недоступно", Section.MUTED);
        }

        // --- фактическое ограничение частоты ---
        List<long[]> pol = cpuLimits();
        out.header("Ограничение частоты процессора (троттлинг)");
        if (pol.isEmpty()) {
            out.row("Состояние", "нет доступа к лимитам частоты", Section.MUTED);
        } else {
            long top = 0;
            for (long[] x : pol) top = Math.max(top, x[2]);
            boolean any = false, closed = false;
            long nowMs = SystemClock.elapsedRealtime();
            sampleFreqs(pol, nowMs);
            for (long[] x : pol) {
                String cores = x[0] == x[1] ? "Ядро " + x[0] : "Ядра " + x[0] + "–" + x[1];
                cores += x[2] == top && pol.size() > 1 ? " (большие)" : (pol.size() > 1 ? " (малые)" : "");
                if (x[3] > 0 && x[3] < x[2]) {
                    any = true;
                    out.row(cores, "ограничено до " + Util.khz(x[3]) + " из " + Util.khz(x[2])
                            + Util.f(" (−%.0f %%)", 100.0 * (x[2] - x[3]) / x[2]), Section.WARN);
                } else if (x[3] > 0) {
                    out.row(cores, "без ограничений (до " + Util.khz(x[2]) + ")", Section.OK);
                } else {
                    closed = true;
                    long cur = curFreq(x[0], x[1]);
                    long peak = peakFreq(x[0], nowMs);
                    if (peak <= 0) {
                        out.row(cores, "лимит закрыт прошивкой (макс. " + Util.khz(x[2]) + ")", Section.MUTED);
                    } else {
                        boolean full = peak >= x[2] * 0.97;
                        out.row(cores, (cur > 0 ? "сейчас " + Util.khz(cur) + " · " : "") + "пик за 2 мин "
                                + Util.khz(peak) + " из " + Util.khz(x[2]) + (full ? " — не ограничено" : ""),
                                full ? Section.OK : Section.MUTED);
                    }
                }
            }
            if (any) out.note("Прошивка сейчас снижает максимальную частоту: из-за нагрева или режима энергосбережения.");
            else if (closed) out.note("Прошивка Honor закрывает сам лимит частоты, поэтому показан пик частоты за последние "
                    + "2 минуты. Если ядра доходят до максимума — ограничения нет. Без нагрузки они и так работают "
                    + "на низкой частоте, это нормально. Проверка: запустите игру или тест, вернитесь сюда — если под "
                    + "нагрузкой пик заметно ниже максимума, прошивка снижает частоту (это и есть троттлинг).");
            else out.note("Это и есть настоящий троттлинг: когда телефон перегреется, здесь появится «ограничено до …».");
        }

        // --- диагностика источников ---
        out.header("Источники температуры (диагностика)");
        if (diag.isEmpty()) out.note("Ни один путь не найден.");
        else out.table(new String[]{"Путь", "Тип", "Результат"}, diag, new float[]{0.5f, 0.25f, 0.25f});

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

    /** По каждой политике cpufreq: {первое ядро, последнее ядро, макс. кГц, текущий лимит кГц или 0}. */
    static List<long[]> cpuLimits() {
        List<long[]> res = new ArrayList<>();
        String base = "/sys/devices/system/cpu/cpufreq";
        List<String> names = new ArrayList<>(java.util.Arrays.asList(Util.list(base)));
        java.util.Collections.sort(names);
        for (String pn : names) {
            if (!pn.startsWith("policy")) continue;
            String d = base + "/" + pn;
            Long max = Util.readLong(d + "/cpuinfo_max_freq");
            if (max == null || max <= 0) continue;
            Long lim = Util.readLong(d + "/scaling_max_freq");
            long first, last;
            String rel = Util.read(d + "/related_cpus");
            try {
                String[] t = rel == null ? new String[0] : rel.trim().split("\\s+");
                first = Long.parseLong(t.length > 0 ? t[0] : pn.substring(6));
                last = Long.parseLong(t.length > 0 ? t[t.length - 1] : pn.substring(6));
            } catch (Exception e) { continue; }
            res.add(new long[]{first, last, max, lim == null ? 0 : lim});
        }
        return res;
    }

    static final long PEAK_WINDOW_MS = 120_000;
    /** Отсчёты частоты по кластерам: первое ядро -> [{время, кГц}]. */
    private static final java.util.Map<Long, java.util.ArrayDeque<long[]>> FREQ = new java.util.HashMap<>();

    /** Наибольшая текущая частота среди ядер first..last (кГц) или 0. */
    static long curFreq(long first, long last) {
        long best = 0;
        for (long c = first; c <= last; c++) {
            Long f = Util.readLong("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq");
            if (f != null && f > best) best = f;
        }
        return best;
    }

    static synchronized void sampleFreqs(List<long[]> pol, long nowMs) {
        for (long[] x : pol) {
            long f = curFreq(x[0], x[1]);
            if (f > 0) addFreq(x[0], nowMs, f);
        }
    }

    static synchronized void addFreq(long cluster, long nowMs, long khz) {
        java.util.ArrayDeque<long[]> q = FREQ.get(cluster);
        if (q == null) { q = new java.util.ArrayDeque<>(); FREQ.put(cluster, q); }
        q.addLast(new long[]{nowMs, khz});
        while (!q.isEmpty() && (q.peekFirst()[0] < nowMs - PEAK_WINDOW_MS || q.peekFirst()[0] > nowMs)) q.pollFirst();
    }

    /** Пик частоты кластера за последние 2 минуты (кГц) или 0. */
    static synchronized long peakFreq(long cluster, long nowMs) {
        java.util.ArrayDeque<long[]> q = FREQ.get(cluster);
        long best = 0;
        if (q != null) for (long[] v : q) if (v[0] >= nowMs - PEAK_WINDOW_MS && v[0] <= nowMs) best = Math.max(best, v[1]);
        return best;
    }

    static synchronized void resetFreqs() { FREQ.clear(); }

    /** Фоновый отсчёт раз в 1.5 с, чтобы пик частоты копился и когда вкладка не открыта. */
    @Override public void tick(Context ctx) {
        try { sampleFreqs(cpuLimits(), SystemClock.elapsedRealtime()); } catch (Throwable ignored) { }
        try { recordBat(batteryTemp(ctx)); } catch (Throwable ignored) { }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 30) recordHead(headroom(pm));
        } catch (Throwable ignored) { }
    }

    /** Порог статуса «Лёгкий» из getThermalHeadroomThresholds; если его нет — 1.0 (порог «Сильный»). */
    static float lightThreshold(java.util.Map<Integer, Float> th) {
        if (th != null) {
            Float v = th.get(1);
            if (v != null && !Float.isNaN(v) && v > 0 && v <= 1.0f) return v;
        }
        return 1.0f;
    }

    /** Сколько °C осталось до порога (1 единица headroom = 30 °C). */
    static String toLimitText(float h, float limit) {
        double d = (limit - h) * 30.0;
        if (d > 0.05) return Util.f("≈ %.1f °C (прогноз на 10 с)", d);
        if (d > -0.05) return "на пороге";
        return Util.f("порог превышен на ≈ %.1f °C", -d);
    }

    /** Таблица порогов; «Сильный» по определению равен 1.00, система его часто не присылает. */
    static List<String[]> thresholdRows(java.util.Map<Integer, Float> th) {
        List<String[]> rows = new ArrayList<>();
        if (th == null || th.isEmpty()) return rows;
        java.util.TreeMap<Integer, Float> m = new java.util.TreeMap<>(th);
        if (!m.containsKey(3)) m.put(3, 1.0f);
        for (java.util.Map.Entry<Integer, Float> e : m.entrySet()) {
            int k = e.getKey();
            if (k <= 0) continue;
            Float v = e.getValue();
            if (v == null || Float.isNaN(v)) continue;
            rows.add(new String[]{k < STATUS.length ? STATUS[k] : "" + k, Util.f("%.2f", v)});
        }
        return rows;
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
