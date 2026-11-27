package com.deviceinfo.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Анимированная батарея: корпус с «носиком», заливка по уровню (плавно меняется), подпись.
 * Зарядка — яркая полоса и молния, бегущие к «носику»; разряд — тусклая медленная полоса в обратную сторону;
 * полная — статично. Анимация идёт только пока View присоединён и виден (~30 кадров/с).
 */
public class BatteryView extends View {
    public static final int DISCHARGING = 0, CHARGING = 1, FULL = 2;

    private final Theme th;
    private final float d;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), nub = new Paint(Paint.ANTI_ALIAS_FLAG),
            textP = new Paint(Paint.ANTI_ALIAS_FLAG), bolt = new Paint(Paint.ANTI_ALIAS_FLAG),
            back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF(), inner = new RectF(), tmp = new RectF();
    private final Path clip = new Path(), boltPath = new Path();

    private float level = 0, shown = 0;
    private int state = DISCHARGING;
    private String text = "";
    private int colorOverride = -1;
    private ValueAnimator levelAnim;
    private boolean running;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 33);
        }
    };

    public BatteryView(Context c, Theme th) {
        super(c);
        this.th = th;
        d = c.getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2.5f * d);
        outline.setColor(th.muted);
        back.setColor(th.surface2);
        nub.setColor(th.muted);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTextSize(20 * d);
        textP.setFakeBoldText(true);
        textP.setColor(0xFFFFFFFF);
        textP.setShadowLayer(3 * d, 0, 0, 0xAA000000);
        bolt.setColor(0xFFFFFFFF);
        bolt.setShadowLayer(3 * d, 0, 0, 0xAA000000);
    }

    /** colorOverride -1 — цвет по уровню (<15 % crit, <30 % warn, иначе ok). */
    public void set(double levelPct, int state, String text, int colorOverride) {
        float lv = (float) Math.max(0, Math.min(100, Double.isNaN(levelPct) ? 0 : levelPct));
        this.text = text == null ? "" : text;
        this.colorOverride = colorOverride;
        boolean stateChanged = this.state != state;
        this.state = state;
        if (Math.abs(lv - level) > 0.001f) {
            level = lv;
            if (levelAnim != null) levelAnim.cancel();
            if (!isShown()) {
                shown = lv;
            } else {
                final float from = shown;
                levelAnim = ValueAnimator.ofFloat(from, lv);
                levelAnim.setDuration(600);
                levelAnim.setInterpolator(new DecelerateInterpolator(1.6f));
                levelAnim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
                levelAnim.start();
            }
        }
        if (stateChanged) updateRunning();
        invalidate();
    }

    float shownLevel() { return shown; }
    boolean isAnimating() { return running; }

    int fillColor() {
        if (colorOverride != -1) return colorOverride;
        return level < 15 ? th.crit : (level < 30 ? th.warn : th.ok);
    }

    private void updateRunning() {
        boolean want = state != FULL && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE;
        if (want == running) return;
        running = want;
        removeCallbacks(frame);
        if (want) postDelayed(frame, 33);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateRunning(); }

    @Override protected void onDetachedFromWindow() {
        if (levelAnim != null) { levelAnim.cancel(); levelAnim = null; }
        running = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, v); updateRunning(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); updateRunning(); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = (int) (240 * d);
        int h = (int) (52 * d);
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float nubW = 6 * d, nubH = h * 0.36f, sw = outline.getStrokeWidth() / 2;
        body.set(sw + getPaddingLeft(), sw + 1, w - nubW - 1 - getPaddingRight(), h - sw - 1);
        float rad = Math.min(10 * d, body.height() / 3);
        tmp.set(body.right + 1 - sw, (h - nubH) / 2, body.right + nubW, (h + nubH) / 2);
        c.drawRoundRect(tmp, 2 * d, 2 * d, nub);
        c.drawRoundRect(body, rad, rad, back);
        float pad = 3.5f * d;
        inner.set(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad);
        float irad = Math.max(1, rad - pad);
        float fw = inner.width() * shown / 100f;
        int col = fillColor();
        if (fw > 0.5f) {
            c.save();
            clip.rewind();
            clip.addRoundRect(inner, irad, irad, Path.Direction.CW);
            c.clipPath(clip);
            fill.setColor(col);
            c.drawRect(inner.left, inner.top, inner.left + fw, inner.bottom, fill);
            if (state != FULL) drawFlow(c, fw, col);
            c.restore();
        }
        c.drawRoundRect(body, rad, rad, outline);
        float cx = body.centerX(), cy = body.centerY();
        float ty = cy - (textP.descent() + textP.ascent()) / 2;
        float tw = textP.measureText(text);
        boolean showBolt = state == CHARGING;
        float bh = body.height() * 0.5f, bw = bh * 0.6f, gap = 6 * d;
        float total = tw + (showBolt ? bw + gap : 0);
        if (showBolt) {
            float bx = cx - total / 2;
            drawBolt(c, bx, cy - bh / 2, bw, bh);
            c.drawText(text, bx + bw + gap + tw / 2, ty, textP);
        } else {
            c.drawText(text, cx, ty, textP);
        }
    }

    /** Бегущая светлая полоса: зарядка — к «носику» (быстро, ярко), разряд — от него (медленно, тускло). */
    private void drawFlow(Canvas c, float fw, int col) {
        boolean chg = state == CHARGING;
        long period = chg ? 1500 : 3600;
        float ph = (SystemClock.uptimeMillis() % period) / (float) period;
        if (!chg) ph = 1 - ph;
        float bandW = Math.max(inner.width() * 0.3f, 28 * d);
        float x = inner.left - bandW + (fw + bandW) * ph;
        int a = chg ? 0x88 : 0x33;
        glow.setShader(new LinearGradient(x, 0, x + bandW, 0,
                new int[]{0x00FFFFFF, (a << 24) | 0xFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(x, inner.top, x + bandW, inner.bottom, glow);
        glow.setShader(null);
    }

    private void drawBolt(Canvas c, float x, float y, float w, float h) {
        boltPath.rewind();
        boltPath.moveTo(x + w * 0.62f, y);
        boltPath.lineTo(x, y + h * 0.56f);
        boltPath.lineTo(x + w * 0.48f, y + h * 0.56f);
        boltPath.lineTo(x + w * 0.34f, y + h);
        boltPath.lineTo(x + w, y + h * 0.40f);
        boltPath.lineTo(x + w * 0.52f, y + h * 0.40f);
        boltPath.close();
        c.drawPath(boltPath, bolt);
    }
}
