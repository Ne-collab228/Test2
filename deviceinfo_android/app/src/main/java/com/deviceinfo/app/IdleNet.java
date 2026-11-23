package com.deviceinfo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;

/**
 * Какая сеть была, пока экран выключен: Wi-Fi, мобильная (и сколько из этого — со слабым сигналом)
 * или без сети. Мобильная сеть и особенно слабый сигнал — частая причина, почему телефон плохо спит:
 * модем чаще просыпается и сильнее передаёт. Считается по событиям (смена сети, экран, батарея),
 * сама ничего не будит.
 */
final class IdleNet {
    private IdleNet() {}

    static final int NONE = 0, WIFI = 1, MOBILE = 2, OTHER = 3;
    static final String PREFS = "idle_net";

    /** Накоплено при выключенном экране: {без сети, Wi-Fi, мобильная, другая, мобильная со слабым сигналом}, мс. */
    static final long[] total = new long[5], period = new long[5];
    static int net = -1;
    static boolean weak;
    static Boolean screenOn;
    static long lastEl = -1;

    /** Новое состояние на момент elapsed; null — не менялось. */
    static synchronized void update(long elapsed, Boolean screen, Integer netType, Boolean weakSignal) {
        if (lastEl >= 0 && elapsed > lastEl && Boolean.FALSE.equals(screenOn) && net >= 0) {
            long dt = Math.min(elapsed - lastEl, 6 * 3600_000L);
            total[net] += dt; period[net] += dt;
            if (net == MOBILE && weak) { total[4] += dt; period[4] += dt; }
        }
        lastEl = elapsed;
        if (screen != null) screenOn = screen;
        if (netType != null) net = netType;
        if (weakSignal != null) weak = weakSignal;
    }

    /** Копия с идущим промежутком до elapsed. */
    static synchronized long[] snapshot(boolean ofPeriod, long elapsed) {
        long[] a = (ofPeriod ? period : total).clone();
        if (lastEl >= 0 && elapsed > lastEl && Boolean.FALSE.equals(screenOn) && net >= 0) {
            long dt = Math.min(elapsed - lastEl, 6 * 3600_000L);
            a[net] += dt;
            if (net == MOBILE && weak) a[4] += dt;
        }
        return a;
    }

    static synchronized void resetPeriod(long elapsed) {
        update(elapsed, null, null, null);
        java.util.Arrays.fill(period, 0);
    }

    static synchronized void resetAll() {
        java.util.Arrays.fill(total, 0); java.util.Arrays.fill(period, 0);
        lastEl = -1; net = -1; weak = false; screenOn = null;
    }

    /** Текст «Wi-Fi 5 ч 10 мин · мобильная 1 ч (слабый сигнал 40 мин) · без сети 3 мин»; null — нет данных. */
    static String text(long[] a) {
        long sum = a[0] + a[1] + a[2] + a[3];
        if (sum < 60_000) return null;
        StringBuilder b = new StringBuilder();
        if (a[1] >= 60_000) b.append("Wi-Fi ").append(ScreenTime.hm(a[1]));
        if (a[2] >= 60_000) {
            if (b.length() > 0) b.append(" · ");
            b.append("мобильная ").append(ScreenTime.hm(a[2]));
            if (a[4] >= 60_000) b.append(" (слабый сигнал ").append(ScreenTime.hm(a[4])).append(')');
        }
        if (a[3] >= 60_000) { if (b.length() > 0) b.append(" · "); b.append("другая ").append(ScreenTime.hm(a[3])); }
        if (a[0] >= 60_000) { if (b.length() > 0) b.append(" · "); b.append("без сети ").append(ScreenTime.hm(a[0])); }
        return b.toString();
    }

    /** Совет по сети; null — сеть не похожа на причину. */
    static String hint(long[] a) {
        long sum = a[0] + a[1] + a[2] + a[3];
        if (sum < 30 * 60_000) return null;
        if (a[4] > sum * 0.3)
            return "много времени со слабым мобильным сигналом — модем усиленно ищет сеть и не даёт телефону уснуть";
        if (a[2] > sum * 0.5)
            return "простой в основном на мобильной сети — она тратит заметно больше Wi-Fi";
        return null;
    }

    /** Текущий тип сети по умолчанию. */
    static int current(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return type(c);
        } catch (Throwable e) { return NONE; }
    }

    static int type(NetworkCapabilities c) {
        if (c == null) return NONE;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WIFI;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return MOBILE;
        return OTHER;
    }

    /** Слабый мобильный сигнал (уровень 0–1 из 4); null — неизвестно. */
    static Boolean weakSignal(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            android.telephony.SignalStrength s = ((TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE)).getSignalStrength();
            return s == null ? null : s.getLevel() <= 1;
        } catch (Throwable e) { return null; }
    }

    static synchronized void save(Context ctx) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) b.append(total[i]).append(',').append(period[i]).append(';');
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("acc", b.toString()).apply();
    }

    static synchronized void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String[] parts = p.getString("acc", "").split(";");
        for (int i = 0; i < 5 && i < parts.length; i++) {
            String[] tp = parts[i].split(",");
            if (tp.length != 2) continue;
            try { total[i] = Long.parseLong(tp[0]); period[i] = Long.parseLong(tp[1]); } catch (NumberFormatException ignored) { }
        }
        lastEl = -1; net = -1; screenOn = null; // что было, пока служба не работала, — неизвестно
    }

    /** Сброс статистики «с начала слежения» (текущее состояние сети сохраняется). */
    static synchronized void resetTotals(Context ctx) {
        java.util.Arrays.fill(total, 0);
        if (ctx != null) save(ctx);
    }

    static synchronized void reset(Context ctx) {
        resetAll();
        if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
