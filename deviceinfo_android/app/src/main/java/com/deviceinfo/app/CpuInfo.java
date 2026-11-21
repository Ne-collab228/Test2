package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Чтение топологии CPU из /sys и /proc (только через Util). */
public final class CpuInfo {
    private CpuInfo() {}

    public static final String CPU = "/sys/devices/system/cpu";

    private static final Map<Integer, String> ARM = new HashMap<>();
    static {
        Object[] t = {0xd03, "Cortex-A53", 0xd04, "Cortex-A35", 0xd05, "Cortex-A55", 0xd07, "Cortex-A57",
                0xd08, "Cortex-A72", 0xd09, "Cortex-A73", 0xd0a, "Cortex-A75", 0xd0b, "Cortex-A76",
                0xd0c, "Neoverse N1", 0xd0d, "Cortex-A77", 0xd0e, "Cortex-A76AE", 0xd40, "Neoverse V1",
                0xd41, "Cortex-A78", 0xd42, "Cortex-A78AE", 0xd44, "Cortex-X1", 0xd46, "Cortex-A510",
                0xd47, "Cortex-A710", 0xd48, "Cortex-X2", 0xd49, "Neoverse N2", 0xd4b, "Cortex-A78C",
                0xd4c, "Cortex-X1C", 0xd4d, "Cortex-A715", 0xd4e, "Cortex-X3", 0xd4f, "Neoverse V2",
                0xd80, "Cortex-A520", 0xd81, "Cortex-A720", 0xd82, "Cortex-X4", 0xd84, "Neoverse V3",
                0xd85, "Cortex-X925", 0xd87, "Cortex-A725", 0xd8a, "C1-Nano", 0xd8b, "C1-Pro",
                0xd8c, "C1-Ultra", 0xd8e, "Neoverse N3", 0xd90, "C1-Premium"};
        for (int i = 0; i < t.length; i += 2) ARM.put((Integer) t[i], (String) t[i + 1]);
    }

    /** Имя ядра по implementer/part; для не-ARM — шестнадцатеричный код. */
    public static String coreName(int impl, int part) {
        if (part < 0) return "неизвестно";
        if (impl == 0x41 || impl < 0) {
            String n = ARM.get(part);
            if (n != null) return n;
        }
        return String.format("%s 0x%x", implementerName(impl), part).trim();
    }

    public static String implementerName(int impl) {
        switch (impl) {
            case 0x41: return "ARM";
            case 0x51: return "Qualcomm";
            case 0x53: return "Samsung";
            case 0x48: return "HiSilicon";
            case 0x61: return "Apple";
            case 0x4e: return "NVIDIA";
            case 0x42: return "Broadcom";
            case 0x43: return "Cavium";
            default: return impl < 0 ? "" : String.format("0x%x", impl);
        }
    }

