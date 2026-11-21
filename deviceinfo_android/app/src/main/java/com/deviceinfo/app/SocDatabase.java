package com.deviceinfo.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** База SoC: чистая Java (без Android), определение названия чипа по свойствам и частотам. */
public final class SocDatabase {
    private SocDatabase() {}

    public static final String HIGH = "высокая", MED = "средняя", LOW = "низкая";

    public static final class SocResult {
        public final String name, method, confidence;
        public SocResult(String name, String method, String confidence) {
            this.name = name; this.method = method; this.confidence = confidence;
        }
        @Override public String toString() { return name + " [" + method + ", " + confidence + "]"; }
    }

    private static final Pattern BRAND = Pattern.compile(
            "(?i)helio|dimensity|snapdragon|exynos|tensor|kirin|unisoc|tiger|骁龙|天玑|麒麟|联发科|apple|\\bA\\d{2}\\b");
    private static final Pattern MT = Pattern.compile("(?i)MT(\\d{4})([A-Z]?)(?:/([A-Z]{1,4}))?");

    /** {ключ, имя, уверенность}. Ключ: MTnnnn[буква][/суффикс]. */
    private static final String[][] MT_TABLE = {
        {"MT6769V/CB", "MediaTek Helio G70 / G81 / G81 Ultra", MED},
        {"MT6769V/CT", "MediaTek Helio G80", HIGH},
        {"MT6769V/CU", "MediaTek Helio G80", HIGH},
        {"MT6769V/CZ", "MediaTek Helio G85", HIGH},
        {"MT6769T", "MediaTek Helio G80", HIGH},
        {"MT6769Z", "MediaTek Helio G85", HIGH},
        {"MT6769H", "MediaTek Helio G88", HIGH},
        {"MT6769G", "MediaTek Helio G91", MED},
        {"MT6769K", "MediaTek Helio G91 Ultra", MED},
        {"MT6769I", "MediaTek Helio G92", LOW},
        {"MT6769J", "MediaTek Helio G81 / G81 Ultra", MED},
        {"MT6769L", "MediaTek Helio G81 / G81 Ultra", MED},
        {"MT6769S", "MediaTek Helio G81 / G81 Ultra", MED},
        {"MT6765", "MediaTek Helio P35", MED},
        {"MT6765G", "MediaTek Helio G35", HIGH},
        {"MT6765V/CB", "MediaTek Helio G35", HIGH},
        {"MT6765V/XAA", "MediaTek Helio G36", HIGH},
        {"MT6765V/XBA", "MediaTek Helio G36", HIGH},
        {"MT6765H", "MediaTek Helio G37", HIGH},
        {"MT6762", "MediaTek Helio P22", HIGH},
        {"MT6762G", "MediaTek Helio G25", HIGH},
        {"MT6762V", "MediaTek Helio A25", HIGH},
        {"MT6761", "MediaTek Helio A22", MED},
        {"MT6761V/WAB", "MediaTek Helio A22", HIGH},
        {"MT6761V/WBB", "MediaTek Helio A22", HIGH},
        {"MT6761V/WE", "MediaTek Helio A20", HIGH},
        {"MT6785", "MediaTek Helio G90", MED},
        {"MT6785T", "MediaTek Helio G90T", HIGH},
        {"MT6785V/CC", "MediaTek Helio G90T", HIGH},
        {"MT6785V/CD", "MediaTek Helio G95", HIGH},
        {"MT6781", "MediaTek Helio G96", HIGH},
        {"MT6789", "MediaTek Helio G99", HIGH},
        {"MT6789H", "MediaTek Helio G100", HIGH},
        {"MT6789I", "MediaTek Helio G100", HIGH},
        {"MT6789J", "MediaTek Helio G100", HIGH},
        {"MT6833", "MediaTek Dimensity 700 / 6020", HIGH},
        {"MT6833P", "MediaTek Dimensity 810 / 6080", HIGH},
        {"MT6835", "MediaTek Dimensity 6100+", HIGH},
        {"MT6835T", "MediaTek Dimensity 6300", HIGH},
        {"MT6853", "MediaTek Dimensity 720", HIGH},
        {"MT6853T", "MediaTek Dimensity 800U", HIGH},
        {"MT6855", "MediaTek Dimensity 930 / 7020", HIGH},
        {"MT6855V/ATZA", "MediaTek Dimensity 7025", HIGH},
        {"MT6877", "MediaTek Dimensity 900 / 920 / 1080 / 7050", MED},
        {"MT6878", "MediaTek Dimensity 7300 / 7400", HIGH},
        {"MT6886", "MediaTek Dimensity 7200", HIGH},
        {"MT6895", "MediaTek Dimensity 8100", HIGH},
        {"MT6896", "MediaTek Dimensity 8200", HIGH},
        {"MT6897", "MediaTek Dimensity 8300", HIGH},
        {"MT6899", "MediaTek Dimensity 8400", HIGH},
        {"MT6983", "MediaTek Dimensity 9000", HIGH},
        {"MT6985", "MediaTek Dimensity 9200", HIGH},
        {"MT6989", "MediaTek Dimensity 9300", HIGH},
        {"MT6991", "MediaTek Dimensity 9400", HIGH},
        {"MT6993", "MediaTek Dimensity 9500", HIGH},
    };
    private static final Map<String, String[]> MT_MAP = new HashMap<>();
    static { for (String[] r : MT_TABLE) MT_MAP.put(r[0], r); }

