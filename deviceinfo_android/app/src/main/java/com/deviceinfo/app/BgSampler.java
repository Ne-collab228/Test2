package com.deviceinfo.app;

import android.content.Context;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Лёгкие замеры в фоне: ток батареи, загрузка и частоты процессора, температура, память — раз в 10 с,
 * только пока экран включён и приложение не на экране (на экране замеры делает само окно, раз в секунду).
 * Wake lock не берётся: когда экран выключен и телефон засыпает, замеры просто не идут.
 * Нужны, чтобы «Хватит примерно на», графики и отчёт были готовы, даже если приложение свернули.
 */
final class BgSampler {
    static final long PERIOD_MS = 10_000;

    /** Окно приложения на экране — тогда фоновые замеры не нужны. */
    static volatile boolean foreground;

    private static List<Page> pages;
    private static final AtomicBoolean busy = new AtomicBoolean();

    private BgSampler() { }

    static synchronized List<Page> pages() {
        if (pages == null) {
            pages = new ArrayList<>();
            pages.add(new BatteryPage());
            pages.add(new CpuPage());
            pages.add(new ThermalPage());
            pages.add(new MemoryPage());
        }
        return pages;
    }

    /** Нужен ли фоновый замер сейчас. */
    static boolean wanted(Context ctx) {
        if (foreground) return false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable e) { return false; }
    }

    /** Один замер (не на главном потоке). Параллельные вызовы пропускаются. */
    static void tick(Context ctx) {
        if (!busy.compareAndSet(false, true)) return;
        try {
            for (Page p : pages()) {
                if (p instanceof BatteryPage && BatteryWearService.running) continue; // замер ведёт служба
                try { synchronized (p) { p.tick(ctx); } } catch (Throwable ignored) { }
            }
        } finally { busy.set(false); }
    }
}
