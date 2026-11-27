package com.deviceinfo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Горизонтальные полосы «кто сколько»: слева название, полоса длиной по значению, справа число и серая
 * приписка (например, трафик). Строка с нулём — короткий серый штрих.
 */
public class HBarsView extends View {
    private final Theme th;
    private final float d;
    private List<String[]> rows = new ArrayList<>();
    private double[] values = new double[0];
    private int color;
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG),
            extra = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    static final float ROW_DP = 30;

    public HBarsView(Context c, Theme th) {
        super(c);
        this.th = th;
        d = c.getResources().getDisplayMetrics().density;
        name.setColor(th.text); name.setTextSize(14 * d);
        num.setColor(th.text); num.setTextSize(14 * d); num.setFakeBoldText(true);
        extra.setColor(th.muted); extra.setTextSize(13 * d);
        color = th.accent;
    }

    public void set(List<String[]> rows, double[] values, int color) {
        boolean resize = rows == null || this.rows == null || rows.size() != this.rows.size();
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.values = values == null ? new double[0] : values;
        this.color = color;
        if (resize) requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (Math.max(1, rows.size()) * ROW_DP * d + 4 * d));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float nameW = Math.min(w * 0.34f, 130 * d);
        float barX = nameW + 8 * d;
        double max = 0;
        for (double v : values) if (!Double.isNaN(v)) max = Math.max(max, v);
        // полоса занимает до 60 % оставшейся ширины — справа место под подписи
        float barMax = (w - barX) * 0.6f;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            float top = i * ROW_DP * d + 2 * d, mid = top + ROW_DP * d / 2;
            String nm = TextUtils.ellipsize(row[0], new android.text.TextPaint(name), nameW, TextUtils.TruncateAt.END).toString();
            c.drawText(nm, 0, mid + 5 * d, name);
            double v = i < values.length ? values[i] : 0;
            float len = max > 0 && v > 0 ? (float) (v / max * barMax) : 0;
            boolean zero = len <= 0;
            len = Math.max(len, 3 * d);
            bar.setColor(zero ? th.surface2 : color);
            r.set(barX, mid - 7 * d, barX + len, mid + 7 * d);
            c.drawRoundRect(r, 4 * d, 4 * d, bar);
            if (!zero) c.drawRect(barX, mid - 7 * d, Math.min(barX + 4 * d, barX + len), mid + 7 * d, bar); // прямой край у основания
            float x = barX + len + 6 * d;
            String n = row.length > 1 && row[1] != null ? row[1] : "";
            if (!n.isEmpty()) { c.drawText(n, x, mid + 5 * d, num); x += num.measureText(n) + 5 * d; }
            String e = row.length > 2 && row[2] != null ? row[2] : "";
            if (!e.isEmpty()) {
                String t = (n.isEmpty() ? "" : "· ") + e;
                t = TextUtils.ellipsize(t, new android.text.TextPaint(extra), Math.max(0, w - x), TextUtils.TruncateAt.END).toString();
                c.drawText(t, x, mid + 5 * d, extra);
            }
        }
    }
}