    /** Кодовые имена платформ (нижний регистр) -> имя. */
    private static final Map<String, String> CODE = new LinkedHashMap<>();
    static {
        String[][] q = {
            {"sm8750", "Qualcomm Snapdragon 8 Elite"}, {"sun", "Qualcomm Snapdragon 8 Elite"},
            {"sm8650", "Qualcomm Snapdragon 8 Gen 3"}, {"pineapple", "Qualcomm Snapdragon 8 Gen 3"},
            {"sm8550", "Qualcomm Snapdragon 8 Gen 2"}, {"kalama", "Qualcomm Snapdragon 8 Gen 2"},
            {"sm8475", "Qualcomm Snapdragon 8+ Gen 1"},
            {"sm8450", "Qualcomm Snapdragon 8 Gen 1"}, {"taro", "Qualcomm Snapdragon 8 Gen 1"},
            {"sm8350", "Qualcomm Snapdragon 888"}, {"lahaina", "Qualcomm Snapdragon 888"},
            {"sm8250", "Qualcomm Snapdragon 865"}, {"kona", "Qualcomm Snapdragon 865"},
            {"sm8150", "Qualcomm Snapdragon 855"}, {"msmnile", "Qualcomm Snapdragon 855"},
            {"sdm845", "Qualcomm Snapdragon 845"},
            {"sm7325", "Qualcomm Snapdragon 778G"},
            {"sm7250", "Qualcomm Snapdragon 765G"}, {"lito", "Qualcomm Snapdragon 765G"},
            {"sm6375", "Qualcomm Snapdragon 695"},
            {"sm6225", "Qualcomm Snapdragon 680"},
            {"bengal", "Qualcomm Snapdragon 662 / 665"}, {"trinket", "Qualcomm Snapdragon 665"},
            {"sdm660", "Qualcomm Snapdragon 660"},
            {"msm8953", "Qualcomm Snapdragon 625"},
            {"s5e9945", "Samsung Exynos 2400"}, {"s5e8825", "Samsung Exynos 1280"},
            {"s5e8835", "Samsung Exynos 1380"},
            {"gs101", "Google Tensor"}, {"gs201", "Google Tensor G2"},
            {"zuma", "Google Tensor G3"}, {"zumapro", "Google Tensor G4"},
            {"ums9230", "Unisoc Tiger T606"}, {"ums9620", "Unisoc Tiger T7520"},
        };
        for (String[] r : q) CODE.put(r[0], r[1]);
    }

