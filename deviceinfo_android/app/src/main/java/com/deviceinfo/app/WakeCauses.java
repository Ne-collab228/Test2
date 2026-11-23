package com.deviceinfo.app;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Кто будил телефон при выключенном экране (нужен доступ к истории использования). */
final class WakeCauses {
    private WakeCauses() {}

    private static final int EV_SCREEN_INTERACTIVE = 15;
    private static final int EV_SCREEN_NON_INTERACTIVE = 16;
    private static final int EV_FGS_START = 19;
    private static final int EV_NOTIFICATION_INTERRUPTION = 12; // @SystemApi, сырое значение

    static final class App {
        final String pkg;
        String label;
        long bgBytes;
        int fgsStarts;
        int notifications;

        App(String pkg) { this.pkg = pkg; this.label = pkg; }

        /** МБ фонового трафика * 1 + запуски фоновых служб * 2 + уведомления * 1. */
        double score() {
            return bgBytes / 1048576.0 + fgsStarts * 2.0 + notifications;
        }
    }

    static boolean hasAccess(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), ctx.getPackageName());
            if (mode == AppOpsManager.MODE_ALLOWED) return true;
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return ctx.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                        == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable ignored) {
        }
        // запасной путь (на некоторых прошивках проверка выше отвечает неточно): без доступа список пустой
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> l =
                    usm == null ? null : usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now);
            return l != null && !l.isEmpty();
        } catch (Throwable ignored) {
        }
        return false;
    }

    static Intent settingsIntent(Context ctx) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        try {
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
        } catch (Throwable ignored) {
        }
        return i;
    }

    static List<App> query(Context ctx, long fromWall, long toWall) {
        if (!hasAccess(ctx)) return null;
        List<App> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            String self = ctx.getPackageName();
            Map<String, App> map = new HashMap<>();

            // 1. Фоновый трафик по uid
            Map<Integer, Long> uidBytes = new HashMap<>();
            try {
                NetworkStatsManager nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
                if (nsm != null) {
                    for (int type : new int[]{ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE}) {
                        NetworkStats ns = null;
                        try {
                            ns = nsm.querySummary(type, null, fromWall, toWall);
                            if (ns == null) continue;
                            NetworkStats.Bucket b = new NetworkStats.Bucket();
                            while (ns.hasNextBucket()) {
                                ns.getNextBucket(b);
                                if (b.getState() != NetworkStats.Bucket.STATE_DEFAULT) continue;
                                long v = b.getRxBytes() + b.getTxBytes();
                                Long old = uidBytes.get(b.getUid());
                                uidBytes.put(b.getUid(), (old == null ? 0 : old) + v);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            try { if (ns != null) ns.close(); } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            for (Map.Entry<Integer, Long> e : uidBytes.entrySet()) {
                if (e.getValue() <= 0) continue;
                String[] names = uidName(pm, e.getKey());
                if (names[0].equals(self)) continue;
                App a = map.get(names[0]);
                if (a == null) { a = new App(names[0]); a.label = names[1]; map.put(names[0], a); }
                a.bgBytes += e.getValue();
            }

            // 2. События при выключенном экране
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                UsageEvents evs = usm == null ? null : usm.queryEvents(fromWall, toWall);
                if (evs != null) {
                    UsageEvents.Event ev = new UsageEvents.Event();
                    int screen = 0; // 0 неизвестно (считаем выкл.), 1 вкл., 2 выкл.
                    boolean first = true;
                    while (evs.hasNextEvent()) {
                        evs.getNextEvent(ev);
                        int t = ev.getEventType();
                        if (t == EV_SCREEN_INTERACTIVE) { screen = 1; first = false; continue; }
                        if (t == EV_SCREEN_NON_INTERACTIVE) { screen = 2; first = false; continue; }
                        first = false;
                        if (screen == 1) continue;
                        if (t != EV_FGS_START && t != EV_NOTIFICATION_INTERRUPTION) continue;
                        String pkg = ev.getPackageName();
                        if (pkg == null || pkg.equals(self)) continue;
                        App a = map.get(pkg);
                        if (a == null) { a = new App(pkg); a.label = label(pm, pkg); map.put(pkg, a); }
                        if (t == EV_FGS_START) a.fgsStarts++; else a.notifications++;
                    }
                }
            } catch (Throwable ignored) {
            }

            for (App a : map.values()) if (a.score() >= 0.05) out.add(a);
            Collections.sort(out, (x, y) -> Double.compare(y.score(), x.score()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String[] uidName(PackageManager pm, int uid) {
        try {
            if (uid == 1000) return new String[]{"android", "Система Android"};
            if (uid >= 10000) {
                String[] pk = pm.getPackagesForUid(uid);
                if (pk != null && pk.length > 0) return new String[]{pk[0], label(pm, pk[0])};
            }
            String n = null;
            try { n = pm.getNameForUid(uid); } catch (Throwable ignored) { }
            if (n == null) n = "uid " + uid;
            return new String[]{n, "Система (" + n + ")"};
        } catch (Throwable t) {
            return new String[]{"uid " + uid, "Система (uid " + uid + ")"};
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            CharSequence c = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return c == null ? pkg : c.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    static String bytes(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024L * 1024) return Math.round(b / 1024.0) + " КБ";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f МБ", b / 1048576.0);
        return String.format(Locale.US, "%.2f ГБ", b / 1073741824.0);
    }

    private static String plural(int n, String one, String few, String many) {
        int m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    static String describe(App a) {
        List<String> p = new ArrayList<>();
        if (a.bgBytes > 0) p.add(bytes(a.bgBytes) + " в фоне");
        if (a.fgsStarts > 0) p.add(a.fgsStarts + " " + plural(a.fgsStarts, "запуск", "запуска", "запусков") + " фоновой службы");
        if (a.notifications > 0) p.add(a.notifications + " " + plural(a.notifications, "уведомление", "уведомления", "уведомлений"));
        String s = a.label == null ? a.pkg : a.label;
        return p.isEmpty() ? s : s + " — " + String.join(", ", p);
    }

    static List<String> top(Context ctx, long from, long to, int max) {
        List<App> l = query(ctx, from, to);
        if (l == null) return null;
        List<String> r = new ArrayList<>();
        for (App a : l) { if (r.size() >= max) break; r.add(describe(a)); }
        return r;
    }
}
