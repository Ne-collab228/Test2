package com.deviceinfo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Живой график: точечная сетка, подписи верхней и нижней границы, линия с заливкой. */
public class ChartView extends View {
    private final Theme th;
    private final float d;
    private double[] values = new double[0];
    private double lo = 0, hi = 1;
    private String loLabel = "", hiLabel = "";
    private int color;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), end = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();
    private final RectF r = new RectF();

    public ChartView(Context c, Theme th) {
        super(c);
        this.th = th;
        d = c.getResources().getDisplayMetrics().density;
        color = th.accent;
        dot.setColor(th.grid);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        pill.setColor(th.surface2);
        label.setColor(th.text);
        label.setTextSize(11 * d);
        label.setFakeBoldText(true);
        end.setStyle(Paint.Style.FILL);
    }

    public void set(double[] v, double lo, double hi, String loLabel, String hiLabel, int color) {
        this.values = v == null ? new double[0] : v;
        this.lo = lo; this.hi = hi;
        this.loLabel = loLabel == null ? "" : loLabel;
        this.hiLabel = hiLabel == null ? "" : hiLabel;
        this.color = color;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (120 * d));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float top = 12 * d, bottom = h - 12 * d, left = 4 * d, right = w - 4 * d;
        // точечная сетка
        float step = 9 * d;
        for (float y = top; y <= bottom + 0.5f; y += step)
            for (float x = left; x <= right + 0.5f; x += step)
                c.drawCircle(x, y, 1.1f * d, dot);

        int n = values.length;
        if (n >= 2 && hi > lo) {
            float gx = left + 52 * d; // место под подписи слева
            float dx = (right - gx) / Math.max(1, n - 1);
            path.reset(); area.reset();
            float x0 = 0, y0 = 0, xl = 0, yl = 0;
            for (int i = 0; i < n; i++) {
                double v = Math.max(lo, Math.min(hi, values[i]));
                float x = gx + i * dx;
                float y = (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y); x0 = x; y0 = y; }
                else {
                    // сглаживание: кривая через середины отрезков
                    float mx = (xl + x) / 2, my = (yl + y) / 2;
                    path.quadTo(xl, yl, mx, my);
                    area.quadTo(xl, yl, mx, my);
                }
                xl = x; yl = y;
            }
            path.lineTo(xl, yl);
            area.lineTo(xl, yl);
            area.lineTo(xl, bottom);
            area.close();
            fill.setShader(new LinearGradient(0, top, 0, bottom, Theme.alpha(color, 110), Theme.alpha(color, 8), Shader.TileMode.CLAMP));
            c.drawPath(area, fill);
            line.setColor(color);
            c.drawPath(path, line);
            end.setColor(color);
            c.drawCircle(xl, yl, 3.5f * d, end);
        }
        drawPill(c, hiLabel, left, top);
        drawPill(c, loLabel, left, bottom);
    }

    private void drawPill(Canvas c, String s, float x, float cy) {
        if (s.isEmpty()) return;
        float tw = label.measureText(s), ph = 18 * d, pw = tw + 12 * d;
        r.set(x, cy - ph / 2, x + pw, cy + ph / 2);
        c.drawRoundRect(r, ph / 2, ph / 2, pill);
        Paint.FontMetrics fm = label.getFontMetrics();
        c.drawText(s, x + 6 * d, cy - (fm.ascent + fm.descent) / 2, label);
    }
}