    private static String g(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static boolean brand(String s) { return s != null && BRAND.matcher(s).find(); }

    public static SocResult detect(Map<String, String> props, String model, String device, String board,
                                   long littleMaxKhz, long bigMaxKhz) {
        if (props == null) props = new HashMap<>();

        // 1. Словарь устройств
        String[] ids = {model, device, board, g(props, "ro.product.model"), g(props, "ro.product.device"),
                g(props, "ro.product.board")};
        for (String id : ids) {
            if (id == null) continue;
            String l = id.trim().toLowerCase(Locale.ROOT);
            if (l.startsWith("nic-lx") || l.equals("nicl"))
                return new SocResult("MediaTek Helio G81 Ultra", "словарь устройств (" + id.trim() + ")", HIGH);
            if (l.equals("lake"))
                return new SocResult("MediaTek Helio G81 Ultra", "словарь устройств (" + id.trim() + ")", HIGH);
            if (l.startsWith("jdy-lx"))
                return new SocResult("MediaTek Helio G85", "словарь устройств (" + id.trim() + ")", HIGH);
        }

        // 2. Маркетинговые имена из свойств
        String disp = g(props, "ro.config.cpu_info_display");
        if (brand(disp)) return new SocResult(disp, "ro.config.cpu_info_display", HIGH);
        String ext = g(props, "ro.vendor.soc.model.external_name");
        if (brand(ext)) return new SocResult(ext, "ro.vendor.soc.model.external_name", MED);

        // 3. Таблица MediaTek
        String[] keys = {"ro.soc.model", "ro.vendor.soc.model.part_name", "ro.vendor.soc.model.external_name",
                "ro.vendor.mediatek.platform", "ro.board.platform", "ro.hardware"};
        String bareFrom = null, bareNum = null, unknownMt = null;
        for (String k : keys) {
            String v = g(props, k);
            if (v == null) continue;
            Matcher m = MT.matcher(v);
            if (!m.find()) continue;
            String num = m.group(1), suf = m.group(2).toUpperCase(Locale.ROOT);
            String sub = m.group(3) == null ? null : m.group(3).toUpperCase(Locale.ROOT);
            String[] e = null;
            if (sub != null) e = MT_MAP.get("MT" + num + suf + "/" + sub);
            if (e == null) e = MT_MAP.get("MT" + num + suf);
            if (e == null) e = MT_MAP.get("MT" + num);
            if (e != null) return new SocResult(e[1], "таблица MediaTek (" + k + "=" + v + ")", e[2]);
            if (num.equals("6768") || num.equals("6769")) {
                if (bareFrom == null) { bareFrom = k + "=" + v; bareNum = num; }
            } else if (unknownMt == null) unknownMt = v;
        }

        // 4. Кодовые имена Qualcomm / Samsung / Google / Unisoc
        String[] ck = {"ro.soc.model", "ro.board.platform", "ro.hardware", "ro.vendor.mediatek.platform",
                "ro.product.board"};
        for (String k : ck) {
            String v = g(props, k);
            if (v == null) continue;
            String l = v.toLowerCase(Locale.ROOT);
            String n = CODE.get(l);
            if (n != null) return new SocResult(n, "таблица кодовых имён (" + k + "=" + v + ")", HIGH);
        }
        for (String k : ck) {
            String v = g(props, k);
            if (v == null) continue;
            String l = v.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : CODE.entrySet()) {
                String key = e.getKey();
                if (key.length() >= 6 && l.startsWith(key))
                    return new SocResult(e.getValue(), "таблица кодовых имён (" + k + "=" + v + ")", MED);
            }
        }

        // 5. Голый MT6768/MT6769 — по частоте малых ядер
        if (bareFrom != null) {
            String how = "MT" + bareNum + " + частоты (" + bareFrom + ")";
            long little = littleMaxKhz;
            int api = 0;
            try { api = Integer.parseInt(g(props, "ro.product.first_api_level")); } catch (Exception ignored) { }
            if (little >= 1650000 && little <= 1750000) {
                if (api >= 34)
                    return new SocResult("MediaTek Helio G81 Ultra (вероятно)", how + " + first_api_level " + api, MED);
                if (api > 0 && api <= 30)
                    return new SocResult("MediaTek Helio G70 / P65 (вероятно)", how + " + first_api_level " + api, LOW);
                return new SocResult("MediaTek Helio G81 / G70 (малые ядра 1.7 ГГц)", how, LOW);
            }
            if (little >= 1750001 && little <= 1850000)
                return new SocResult("MediaTek Helio G80 / G85 / G88 / G91 (малые ядра 1.8 ГГц)", how, LOW);
            return new SocResult("MediaTek MT" + bareNum + " (Helio G-серия, точная модель не определена)", how, LOW);
        }
        if (unknownMt != null)
            return new SocResult("MediaTek " + unknownMt.toUpperCase(Locale.ROOT), "ro.* (номер без записи в таблице)", LOW);

        // 6. Что есть
        String sm = g(props, "ro.soc.model"), mf = g(props, "ro.soc.manufacturer");
        if (sm != null) return new SocResult((mf != null ? mf + " " : "") + sm, "ro.soc.model как есть", LOW);
        String plat = Util.firstNonEmpty(g(props, "ro.board.platform"), g(props, "ro.hardware"));
        if (plat != null) return new SocResult("Платформа " + plat, "ro.board.platform / ro.hardware", LOW);
        return new SocResult("Не определён", "нет данных", LOW);
    }
}
