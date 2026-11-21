package com.deviceinfo.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;


import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BatteryWearServiceTest {
    @Before public void setUp() {
        BatteryMeter.reset();
        BatteryWearService.disableLoop = true;
        BatteryWearService.running = false;
        BatteryWearService.runCount = 0;
    }

    @After public void tearDown() {
        BatteryWearService.disableLoop = false;
        BatteryWearService.running = false;
    }

    private static String text(Notification n) {
        return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    @Test public void measuresAndStopsAfterThree() {
        ServiceController<BatteryWearService> c = Robolectric.buildService(BatteryWearService.class, new Intent());
        BatteryWearService s = c.create().startCommand(0, 1).get();
        assertTrue(BatteryWearService.running);
        Notification fg = shadowOf(s).getLastForegroundNotification();
        assertNotNull(fg);
        assertEquals("Идёт замер износа батареи", String.valueOf(fg.extras.getCharSequence(Notification.EXTRA_TITLE)));
        s.designMah = 5000;

        // 1000 мА при 5000 мА·ч: 1 % = 180 с = 60 отсчётов по 3 с.
        long t = 1_000_000;
        int level = 90;
        int done = 0;
        for (int i = 0; i < 20000 && !shadowOf(s).isStoppedBySelf(); i++) {
            t += 3000;
            if (i % 60 == 0) level--;
            s.onSample(t, level, 3, -1000);
        }
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(BatteryWearService.running);
        assertEquals(3, BatteryWearService.runCount);
        assertEquals(3, BatteryMeter.load(s).size());

        NotificationManager nm = (NotificationManager) s.getSystemService(Context.NOTIFICATION_SERVICE);
        List<String> texts = new ArrayList<>();
        for (android.service.notification.StatusBarNotification n : shadowOf(nm).getActiveNotifications())
            texts.add(text(n.getNotification()));
        boolean result = false, fin = false;
        for (String x : texts) {
            if (x.contains("мА·ч") && x.contains("износ") && x.contains("замер 1 из 3")) result = true;
            if (x.contains("медиана")) fin = true;
        }
        assertTrue(texts.toString(), result);
        assertTrue(texts.toString(), fin);
    }

    @Test public void stopActionStops() {
        ServiceController<BatteryWearService> c = Robolectric.buildService(BatteryWearService.class, new Intent());
        BatteryWearService s = c.create().startCommand(0, 1).get();
        assertTrue(BatteryWearService.running);
        s.onStartCommand(new Intent(BatteryWearService.ACTION_STOP), 0, 2);
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(BatteryWearService.running);
    }

    @Test public void batteryPageShowsStartAction() throws Exception {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        Intent bat = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", 60).putExtra("scale", 100)
                .putExtra("status", 3).putExtra("plugged", 0).putExtra("present", true)
                .putExtra("temperature", 300).putExtra("voltage", 3900).putExtra("health", 2);
        ctx.sendStickyBroadcast(bat);
        Section sec = new Section();
        new BatteryPage().build(ctx, sec);
        String txt = sec.toText();
        assertTrue(txt, txt.contains("[кнопка] Запустить замер в фоне"));
        BatteryWearService.running = true;
        Section sec2 = new Section();
        new BatteryPage().build(ctx, sec2);
        assertTrue(sec2.toText().contains("[кнопка] Остановить замер"));
    }

    @Test public void activityRendersActionButton() {
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        Section s = new Section();
        s.header("T");
        s.action("x", "Тест кнопка", true);
        a.render(s, false);
        List<Button> btns = new ArrayList<>();
        collect(a.getWindow().getDecorView(), btns);
        boolean found = false;
        for (Button b : btns) if ("Тест кнопка".contentEquals(b.getText())) found = true;
        assertTrue(found);
        Section s2 = new Section();
        s2.header("T");
        s2.action("x", "Другая", true);
        a.render(s2, true);
        btns.clear(); collect(a.getWindow().getDecorView(), btns);
        found = false;
        for (Button b : btns) if ("Другая".contentEquals(b.getText())) found = true;
        assertTrue(found);
        c.pause().stop().destroy();
    }

    private static void collect(android.view.View v, List<Button> out) {
        if (v instanceof Button) out.add((Button) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
