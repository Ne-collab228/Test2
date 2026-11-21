package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.List;

/** Содержимое вкладки: заголовки, строки «параметр — значение», полосы, таблицы, примечания. */
public final class Section {
    public static final int OK = 1, WARN = 2, CRIT = 3, MUTED = 4, INFO = 5, NORMAL = 0;
    public static final String NA = "Нет доступа";

    public static final class Item {
        public final int type; // 0 header, 1 row, 2 bar, 3 note, 4 table
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
                case 0: sb.append("\n[").append(i.key).append("]\n"); break;
                case 1: case 2: sb.append(i.key).append(": ").append(i.value).append('\n'); break;
                case 3: sb.append("  ").append(i.value).append('\n'); break;
                case 4:
                    sb.append(String.join(" | ", i.headers)).append('\n');
                    for (String[] r : i.rows) sb.append(String.join(" | ", r)).append('\n');
                    break;
            }
        }
        return sb.toString();
    }
}
