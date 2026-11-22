package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Новый интерфейс: темы, акцент, период обновления, заголовки с крупным значением и графики. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class UiTest {
    static void collectText(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), out);
        }
    }

    static void collectCharts(View v, List<ChartView> out) {
        if (v instanceof ChartView) out.add((ChartView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectCharts(g.getChildAt(i), out);
        }
    }

    @Test public void defaultsAreDarkOrangeOneSecond() {
        Context ctx = RuntimeEnvironment.getApplication();
        Theme.prefs(ctx).edit().clear().commit();
        assertEquals(1000, Theme.period(ctx));
        Theme t = Theme.load(ctx);
        assertTrue(t.dark);
        assertEquals(Theme.ACCENTS[0], t.accent);
        assertEquals(t.accent, t.color(Section.NORMAL));
    }

    @Test public void settingsChangeThemeAndPeriod() {
        Context ctx = RuntimeEnvironment.getApplication();
        Theme.prefs(ctx).edit().putInt("mode", Theme.MODE_LIGHT).putInt("accent", 4).putLong("period", 2000).commit();
        Theme t = Theme.load(ctx);
        assertFalse(t.dark);
        assertEquals(Theme.ACCENTS[4], t.accent);
        assertEquals(2000, Theme.period(ctx));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(2000, a.period);
        assertFalse(a.th.dark);
        Theme.prefs(ctx).edit().clear().commit();
    }

    @Test public void rendersHeaderBigValueAndChart() {
        Theme.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Section s = new Section();
        s.header("Темп. CPU", "38 °C", Section.NORMAL);
        s.chart("История", "38 °C", new double[]{30, 35, 40, 38}, 30, 40, "°C", Section.NORMAL);
        s.row("Аппаратура CPU", "Mediatek MT6769V/CB");
        s.row("Поддерживаемые ABI", "arm64-v8a, armeabi-v7a, armeabi — очень длинное значение");
        a.render(s, false);
        List<TextView> tv = new ArrayList<>();
        collectText(a.getWindow().getDecorView(), tv);
        boolean big = false;
        for (TextView t : tv) if ("38 °C".contentEquals(t.getText()) && t.getTextSize() >= 25 * a.getResources().getDisplayMetrics().scaledDensity) big = true;
        assertTrue(big);
        List<ChartView> ch = new ArrayList<>();
        collectCharts(a.getWindow().getDecorView(), ch);
        assertEquals(1, ch.size());
        // обновление на месте: структура та же — View не пересоздаются
        Section s2 = new Section();
        s2.header("Темп. CPU", "39 °C", Section.NORMAL);
        s2.chart("История", "39 °C", new double[]{30, 35, 40, 38, 39}, 30, 40, "°C", Section.NORMAL);
        s2.row("Аппаратура CPU", "Mediatek MT6769V/CB");
        s2.row("Поддерживаемые ABI", "arm64-v8a, armeabi-v7a, armeabi — очень длинное значение");
        a.render(s2, true);
        List<ChartView> ch2 = new ArrayList<>();
        collectCharts(a.getWindow().getDecorView(), ch2);
        assertSame(ch.get(0), ch2.get(0));
        tv.clear();
        collectText(a.getWindow().getDecorView(), tv);
        boolean upd = false;
        for (TextView t : tv) if ("39 °C".contentEquals(t.getText())) upd = true;
        assertTrue(upd);
    }

    @Test public void chartScaleAndText() {
        Section s = new Section();
        s.chart("Ток", "-500 мА", new double[]{-600, -400}, Double.NaN, Double.NaN, " мА", Section.NORMAL);
        Section.Item it = s.items.get(0);
        assertEquals(-600, it.lo, 1e-9);
        assertEquals(-400, it.hi, 1e-9);
        assertTrue(s.toText(), s.toText().contains("Ток: -500 мА"));
        Section f = new Section();
        f.chart("Пусто", null, new double[0], Double.NaN, Double.NaN, "", Section.NORMAL);
        assertTrue(f.items.get(0).hi > f.items.get(0).lo);
    }

    @Test public void seriesWindow() {
        Series s = new Series(5, 1000);
        for (int i = 0; i < 8; i++) s.add(i * 100, i);
        double[] v = s.values(700);
        assertArrayEquals(new double[]{3, 4, 5, 6, 7}, v, 1e-9);
        assertEquals(1, s.values(1650).length);
        s.add(800, Double.NaN);
        assertEquals(7, s.last(), 1e-9);
    }

    @Test public void settingsDialogOpens() {
        Theme.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.settingsDialog();
        android.app.AlertDialog d = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
    }

    @Test public void stackedLongValues() {
        assertFalse(MainActivity.stacked("Ядра CPU", "8"));
        assertTrue(MainActivity.stacked("Версия OpenGL", "OpenGL ES 3.2 v1.r49p1-03bet0.19498e0ae1d5dac223383c39a2e58f04"));
    }

    @Test public void swipeDirection() {
        float d = 2f;
        assertEquals(1, MainActivity.swipeDir(-200, 20, 200, d));   // влево — следующая вкладка
        assertEquals(-1, MainActivity.swipeDir(200, -30, 200, d));  // вправо — предыдущая
        assertEquals(0, MainActivity.swipeDir(-100, 10, 200, d));   // коротко
        assertEquals(0, MainActivity.swipeDir(-200, 150, 200, d));  // диагональ / прокрутка
        assertEquals(0, MainActivity.swipeDir(-200, 0, 2000, d));   // слишком медленно
    }

    @Test public void swipeSwitchesTab() {
        MainActivity a = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        a.switchTab(1);
        long t = android.os.SystemClock.uptimeMillis();
        android.view.View root = a.getWindow().getDecorView();
        float y = root.getHeight() * 0.7f, d = a.getResources().getDisplayMetrics().density;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 250 * d, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 150, android.view.MotionEvent.ACTION_UP, 50 * d, y + 5, 0);
        a.dispatchTouchEvent(down);
        a.dispatchTouchEvent(up);
        assertEquals(2, a.currentTab());
    }
}
