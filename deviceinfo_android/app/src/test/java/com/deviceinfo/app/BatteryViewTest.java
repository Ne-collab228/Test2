package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BatteryViewTest {
    @Test public void batteryItemAndText() {
        Section s = new Section();
        s.battery("Уровень заряда", 150, 1, "85 %", Section.NORMAL);
        assertEquals(1, s.items.size());
        Section.Item it = s.items.get(0);
        assertEquals(7, it.type);
        assertEquals(100f, it.pct, 0.001f);
        assertEquals(1, it.state);
        assertEquals("Уровень заряда: 85 %\n", s.toText());
    }

    @Test public void signatureIncludesTypeAndKey() {
        Section a = new Section(), b = new Section(), c = new Section();
        a.battery("K", 10, 0, "10 %", 0);
        b.battery("K", 90, 1, "90 %", 0);
        c.battery("Other", 10, 0, "10 %", 0);
        assertTrue(MainActivity.signature(a).startsWith("7|K"));
        assertEquals(MainActivity.signature(a), MainActivity.signature(b));
        assertNotEquals(MainActivity.signature(a), MainActivity.signature(c));
    }

    @Test public void viewConstructSetDraw() {
        Context ctx = RuntimeEnvironment.getApplication();
        BatteryView v = new BatteryView(ctx, Theme.load(ctx));
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(150, android.view.View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 600, 150);
        Canvas cv = new Canvas(Bitmap.createBitmap(600, 150, Bitmap.Config.ARGB_8888));
        for (int st = 0; st < 3; st++) {
            v.set(st == 0 ? 10 : 55, st, "55 %", -1);
            v.draw(cv);
        }
        v.set(Double.NaN, 1, null, -1);
        v.draw(cv);
        assertFalse(v.isAnimating()); // не присоединён к окну
        assertEquals(0, v.fillColor() == 0 ? 1 : 0);
    }

    @Test public void colorByLevel() {
        Context ctx = RuntimeEnvironment.getApplication();
        Theme t = Theme.load(ctx);
        BatteryView v = new BatteryView(ctx, t);
        v.set(10, 0, "10 %", -1);
        assertEquals(t.crit, v.fillColor());
        v.set(25, 0, "25 %", -1);
        assertEquals(t.warn, v.fillColor());
        v.set(80, 0, "80 %", -1);
        assertEquals(t.ok, v.fillColor());
        v.set(80, 0, "80 %", 0x123456);
        assertEquals(0x123456, v.fillColor());
        assertEquals(80f, v.shownLevel(), 0.01f); // не показан — без анимации
    }

    @Test public void renderInPlaceKeepsView() {
        Theme.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
        MainActivity a = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        Section s = new Section();
        s.battery("Заряд", 40, 1, "40 %", 0);
        s.bar("Полоса", "10 %", 10, Section.OK);
        a.render(s, false);
        Section s2 = new Section();
        s2.battery("Заряд", 41, 1, "41 %", 0);
        s2.bar("Полоса", "90 %", 90, Section.CRIT);
        a.render(s2, true);
        a.render(s, true);
        assertTrue(MainActivity.signature(s).equals(MainActivity.signature(s2)));
    }
}
