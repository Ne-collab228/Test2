package com.deviceinfo.app;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CpuPage implements Page {
    @Override public String title() { return "Процессор"; }

    static final String[] DIAG_KEYS = {"ro.soc.model", "ro.soc.manufacturer", "ro.board.platform", "ro.hardware",
            "ro.vendor.mediatek.platform", "ro.vendor.soc.model.external_name", "ro.vendor.soc.model.part_name",
            "ro.config.cpu_info_display", "ro.product.board", "ro.product.model", "ro.product.device",
            "ro.product.first_api_level"};

    // ---- состояние для расчёта загрузки ----
    private static final Map<Integer, long[]> prevStat = new HashMap<>();
    private static final Map<Integer, Double> lastStatLoad = new HashMap<>();
    private static final Map<Integer, Long> prevIdle = new HashMap<>();
    private static long prevWallNs = -1;
    private static LoadResult idleCache;
    /** История общей загрузки CPU для графика. */
    static final Series LOAD = new Series(300, 120_000);
    private static long loadAt = -1;
    private static LoadResult loadCache;

    /** Фоновый замер: общая загрузка пишется в историю даже когда вкладка закрыта. */
    @Override public void tick(Context ctx) {
        try { sampleTick(); } catch (Throwable ignored) { }
    }

    private static synchronized void sampleTick() {
        CpuInfo.Proc proc = CpuInfo.parseCpuinfo();
        List<Integer> ids = CpuInfo.cpuIds(proc);
        Set<Integer> online = CpuInfo.onlineSet(ids);
        Map<Integer, CpuInfo.Cluster> clusterOf = new HashMap<>();
        for (CpuInfo.Cluster c : CpuInfo.clusters(ids, proc)) for (int id : c.cpus) clusterOf.put(id, c);
        computeLoad(ids, online, clusterOf);
    }

    static final class LoadResult {
        final Map<Integer, Double> loads = new HashMap<>(); // -1 = общая
        String method = "";
        boolean measuring;
    }

    /** Сброс накопленных значений (для тестов). */
    public static synchronized void resetState() {
        prevStat.clear(); lastStatLoad.clear(); prevIdle.clear(); prevWallNs = -1; idleCache = null;
        loadAt = -1; loadCache = null; LOAD.clear();
    }

    @Override public void build(Context ctx, Section out) {
        try { buildImpl(out); } catch (Throwable t) {
            out.header("Процессор");
            out.note("Ошибка: " + t);
        }
    }

    private static synchronized void buildImpl(Section out) {
        CpuInfo.Proc proc = CpuInfo.parseCpuinfo();
        List<Integer> ids = CpuInfo.cpuIds(proc);
        Set<Integer> online = CpuInfo.onlineSet(ids);
        List<CpuInfo.Cluster> clusters = CpuInfo.clusters(ids, proc);

        // part по ядру (с подстановкой из кластера)
        Map<Integer, Integer> partOf = new HashMap<>();
        Map<Integer, CpuInfo.Cluster> clusterOf = new HashMap<>();
        for (CpuInfo.Cluster c : clusters) for (int id : c.cpus) clusterOf.put(id, c);
        for (int id : ids) {
            Integer p = proc.part.get(id);
            if (p == null) { CpuInfo.Cluster c = clusterOf.get(id); if (c != null && c.part >= 0) p = c.part; }
            if (p != null) partOf.put(id, p);
        }

        // --- SoC ---
        Map<String, String> props = new LinkedHashMap<>();
        for (String k : DIAG_KEYS) { String v = Util.prop(k); if (v != null) props.put(k, v); }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String sm = Build.SOC_MODEL, mf = Build.SOC_MANUFACTURER;
                if (sm != null && !sm.isEmpty() && !"unknown".equalsIgnoreCase(sm) && !props.containsKey("ro.soc.model"))
                    props.put("ro.soc.model", sm);
                if (mf != null && !mf.isEmpty() && !"unknown".equalsIgnoreCase(mf) && !props.containsKey("ro.soc.manufacturer"))
                    props.put("ro.soc.manufacturer", mf);
            }
        } catch (Throwable ignored) { }
        long little = 0, big = 0;
        for (CpuInfo.Cluster c : clusters) {
            if (c.max == null || c.max <= 0) continue;
            if (little == 0 || c.max < little) little = c.max;
            if (c.max > big) big = c.max;
        }
        String model = Util.firstNonEmpty(Util.prop("ro.product.model"), Build.MODEL);
        String device = Util.firstNonEmpty(Util.prop("ro.product.device"), Build.DEVICE);
        String board = Util.firstNonEmpty(Util.prop("ro.product.board"), Build.BOARD);
        SocDatabase.SocResult soc = SocDatabase.detect(props, model, device, board, little, big);

        out.header("Чип (SoC)");
        int cc = SocDatabase.HIGH.equals(soc.confidence) ? Section.OK
                : SocDatabase.MED.equals(soc.confidence) ? Section.WARN : Section.MUTED;
        out.row("SoC", soc.name);
        out.row("Определено", soc.method);
        out.row("Уверенность", soc.confidence, cc);
        String mfr = props.get("ro.soc.manufacturer");
        if (mfr != null) out.row("Производитель", mfr);

        // --- Ядра ---
        out.header("Ядра");
        // порядок для сводки: сначала самые быстрые
        List<Integer> order = new ArrayList<>(ids);
        final Map<Integer, CpuInfo.Cluster> co = clusterOf;
        java.util.Collections.sort(order, (a, b) -> {
            long ma = co.get(a) != null && co.get(a).max != null ? co.get(a).max : 0;
            long mb = co.get(b) != null && co.get(b).max != null ? co.get(b).max : 0;
            return ma != mb ? Long.compare(mb, ma) : a - b;
        });
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (int id : order) {
            Integer p = partOf.get(id);
            Integer im = proc.impl.get(id);
            String n = p == null ? "неизвестно" : CpuInfo.coreName(im == null ? proc.firstImpl : im, p);
            kinds.put(n, kinds.containsKey(n) ? kinds.get(n) + 1 : 1);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.getValue()).append(" × ").append(e.getKey());
        }
        out.row("Ядер", ids.size() + " (" + sb + ")");
        out.row("Онлайн", online.size() + " из " + ids.size());

        int ci = 0;
        for (CpuInfo.Cluster c : clusters) {
            ci++;
            String nm = c.part >= 0 ? CpuInfo.coreName(c.impl, c.part) : "ядра";
            out.row("Кластер " + ci, c.cpus.size() + " × " + nm + " (CPU " + CpuInfo.rangeText(c.cpus) + ")");
            String range = null;
            if (c.min != null && c.max != null) range = Util.khz(c.min) + " – " + Util.khz(c.max);
            else if (c.max != null) range = "до " + Util.khz(c.max);
            if (range != null) out.row("  Частоты", range + (c.nFreq > 0 ? " (" + c.nFreq + " ступ.)" : ""));
            if (c.governor != null && !c.governor.isEmpty()) out.row("  Governor", c.governor);
            if (c.scalingMax != null && c.max != null && c.scalingMax < c.max)
                out.row("  Лимит", "ограничено до " + Util.khz(c.scalingMax), Section.WARN);
        }
        if (clusters.isEmpty()) out.note("Каталог cpufreq недоступен — кластеры не определены.");

        // --- Нагрузка ---
        LoadResult lr = computeLoad(ids, online, clusterOf);
        Double all = lr.loads.get(-1);
        out.header("Загрузка по ядрам", all == null ? null : Util.f("%.0f %%", all), Section.NORMAL);
        if (all != null)
            out.chart("Загрузка CPU", Util.f("%.0f %%", all), LOAD.values(SystemClock.elapsedRealtime()), 0, 100, "%",
                    Section.NORMAL);
        if (all != null) out.bar("Общая", Util.f("%.0f%%", all), all, Section.level(all, 60, 85));
        else out.row("Общая", "измеряется…", Section.MUTED);
        for (int id : ids) {
            String key = "CPU" + id;
            Integer p = partOf.get(id);
            if (p != null) key += " · " + CpuInfo.coreName(proc.impl.containsKey(id) ? proc.impl.get(id) : proc.firstImpl, p);
            if (!online.contains(id)) { out.bar(key, "офлайн", 0, Section.MUTED); continue; }
            Long cur = Util.readLong(CpuInfo.CPU + "/cpu" + id + "/cpufreq/scaling_cur_freq");
            if (cur == null) cur = Util.readLong(CpuInfo.CPU + "/cpu" + id + "/cpufreq/cpuinfo_cur_freq");
            if (cur == null) { CpuInfo.Cluster c = clusterOf.get(id); if (c != null) { String s = CpuInfo.clusterRead(c, "scaling_cur_freq"); if (s != null) try { cur = Long.parseLong(s.trim()); } catch (Exception ignored) { } } }
            String fq = Util.khz(cur);
            Double l = lr.loads.get(id);
            String txt = (fq == null ? "—" : fq) + " · " + (l == null ? "измеряется…" : Util.f("%.0f%%", l));
            out.bar(key, txt, l == null ? 0 : l, l == null ? Section.MUTED : Section.level(l, 60, 85));
        }
        out.note("Метод: " + lr.method);

        // --- Прочее ---
        out.header("Архитектура");
        out.row("Архитектура", System.getProperty("os.arch"));
        String[] abis = Build.SUPPORTED_ABIS;
        out.row("ABI", abis == null ? null : String.join(", ", abis));
        boolean has64 = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        out.row("Разрядность", has64 ? "64-бит (процесс: " + (android.os.Process.is64Bit() ? "64" : "32") + "-бит)" : "32-бит");
        if (proc.firstImpl >= 0) out.row("Implementer", CpuInfo.implementerName(proc.firstImpl) + String.format(" (0x%x)", proc.firstImpl));
        if (proc.revision != null) out.row("Ревизия", proc.revision);
        if (proc.bogo != null) out.row("BogoMIPS", proc.bogo);
        if (proc.hardware != null) out.row("Hardware (cpuinfo)", proc.hardware);
        out.row("Ядро ОС", System.getProperty("os.version"));
        if (proc.features != null) out.row("Возможности CPU", proc.features);

        // --- Диагностика ---
        out.header("Диагностика SoC");
        List<String[]> rows = new ArrayList<>();
        for (String k : DIAG_KEYS) {
            String v = Util.prop(k);
            rows.add(new String[]{k, v == null ? "—" : v});
        }
        out.table(new String[]{"Свойство", "Значение"}, rows, new float[]{0.55f, 0.45f});
    }

    // ================= загрузка =================

    /**
     * Общий расчёт загрузки для build() и tick(): не чаще раза в ~700 мс (иначе короткие интервалы
     * портят дельты); результат пишется в историю графика.
     */
    private static LoadResult computeLoad(List<Integer> ids, Set<Integer> online, Map<Integer, CpuInfo.Cluster> clusterOf) {
        long t = SystemClock.elapsedRealtime();
        if (loadCache != null && loadAt >= 0 && t - loadAt < 700 && t >= loadAt) return loadCache;
        LoadResult r = computeLoadRaw(ids, online, clusterOf);
        loadAt = t; loadCache = r;
        Double all = r.loads.get(-1);
        if (all != null) LOAD.add(t, all);
        return r;
    }

    private static LoadResult computeLoadRaw(List<Integer> ids, Set<Integer> online, Map<Integer, CpuInfo.Cluster> clusterOf) {
        LoadResult r = statLoad(ids);
        if (r != null) return r;
        r = idleLoad(ids, online);
        if (r != null) return r;
        r = new LoadResult();
        r.method = "оценка по частоте (scaling_cur_freq / cpuinfo_max_freq)";
        double sum = 0; int n = 0;
        for (int id : ids) {
            if (!online.contains(id)) continue;
            Long cur = Util.readLong(CpuInfo.CPU + "/cpu" + id + "/cpufreq/scaling_cur_freq");
            Long max = Util.readLong(CpuInfo.CPU + "/cpu" + id + "/cpufreq/cpuinfo_max_freq");
            if (max == null) { CpuInfo.Cluster c = clusterOf.get(id); if (c != null) max = c.max; }
            if (cur == null || max == null || max <= 0) continue;
            double l = Math.min(100, 100.0 * cur / max);
            r.loads.put(id, l); sum += l; n++;
        }
        if (n > 0) r.loads.put(-1, sum / n);
        return r;
    }

    private static LoadResult statLoad(List<Integer> ids) {
        String s = Util.read("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return null;
        LoadResult r = new LoadResult();
        r.method = "/proc/stat";
        for (String line : s.split("\n")) {
            if (!line.startsWith("cpu")) continue;
            String[] f = line.trim().split("\\s+");
            int key;
            if (f[0].equals("cpu")) key = -1;
            else {
                try { key = Integer.parseInt(f[0].substring(3)); } catch (Exception e) { continue; }
            }
            long total = 0, idle = 0;
            try {
                for (int i = 1; i < f.length && i <= 8; i++) {
                    long v = Long.parseLong(f[i]);
                    total += v;
                    if (i == 4 || i == 5) idle += v;
                }
            } catch (Exception e) { continue; }
            long[] prev = prevStat.get(key);
            prevStat.put(key, new long[]{total, idle});
            if (prev != null && total > prev[0]) {
                double l = 100.0 * (1.0 - (double) (idle - prev[1]) / (total - prev[0]));
                l = Math.max(0, Math.min(100, l));
                lastStatLoad.put(key, l);
            }
            Double l = lastStatLoad.get(key);
            if (l != null) r.loads.put(key, l);
        }
        return r;
    }

    private static Long idleUs(int id) {
        String dir = CpuInfo.CPU + "/cpu" + id + "/cpuidle";
        long sum = 0; boolean any = false;
        for (String n : Util.list(dir)) {
            if (!n.matches("state\\d+")) continue;
            Long t = Util.readLong(dir + "/" + n + "/time");
            if (t != null) { sum += t; any = true; }
        }
        return any ? sum : null;
    }

    private static LoadResult idleLoad(List<Integer> ids, Set<Integer> online) {
        Map<Integer, Long> cur = new HashMap<>();
        for (int id : ids) {
            if (!online.contains(id)) continue;
            Long t = idleUs(id);
            if (t != null) cur.put(id, t);
        }
        if (cur.isEmpty()) return null;
        long now = SystemClock.elapsedRealtimeNanos();
        LoadResult r = new LoadResult();
        r.method = "cpuidle (время простоя за интервал)";
        if (prevWallNs < 0 || prevIdle.isEmpty()) {
            prevWallNs = now; prevIdle.clear(); prevIdle.putAll(cur);
            r.measuring = true;
            return r;
        }
        long dWall = now - prevWallNs;
        if (dWall < 50_000_000L) { // слишком рано — берём прошлое значение
            if (idleCache != null) return idleCache;
            r.measuring = true;
            return r;
        }
        double sum = 0; int n = 0;
        for (Map.Entry<Integer, Long> e : cur.entrySet()) {
            Long p = prevIdle.get(e.getKey());
            if (p == null) continue;
            long dIdle = e.getValue() - p;
            if (dIdle < 0) continue;
            double l = 100.0 * (1.0 - (dIdle * 1000.0) / dWall);
            l = Math.max(0, Math.min(100, l));
            r.loads.put(e.getKey(), l); sum += l; n++;
        }
        if (n > 0) r.loads.put(-1, sum / n);
        prevWallNs = now; prevIdle.clear(); prevIdle.putAll(cur);
        idleCache = r;
        return r;
    }
}
