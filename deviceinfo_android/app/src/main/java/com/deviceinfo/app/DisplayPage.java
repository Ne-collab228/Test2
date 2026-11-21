package com.deviceinfo.app;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

@SuppressWarnings("deprecation")
public class DisplayPage implements Page {
    @Override public String title() { return "Экран"; }

    // ---------- чистые функции (для тестов) ----------

    /** Соотношение сторон "20:9": long/short*9 с округлением до 0.1, ".0" отбрасывается. */
    public static String aspectRatio(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        double r = Math.max(w, h) / (double) Math.min(w, h) * 9.0;
        r = Math.round(r * 10) / 10.0;
        String s = r == Math.floor(r) ? String.valueOf((long) r) : Util.f("%.1f", r);
        return s + ":9";
    }

    /** xdpi/ydpi надёжны: оба > 100, различаются не более чем на 10%, и не равны densityDpi (±1.5). */
    public static boolean dpiReliable(float xdpi, float ydpi, float densityDpi) {
        if (!(xdpi > 100 && ydpi > 100)) return false;
        float mx = Math.max(xdpi, ydpi), mn = Math.min(xdpi, ydpi);
        if ((mx - mn) / mx > 0.10f) return false;
        if (Math.abs(xdpi - densityDpi) <= 1.5f && Math.abs(ydpi - densityDpi) <= 1.5f) return false;
        return true;
    }

    /** Диагональ в дюймах по пикселям и dpi. */
    public static double diagonalInches(int w, int h, float xdpi, float ydpi) {
        double wi = w / (double) xdpi, hi = h / (double) ydpi;
        return Math.sqrt(wi * wi + hi * hi);
    }

    public static String hdrName(int t) {
        switch (t) {
            case 1: return "Dolby Vision";
            case 2: return "HDR10";
            case 3: return "HLG";
            case 4: return "HDR10+";
            default: return "Тип " + t;
        }
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : Util.f("%.1f", v);
    }

    private static String hz(float v) { return Util.f("%.2f Гц", v); }

    private static String sys(Context ctx, String key) {
        try { return Settings.System.getString(ctx.getContentResolver(), key); } catch (Throwable e) { return null; }
    }

    // ---------- страница ----------

    @Override public void build(Context ctx, Section out) {
        Display d = null;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Throwable ignored) { }

        out.header("Экран");
        if (d == null) {
            out.row("Дисплей", null);
            buildSettings(ctx, out);
            return;
        }
        DisplayMetrics m = new DisplayMetrics();
        try { d.getRealMetrics(m); } catch (Throwable e) { m = ctx.getResources().getDisplayMetrics(); }
        int sw = Math.min(m.widthPixels, m.heightPixels), lw = Math.max(m.widthPixels, m.heightPixels);
        out.row("Название", d.getName());
        out.row("Разрешение", sw + " × " + lw + " px");
        out.row("Соотношение сторон", aspectRatio(sw, lw));
        out.row("Плотность (densityDpi)", m.densityDpi + " dpi");
        out.row("Плотность (density)", Util.f("%.3f", m.density));
        float fs = ctx.getResources().getConfiguration().fontScale;
        out.row("scaledDensity", Util.f("%.3f", m.scaledDensity));
        out.row("Масштаб шрифта", Util.f("%.2f", fs));
        out.row("xdpi × ydpi", Util.f("%.1f × %.1f", m.xdpi, m.ydpi));
        boolean nic = Build.MODEL != null && Build.MODEL.toUpperCase().startsWith("NIC-");
        if (dpiReliable(m.xdpi, m.ydpi, m.densityDpi)) {
            double diag = diagonalInches(m.widthPixels, m.heightPixels, m.xdpi, m.ydpi);
            double wi = sw / (double) Math.min(m.xdpi, m.ydpi), hi = lw / (double) Math.max(m.xdpi, m.ydpi);
            out.row("Диагональ", Util.f("%.2f″", diag));
            out.row("Физический размер", Util.f("%.1f × %.1f мм", Math.min(wi, hi) * 25.4, Math.max(wi, hi) * 25.4));
            out.row("Плотность пикселей", Util.f("%.0f ppi", Math.sqrt(m.widthPixels * (double) m.heightPixels / (wi * hi))));
        } else {
            out.row("Физический размер / диагональ / ppi", "Прошивка не сообщает", Section.MUTED);
            if (nic) out.row("Справочно (Honor X6c)", "6.61″, ≈266 ppi (по спецификации)", Section.INFO);
        }

