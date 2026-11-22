package com.deviceinfo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Слежение за расходом: только системные события (экран вкл/выкл, изменение батареи, зарядка).
 * Wake lock не держит и таймеров не заводит — телефон спит как обычно, сама служба почти
 * ничего не тратит.
 */
public class EnergyService extends Service {
    static final String ACTION_STOP = "com.deviceinfo.app.action.STOP_ENERGY";
    static final String CH = "energy";
    static final int ID = 10;
    static final String PREF_ON = "monitor_on";

    static volatile boolean running;
    /** Текущая статистика (пока служба работает), иначе null — тогда читается из настроек. */
    static volatile EnergyStats stats;

    private BroadcastReceiver rx;
    /** Фоновые замеры раз в 10 с, пока экран включён (без wake lock). */
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (!running) return;
            final Context app = getApplicationContext();
            if (BgSampler.wanted(app)) { try { exec.execute(() -> BgSampler.tick(app)); } catch (Throwable ignored) { } }
            h.postDelayed(this, BgSampler.PERIOD_MS);
        }
    };
    private long lastNotify;

    @Override public IBinder onBind(Intent intent) { return null; }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(EnergyStats.PREFS + "_cfg", MODE_PRIVATE).getBoolean(PREF_ON, false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(EnergyStats.PREFS + "_cfg", MODE_PRIVATE).edit().putBoolean(PREF_ON, on).apply();
    }

    static void start(Context c) {
        setEnabled(c, true);
        try { c.startForegroundService(new Intent(c, EnergyService.class)); } catch (Throwable ignored) { }
    }

    static void stop(Context c) {
        setEnabled(c, false);
        try { c.startService(new Intent(c, EnergyService.class).setAction(ACTION_STOP)); } catch (Throwable ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CH, "Слежение за расходом", NotificationManager.IMPORTANCE_MIN));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startFg(build());
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            EnergyStats s = EnergyStats.load(this);
            s.open = null; // что было между остановкой и стартом — неизвестно
            stats = s;
            ChargeLog.load(this);
            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { onEvent(i); }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            Intent sticky = registerReceiver(rx, f);
            if (sticky != null) onEvent(sticky);
            h.post(sample);
        }
        return START_STICKY;
    }

    private synchronized void onEvent(Intent i) {
        // экран выключен — замеры не нужны (телефон всё равно заснёт), включён — возобновить
        if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) h.removeCallbacks(sample);
        else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) { h.removeCallbacks(sample); h.post(sample); }
        try {
            EnergyStats s = stats;
            if (s == null) return;
            Intent b = Intent.ACTION_BATTERY_CHANGED.equals(i.getAction()) ? i
                    : registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int level = b.getIntExtra("level", -1), scale = b.getIntExtra("scale", 100);
            double lvl = level < 0 ? Double.NaN : 100.0 * level / Math.max(1, scale);
            boolean plugged = b.getIntExtra("plugged", 0) != 0;
            if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) plugged = true;
            if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) plugged = false;
            boolean screen = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) screen = false;
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) screen = true;
            boolean busy = BatteryWearService.running && BatteryWearService.precise;
            s.event(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), System.currentTimeMillis(),
                    lvl, screen, plugged, busy);
            s.save(this);
            // тест зарядки в фоне
            double[] r = BatteryMeter.read(this);
            int mv = b.getIntExtra("voltage", -1);
            double v = mv > 0 ? (mv > 100 ? mv / 1000.0 : mv) : Double.NaN;
            double ma = r == null ? Double.NaN : r[3] * BatteryMeter.sign();
            boolean wasCharging = ChargeLog.charging;
            if (ChargeLog.add(System.currentTimeMillis(), lvl, v, ma, plugged) || (wasCharging && !plugged))
                ChargeLog.save(this);
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotify > 60_000) {
                lastNotify = now;
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, build());
            }
        } catch (Throwable ignored) { }
    }

    /** Короткий текст для уведомления. */
    static String summaryText(EnergyStats s) {
        if (s == null) return "собираю данные";
        double off = s.offRate(), on = s.onRate();
        String a = Double.isNaN(off) ? Util.f("экран выкл: сбор (%.1f из 2 ч)", s.off.ms / 3600_000.0)
                : Util.f("экран выкл: %.1f %%/ч", off);
        String b = Double.isNaN(on) ? Util.f("вкл: сбор (%.0f из 30 мин)", s.on.ms / 60_000.0)
                : Util.f("вкл: %.0f %%/ч", on);
        return a + " · " + b;
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 21, new Intent(this, EnergyService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String t = summaryText(stats);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("Слежение за расходом батареи")
                .setContentText(t)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Выключить", stop).build())
                .build();
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID, n);
    }

    private synchronized void shutdown() {
        setEnabled(this, false);
        EnergyStats s = stats;
        if (s != null) {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            double lvl = Double.NaN;
            if (b != null) { int l = b.getIntExtra("level", -1); if (l >= 0) lvl = 100.0 * l / Math.max(1, b.getIntExtra("scale", 100)); }
            s.close(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), lvl);
            s.save(this);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        exec.shutdownNow();
        EnergyStats s = stats;
        if (s != null) s.save(this);
        stats = null;
        try { if (rx != null) unregisterReceiver(rx); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /** Перезапуск после перезагрузки телефона и после обновления приложения, если слежение было включено. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if ((Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) && enabled(c)) {
                try { c.startForegroundService(new Intent(c, EnergyService.class)); } catch (Throwable ignored) { }
            }
        }
    }
}
