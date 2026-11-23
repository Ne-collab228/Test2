package com.deviceinfo.app;

import static org.junit.Assert.*;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowUsageStatsManager;
import org.robolectric.Shadows;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WakeCausesTest {
    private Context ctx;
    private ShadowUsageStatsManager shadow;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        shadow = Shadows.shadowOf((UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE));
    }

    private void setMode(int mode) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        Shadows.shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                ctx.getPackageName(), mode);
    }

    private void grant() { setMode(AppOpsManager.MODE_ALLOWED); }

    private void ev(String pkg, int type, long t) {
        shadow.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(pkg).setEventType(type).setTimeStamp(t).build());
    }

    @Test
    public void noAccess() {
        setMode(AppOpsManager.MODE_IGNORED);
        assertFalse(WakeCauses.hasAccess(ctx));
        assertNull(WakeCauses.query(ctx, 0, 100000));
        assertNull(WakeCauses.top(ctx, 0, 100000, 3));
        assertNotNull(WakeCauses.settingsIntent(ctx));
    }

    @Test
    public void countsOnlyScreenOff() {
        grant();
        assertTrue(WakeCauses.hasAccess(ctx));
        ev("a.screenon", 19, 1000);          // экран неизвестен -> считается выкл.? до первого события
        ev("x.on", 15, 2000);
        ev("a.screenon", 19, 3000);          // экран включён: игнор
        ev("a.screenon", 12, 3100);
        ev("x.off", 16, 4000);
        ev("b.app", 19, 5000);
        ev("b.app", 19, 5100);
        ev("b.app", 12, 5200);
        ev("c.app", 12, 5300);
        ev("c.app", 12, 5400);
        ev("c.app", 12, 5500);
        ev(ctx.getPackageName(), 19, 5600);  // свой пакет пропускается
        List<WakeCauses.App> l = WakeCauses.query(ctx, 0, 10000);
        assertNotNull(l);
        WakeCauses.App b = null, c = null;
        for (WakeCauses.App a : l) {
            assertNotEquals(ctx.getPackageName(), a.pkg);
            if (a.pkg.equals("b.app")) b = a;
            if (a.pkg.equals("c.app")) c = a;
            if (a.pkg.equals("a.screenon")) {
                assertEquals(1, a.fgsStarts);
                assertEquals(0, a.notifications);
            }
        }
        assertNotNull(b);
        assertEquals(2, b.fgsStarts);
        assertEquals(1, b.notifications);
        assertEquals(3, c.notifications);
        assertEquals(5.0, b.score(), 1e-9);
        assertEquals(3.0, c.score(), 1e-9);
        assertTrue(l.indexOf(b) < l.indexOf(c));
        List<String> top = WakeCauses.top(ctx, 0, 10000, 1);
        assertEquals(1, top.size());
        assertEquals("b.app — 2 запуска фоновой службы, 1 уведомление", top.get(0));
    }

    @Test
    public void formatting() {
        assertEquals("12 КБ", WakeCauses.bytes(12 * 1024));
        assertEquals("3.4 МБ", WakeCauses.bytes((long) (3.4 * 1048576)));
        WakeCauses.App a = new WakeCauses.App("p");
        a.label = "Telegram";
        a.bgBytes = (long) (3.4 * 1048576);
        a.fgsStarts = 3;
        a.notifications = 5;
        assertEquals("Telegram — 3.4 МБ в фоне, 3 запуска фоновой службы, 5 уведомлений", WakeCauses.describe(a));
        a.bgBytes = 0; a.fgsStarts = 0;
        assertEquals("Telegram — 5 уведомлений", WakeCauses.describe(a));
    }
}