        out.header("Частота обновления");
        float cur = 0;
        try { cur = d.getRefreshRate(); } catch (Throwable ignored) { }
        out.row("Текущая частота", cur > 0 ? hz(cur) : null, Section.INFO);
        try {
            Display.Mode cm = d.getMode();
            if (cm != null) out.row("Текущий режим", cm.getPhysicalWidth() + " × " + cm.getPhysicalHeight() + " @ " + hz(cm.getRefreshRate()));
        } catch (Throwable ignored) { }
        try {
            Display.Mode[] modes = d.getSupportedModes();
            TreeSet<Float> rates = new TreeSet<>();
            TreeSet<String> res = new TreeSet<>();
            List<String[]> rows = new ArrayList<>();
            for (Display.Mode md : modes) {
                float r = Math.round(md.getRefreshRate() * 100) / 100f;
                rates.add(r);
                res.add(Math.min(md.getPhysicalWidth(), md.getPhysicalHeight()) + " × " + Math.max(md.getPhysicalWidth(), md.getPhysicalHeight()));
                rows.add(new String[]{String.valueOf(md.getModeId()),
                        md.getPhysicalWidth() + " × " + md.getPhysicalHeight(), hz(r)});
            }
            StringBuilder sb = new StringBuilder();
            for (Float r : rates) { if (sb.length() > 0) sb.append(", "); sb.append(num(r)); }
            out.row("Поддерживаемые частоты", sb.length() == 0 ? null : sb + " Гц");
            StringBuilder rs = new StringBuilder();
            for (String r : res) { if (rs.length() > 0) rs.append(", "); rs.append(r); }
            out.row("Поддерживаемые разрешения", rs.length() == 0 ? null : rs.toString());
            if (!rows.isEmpty()) out.table(new String[]{"Режим", "Разрешение", "Частота"}, rows, new float[]{1, 2, 2});
        } catch (Throwable e) {
            out.row("Поддерживаемые режимы", null);
        }
        String peak = sys(ctx, "peak_refresh_rate"), min = sys(ctx, "min_refresh_rate"), user = sys(ctx, "user_refresh_rate");
        if (peak != null) out.row("peak_refresh_rate", peak);
        if (min != null) out.row("min_refresh_rate", min);
        if (user != null) out.row("user_refresh_rate", user);
        out.note("MagicOS может снижать частоту до 60 Гц в простое, при экономии энергии и в части приложений; "
                + "текущая частота адаптивна и меняется во время работы.");

        out.header("Цвет и HDR");
        try { out.row("Широкая цветовая гамма", d.isWideColorGamut() ? "Да" : "Нет"); } catch (Throwable e) { out.row("Широкая цветовая гамма", null); }
        try { out.row("HDR", d.isHdr() ? "Да" : "Нет"); } catch (Throwable e) { out.row("HDR", null); }
        try {
            Display.HdrCapabilities hc = d.getHdrCapabilities();
            int[] types = hc == null ? new int[0] : hc.getSupportedHdrTypes();
            StringBuilder sb = new StringBuilder();
            for (int t : types) { if (sb.length() > 0) sb.append(", "); sb.append(hdrName(t)); }
            out.row("Типы HDR", sb.length() == 0 ? "Не поддерживается" : sb.toString());
            if (hc != null && types.length > 0 && hc.getDesiredMaxLuminance() > 0)
                out.row("Макс. яркость HDR", Util.f("%.0f нит", hc.getDesiredMaxLuminance()));
        } catch (Throwable e) { out.row("Типы HDR", null); }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                android.graphics.ColorSpace cs = d.getPreferredWideGamutColorSpace();
                out.row("Предпочтительное цветовое пространство", cs == null ? "Нет" : cs.getName());
            } catch (Throwable ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.view.DisplayCutout c = d.getCutout();
                if (c == null) out.row("Вырез", "Нет данных / нет выреза");
                else {
                    out.row("Вырез", c.getBoundingRects().size() + " шт.");
                    out.row("Отступы выреза (сверху)", c.getSafeInsetTop() + " px");
                }
            } catch (Throwable ignored) { }
        }
        buildSettings(ctx, out);
    }

    private void buildSettings(Context ctx, Section out) {
        out.header("Яркость и параметры");
        String b = sys(ctx, "screen_brightness");
        String mode = sys(ctx, "screen_brightness_mode");
        if (b != null) {
            try {
                int raw = Integer.parseInt(b.trim());
                int max = raw > 255 ? 4095 : 255;
                out.row("Яркость (raw)", raw + " (предположительный максимум " + max + ")");
                out.bar("Яркость", Util.f("%.0f%%", raw * 100.0 / max), raw * 100.0 / max, Section.INFO);
            } catch (Exception e) { out.row("Яркость (raw)", b); }
        } else out.row("Яркость", null);
        out.row("Автояркость", mode == null ? null : ("1".equals(mode.trim()) ? "Включена" : "Выключена"));
        String to = sys(ctx, "screen_off_timeout");
        if (to != null) {
            try { out.row("Тайм-аут экрана", Util.duration(Long.parseLong(to.trim()) / 1000.0)); }
            catch (Exception e) { out.row("Тайм-аут экрана", to); }
        } else out.row("Тайм-аут экрана", null);
        try {
            out.row("Масштаб шрифта (система)", Util.f("%.2f", ctx.getResources().getConfiguration().fontScale));
        } catch (Throwable ignored) { }
        try {
            UiModeManager u = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            String night = null;
            int nm = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (nm == Configuration.UI_MODE_NIGHT_YES) night = "Включена";
            else if (nm == Configuration.UI_MODE_NIGHT_NO) night = "Выключена";
            if (u != null) {
                int t = u.getNightMode();
                String cfg = t == UiModeManager.MODE_NIGHT_YES ? "всегда" : t == UiModeManager.MODE_NIGHT_NO ? "никогда"
                        : t == UiModeManager.MODE_NIGHT_AUTO ? "авто" : "по системе";
                night = (night == null ? "" : night + ", ") + "режим: " + cfg;
            }
            out.row("Тёмная тема", night);
        } catch (Throwable e) { out.row("Тёмная тема", null); }
    }
}
