package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Цвета интерфейса и настройки (тема, акцент, частота обновления). */
public final class Theme {
    static final String PREFS = "settings";
    static final int MODE_DARK = 0, MODE_LIGHT = 1, MODE_SYSTEM = 2;

    static final String[] ACCENT_NAMES = {"Оранжевый", "Красный", "Розовый", "Фиолетовый", "Синий", "Бирюзовый", "Зелёный", "Жёлтый"};
    static final int[] ACCENTS = {0xFFFF7A45, 0xFFE5484D, 0xFFE54D9B, 0xFF8E6CEF, 0xFF3E8BFF, 0xFF12B5A5, 0xFF46A758, 0xFFF5B100};
    static final String[] MODE_NAMES = {"Тёмная", "Светлая", "Системная"};
    static final long[] PERIODS = {500, 1000, 2000, 3000, 5000};
    static final long DEFAULT_PERIOD = 1000;

    final boolean dark;
    final int accent, onAccent;
    final int bg, surface, surface2, bar, text, muted, ok, warn, crit, info, grid;

    private Theme(boolean dark, int accent) {
        this.dark = dark;
        this.accent = accent;
        this.onAccent = luminance(accent) > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
        if (dark) {
            bg = 0xFF15171B; surface = 0xFF1E2126; surface2 = 0xFF2A2E35; bar = 0xFF15171B;
            text = 0xFFEDEFF2; muted = 0xFF8E96A3; grid = 0xFF4A505A;
            ok = 0xFF4CC26E; warn = 0xFFF2A93B; crit = 0xFFF0524F; info = 0xFF5AA2FF;
        } else {
            bg = 0xFFF3F4F6; surface = 0xFFFFFFFF; surface2 = 0xFFE6E8EC; bar = 0xFFF3F4F6;
            text = 0xFF15171B; muted = 0xFF667080; grid = 0xFFB9BFC8;
            ok = 0xFF1E8E4A; warn = 0xFFC77700; crit = 0xFFD02F2C; info = 0xFF1F6FE0;
        }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static int mode(Context c) { return prefs(c).getInt("mode", MODE_DARK); }

    static int accentIndex(Context c) {
        int i = prefs(c).getInt("accent", 0);
        return i >= 0 && i < ACCENTS.length ? i : 0;
    }

    /** Интервал автообновления, мс (по умолчанию 1 с). */
    static long period(Context c) {
        long p = prefs(c).getLong("period", DEFAULT_PERIOD);
        return p < 250 ? DEFAULT_PERIOD : p;
    }

    static Theme load(Context c) {
        int m = mode(c);
        boolean dark = m == MODE_DARK || (m == MODE_SYSTEM
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        return new Theme(dark, ACCENTS[accentIndex(c)]);
    }

    /** Цвет значения по коду Section. NORMAL — акцент. */
    int color(int c) {
        switch (c) {
            case Section.OK: return ok;
            case Section.WARN: return warn;
            case Section.CRIT: return crit;
            case Section.MUTED: return muted;
            case Section.INFO: return info;
            default: return accent;
        }
    }

    static double luminance(int c) {
        double r = ((c >> 16) & 0xFF) / 255.0, g = ((c >> 8) & 0xFF) / 255.0, b = (c & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** Цвет с прозрачностью alpha (0..255). */
    static int alpha(int c, int alpha) { return (c & 0x00FFFFFF) | (alpha << 24); }
}
