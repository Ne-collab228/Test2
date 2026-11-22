package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.List;

/** Содержимое вкладки: заголовки, строки «параметр — значение», полосы, таблицы, примечания. */
public final class Section {
    public static final int OK = 1, WARN = 2, CRIT = 3, MUTED = 4, INFO = 5, NORMAL = 0;
    public static final String NA = "Нет доступа";

    public static final class Item {
        public final int type; // 0 header (value — крупное значение справа или null), 1 row, 2 bar, 3 note, 4 table,
                               // 5 action (key = id, value = подпись, color != 0 — основная), 6 chart
        /** График: точки (старые → новые), границы шкалы и подписи границ. */
        public double[] series;
        public double lo, hi;
        public String loLabel, hiLabel;
        public String key, value;
        public int color;
        public float pct;
        public String[] headers;
        public List<String[]> rows;
        public float[] ratios;
        Item(int type) { this.type = type; }
    }

    public final List<Item> items = new ArrayList<>();

    public void header(String title) {
        Item i = new Item(0); i.key = title; items.add(i);
    }

    /** Заголовок раздела с крупным значением справа (например «38 °C»). */
    public void header(String title, String big, int color) {
        Item i = new Item(0); i.key = title; i.value = big; i.color = color; items.add(i);
    }

    /**
     * Живой график. values — точки от старых к новым; lo/hi — границы шкалы (NaN — по данным);
     * unit добавляется к подписям границ. value — текст текущего значения (для отчёта).
     */
    public void chart(String key, String value, double[] values, double lo, double hi, String unit, int color) {
        Item i = new Item(6);
        i.key = key; i.value = value == null ? "—" : value; i.color = color;
        i.series = values == null ? new double[0] : values;
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (double v : i.series) { if (Double.isNaN(v)) continue; mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (Double.isNaN(lo)) lo = mn == Double.POSITIVE_INFINITY ? 0 : mn;
        if (Double.isNaN(hi)) hi = mx == Double.NEGATIVE_INFINITY ? 1 : mx;
        if (hi - lo < 1e-9) { double pad = Math.max(1, Math.abs(hi) * 0.1); lo -= pad; hi += pad; }
        i.lo = lo; i.hi = hi;
        String u = unit == null ? "" : unit;
        i.loLabel = fmtAxis(lo) + u; i.hiLabel = fmtAxis(hi) + u;
        items.add(i);
    }

    static String fmtAxis(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6 || Math.abs(v) >= 100) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** value == null или пусто -> «Нет доступа» серым. */
    public void row(String key, Object value) { row(key, value, NORMAL); }

    public void row(String key, Object value, int color) {
        Item i = new Item(1);
        i.key = key;
        String v = value == null ? null : String.valueOf(value);
        if (v == null || v.isEmpty()) { v = NA; color = MUTED; }
        i.value = v; i.color = color; items.add(i);
    }

    /** Полоса заполнения 0..100. */
    public void bar(String key, String text, double pct, int color) {
        Item i = new Item(2);
        i.key = key; i.value = text; i.color = color;
        i.pct = (float) Math.max(0, Math.min(100, pct));
        items.add(i);
    }

    public void note(String text) { note(text, MUTED); }

    public void note(String text, int color) {
        Item i = new Item(3); i.value = text; i.color = color; items.add(i);
    }

    /** Кнопка действия; клик вызывает MainActivity.onAction(id). */
    public void action(String id, String label) { action(id, label, false); }

    public void action(String id, String label, boolean primary) {
        Item i = new Item(5); i.key = id; i.value = label; i.color = primary ? 1 : 0; items.add(i);
    }

    public void table(String[] headers, List<String[]> rows, float[] ratios) {
        Item i = new Item(4); i.headers = headers; i.rows = rows; i.ratios = ratios; items.add(i);
    }

    /** Цвет по уровню (0..100): зелёный / оранжевый / красный. */
    public static int level(double pct, double warn, double crit) {
        return pct >= crit ? CRIT : (pct >= warn ? WARN : OK);
    }

    public static int temp(double c) { return c >= 80 ? CRIT : (c >= 60 ? WARN : OK); }

    /** Текстовый вид для отчёта. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            switch (i.type) {
                case 0:
                    sb.append("\n[").append(i.key).append(']');
                    if (i.value != null) sb.append(" ").append(i.value);
                    sb.append('\n');
                    break;
                case 6:
                    sb.append(i.key).append(": ").append(i.value);
                    if (i.series.length > 1) sb.append(" (график: ").append(i.series.length).append(" точек, шкала ")
                            .append(i.loLabel).append(" – ").append(i.hiLabel).append(')');
                    sb.append('\n');
                    break;
                case 1: case 2: sb.append(i.key).append(": ").append(i.value).append('\n'); break;
                case 3: sb.append("  ").append(i.value).append('\n'); break;
                case 5: sb.append("[кнопка] ").append(i.value).append('\n'); break;
                case 4:
                    sb.append(String.join(" | ", i.headers)).append('\n');
                    for (String[] r : i.rows) sb.append(String.join(" | ", r)).append('\n');
                    break;
            }
        }
        return sb.toString();
    }
}
