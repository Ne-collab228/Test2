package com.deviceinfo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Столбики по часам: высота — расход в простое (%/ч), цвет — сеть (Wi-Fi / мобильная). Часы на зарядке,
 * с включённым экраном и «на 100 %» (расход не виден) — низкие серые метки. Снизу — подписи часов и легенда.
 */
public class HoursView extends View {
    static final int NONE = -1, WIFI = 0, MOBILE = 1, CHARGE = 2, SCREEN = 3, FULL = 4;

    static String kindName(int k) {
        switch (k) {
            case WIFI: return "Wi-Fi";
            case MOBILE: return "мобильная сеть";
            case CHARGE: return "на зарядке";
            case SCREEN: return "экран включён";
            case FULL: return "на 100 % (расход не виден)";
            default: return null;
        }
    }

    private final Theme th;
    private final float d;
    private String[] labels = new String[0];
    private double[] values = new double[0];
    private int[] kinds = new int[0];
    private final int wifi, mobile;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(Paint.ANTI_ALIAS_FLAG), hatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HoursView(Context c, Theme th) {
        super(c);
        this.th = th;
        d = c.getResources().getDisplayMetrics().density;
        wifi = th.dark ? 0xFF338CED : 0xFF257DE0;
        mobile = th.dark ? 0xFFE76624 : 0xFFF47230;
        txt.setColor(th.muted); txt.setTextSize(10.5f * d);
        grid.setColor(Theme.alpha(th.grid, 110)); grid.setStrokeWidth(1 * d);
        hatch.setColor(th.grid); hatch.setStrokeWidth(1.5f * d);
    }

    public void set(String[] labels, double[] values, int[] kinds) {
        this.labels = labels == null ? new String[0] : labels;
        this.values = values == null ? new double[0] : values;
        this.kinds = kinds == null ? new int[0] : kinds;
        invalidate();
    }

    static final float PLOT_DP = 120, AXIS_DP = 18, LEGEND_DP = 44;

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) ((PLOT_DP + AXIS_DP + LEGEND_DP) * d));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float left = 22 * d, top = 6 * d, bottom = top + PLOT_DP * d - 6 * d;
        int n = values.length;
        double max = 1;
        for (double v : values) if (!Double.isNaN(v)) max = Math.max(max, v);
        double scale = Math.ceil(max);
        // сетка: целые %/ч
        int step = scale > 6 ? 2 : 1;
        for (int g = 0; g <= scale; g += step) {
            float y = (float) (bottom - g / scale * (bottom - top));
            c.drawLine(left, y, w, y, grid);
            String t = g + "";
            c.drawText(t, left - 6 * d - txt.measureText(t), y + 4 * d, txt);
        }
        if (n == 0) return;
        float slot = (w - left) / n, gap = Math.max(1.5f * d, slot * 0.16f), bw = slot - gap;
        for (int i = 0; i < n; i++) {
            float x0 = left + i * slot + gap / 2, x1 = x0 + bw;
            int k = i < kinds.length ? kinds[i] : NONE;
            double v = values[i];
            if ((k == WIFI || k == MOBILE) && !Double.isNaN(v)) {
                float y = (float) (bottom - Math.min(v, scale) / scale * (bottom - top));
                y = Math.min(y, bottom - 2 * d);
                p.setColor(k == WIFI ? wifi : mobile);
                r.set(x0, y, x1, bottom);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
                c.drawRect(x0, Math.max(y, bottom - 3 * d), x1, bottom, p);
            } else if (k == CHARGE || k == FULL) {
                drawHatched(c, x0, bottom - 7 * d, x1, bottom, k == FULL);
            } else if (k == SCREEN) {
                p.setColor(th.surface2);
                c.drawRect(x0, bottom - 3 * d, x1, bottom, p);
            }
        }
        // подписи часов: каждые 3 часа (по подписи, которая делится на 3)
        float ly = bottom + 14 * d;
        for (int i = 0; i < n && i < labels.length; i++) {
            String l = labels[i];
            int hr;
            try { hr = Integer.parseInt(l); } catch (Exception e) { continue; }
            if (hr % 3 != 0) continue;
            String t = hr + ":00";
            float cx = left + i * slot + slot / 2 - txt.measureText(t) / 2;
            cx = Math.max(0, Math.min(w - txt.measureText(t), cx));
            c.drawText(t, cx, ly, txt);
        }
        // легенда
        float lx = 0, lyy = bottom + AXIS_DP * d + 14 * d;
        int[] lk = {WIFI, MOBILE, CHARGE, SCREEN};
        String[] ln = {"Wi-Fi", "мобильная", "зарядка", "экран вкл."};
        for (int j = 0; j < lk.length; j++) {
            float tw = 10 * d + 5 * d + txt.measureText(ln[j]) + 14 * d;
            if (lx + tw > w && lx > 0) { lx = 0; lyy += 18 * d; }
            if (lk[j] == CHARGE) drawHatched(c, lx, lyy - 9 * d, lx + 10 * d, lyy + 1 * d, false);
            else {
                p.setColor(lk[j] == WIFI ? wifi : lk[j] == MOBILE ? mobile : th.surface2);
                r.set(lx, lyy - 9 * d, lx + 10 * d, lyy + 1 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, p);
            }
            c.drawText(ln[j], lx + 15 * d, lyy, txt);
            lx += tw;
        }
    }

    private void drawHatched(Canvas c, float x0, float y0, float x1, float y1, boolean light) {
        p.setColor(th.surface2);
        c.drawRect(x0, y0, x1, y1, p);
        c.save();
        c.clipRect(x0, y0, x1, y1);
        hatch.setColor(light ? Theme.alpha(th.grid, 120) : th.grid);
        float s = 4 * d;
        for (float x = x0 - (y1 - y0); x < x1; x += s) c.drawLine(x, y1, x + (y1 - y0), y0, hatch);
        c.restore();
    }
}