    /** "0-3,5" или "0 1 2" -> отсортированный набор. */
    public static TreeSet<Integer> parseRanges(String s) {
        TreeSet<Integer> r = new TreeSet<>();
        if (s == null) return r;
        for (String tok : s.trim().split("[\\s,]+")) {
            if (tok.isEmpty()) continue;
            try {
                int d = tok.indexOf('-');
                if (d > 0) {
                    int a = Integer.parseInt(tok.substring(0, d)), b = Integer.parseInt(tok.substring(d + 1));
                    for (int i = a; i <= b && i < 4096; i++) r.add(i);
                } else r.add(Integer.parseInt(tok));
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /** "0–5" / "0–1, 4" */
    public static String rangeText(Iterable<Integer> ids) {
        List<Integer> l = new ArrayList<>();
        for (Integer i : ids) l.add(i);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); ) {
            int j = i;
            while (j + 1 < l.size() && l.get(j + 1) == l.get(j) + 1) j++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(l.get(i));
            if (j > i) sb.append(j == i + 1 ? ", " : "–").append(l.get(j));
            i = j + 1;
        }
        return sb.toString();
    }

    /** Данные /proc/cpuinfo. */
    public static final class Proc {
        public final Map<Integer, Integer> part = new HashMap<>(), impl = new HashMap<>();
        public String features, revision, bogo, hardware;
        public int firstImpl = -1;
    }

    public static Proc parseCpuinfo() {
        Proc p = new Proc();
        String s = Util.read("/proc/cpuinfo");
        if (s == null) return p;
        int cur = -1;
        for (String line : s.split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
            switch (k) {
                case "processor": try { cur = Integer.parseInt(v); } catch (Exception e) { cur = -1; } break;
                case "CPU part": { int x = hex(v); if (cur >= 0 && x >= 0) p.part.put(cur, x); break; }
                case "CPU implementer": {
                    int x = hex(v);
                    if (cur >= 0 && x >= 0) p.impl.put(cur, x);
                    if (p.firstImpl < 0) p.firstImpl = x;
                    break;
                }
                case "CPU revision": if (p.revision == null) p.revision = v; break;
                case "BogoMIPS": if (p.bogo == null) p.bogo = v; break;
                case "Features": if (p.features == null) p.features = v; break;
                case "Hardware": if (p.hardware == null) p.hardware = v; break;
                default: break;
            }
        }
        return p;
    }

    private static int hex(String v) {
        try { return Integer.decode(v.trim()); } catch (Exception e) { return -1; }
    }

    /** Список id ядер. */
    public static List<Integer> cpuIds(Proc proc) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (String n : Util.list(CPU)) {
            if (n.matches("cpu\\d+")) ids.add(Integer.parseInt(n.substring(3)));
        }
        if (ids.isEmpty()) ids.addAll(parseRanges(Util.read(CPU + "/possible")));
        if (ids.isEmpty() && proc != null) ids.addAll(proc.part.keySet());
        if (ids.isEmpty()) {
            int n = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < n; i++) ids.add(i);
        }
        return new ArrayList<>(ids);
    }

    public static Set<Integer> onlineSet(List<Integer> ids) {
        Set<Integer> on = new HashSet<>();
        String s = Util.read(CPU + "/online");
        if (s != null && !s.isEmpty()) { on.addAll(parseRanges(s)); return on; }
        for (int id : ids) {
            Long v = Util.readLong(CPU + "/cpu" + id + "/online");
            if (v == null || v != 0) on.add(id);
        }
        return on;
    }

    public static final class Cluster {
        public final TreeSet<Integer> cpus = new TreeSet<>();
        public String dir;
        public Long min, max, scalingMax;
        public String governor;
        public int nFreq;
        public int part = -1, impl = -1;
    }

    private static String cf(int id, String name) { return CPU + "/cpu" + id + "/cpufreq/" + name; }

    /** Чтение файла cpufreq кластера: каталог кластера, затем каталоги его ядер. */
    public static String clusterRead(Cluster c, String name) {
        if (c.dir != null) { String s = Util.read(c.dir + "/" + name); if (s != null) return s; }
        for (int id : c.cpus) { String s = Util.read(cf(id, name)); if (s != null) return s; }
        return null;
    }

    private static Long toLong(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim().split("\\s+")[0]); } catch (Exception e) { return null; }
    }

    public static List<Cluster> clusters(List<Integer> ids, Proc proc) {
        List<Cluster> res = new ArrayList<>();
        Set<Integer> assigned = new HashSet<>();
        String base = CPU + "/cpufreq";
        for (String d : Util.list(base)) {
            if (!d.startsWith("policy")) continue;
            String rc = Util.read(base + "/" + d + "/related_cpus");
            if (rc == null) rc = Util.read(base + "/" + d + "/affected_cpus");
            TreeSet<Integer> cpus = parseRanges(rc);
            if (cpus.isEmpty()) continue;
            boolean overlap = false;
            for (int c : cpus) if (assigned.contains(c)) overlap = true;
            if (overlap) continue;
            Cluster cl = new Cluster();
            cl.cpus.addAll(cpus); cl.dir = base + "/" + d;
            assigned.addAll(cpus); res.add(cl);
        }
        for (int id : ids) {
            if (assigned.contains(id)) continue;
            TreeSet<Integer> cpus = parseRanges(Util.read(cf(id, "related_cpus")));
            if (cpus.isEmpty()) continue;
            Cluster cl = new Cluster();
            for (int c : cpus) if (!assigned.contains(c)) cl.cpus.add(c);
            if (cl.cpus.isEmpty()) continue;
            cl.dir = cf(id, "related_cpus").replace("/related_cpus", "");
            assigned.addAll(cl.cpus); res.add(cl);
        }
        // Запасной вариант: группировка по part + max freq
        Map<String, Cluster> groups = new LinkedHashMap<>();
        for (int id : ids) {
            if (assigned.contains(id)) continue;
            Integer part = proc == null ? null : proc.part.get(id);
            Long max = toLong(Util.read(cf(id, "cpuinfo_max_freq")));
            String key = part + "|" + max;
            Cluster cl = groups.get(key);
            if (cl == null) { cl = new Cluster(); cl.dir = cf(id, "x").replace("/x", ""); groups.put(key, cl); }
            cl.cpus.add(id);
        }
        res.addAll(groups.values());
        for (Cluster cl : res) {
            cl.min = toLong(clusterRead(cl, "cpuinfo_min_freq"));
            cl.max = toLong(clusterRead(cl, "cpuinfo_max_freq"));
            cl.scalingMax = toLong(clusterRead(cl, "scaling_max_freq"));
            cl.governor = clusterRead(cl, "scaling_governor");
            String av = clusterRead(cl, "scaling_available_frequencies");
            if (av != null && !av.isEmpty()) cl.nFreq = av.trim().split("\\s+").length;
            else {
                String tis = clusterRead(cl, "stats/time_in_state");
                if (tis != null && !tis.isEmpty()) cl.nFreq = tis.split("\n").length;
            }
            if (proc != null) {
                Map<Integer, Integer> cnt = new HashMap<>();
                int bestN = 0;
                for (int c : cl.cpus) {
                    Integer pt = proc.part.get(c);
                    if (pt == null) continue;
                    int n = cnt.containsKey(pt) ? cnt.get(pt) + 1 : 1;
                    cnt.put(pt, n);
                    if (n > bestN) { bestN = n; cl.part = pt; }
                    Integer im = proc.impl.get(c);
                    if (im != null) cl.impl = im;
                }
            }
        }
        java.util.Collections.sort(res, (a, b) -> a.cpus.first() - b.cpus.first());
        return res;
    }
}
