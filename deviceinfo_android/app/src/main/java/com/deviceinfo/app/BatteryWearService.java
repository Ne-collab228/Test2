package com.deviceinfo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Фоновый замер износа батареи (кулон-счёт). Тот же BatteryMeter, что и в приложении; пока служба
 * работает (running), приложение свой tick пропускает — двойного счёта нет.
 */
public class BatteryWearService extends Service {
    static final String ACTION_STOP = "com.deviceinfo.app.action.STOP_METER";
    static final String ACTION_FINISH = "com.deviceinfo.app.action.FINISH_PRECISE";
    static final String EXTRA_PRECISE = "precise";
    static final String CH_METER = "meter", CH_RESULT = "meter_result";
    static final int ID_FG = 1, ID_FINAL = 2, ID_RESULT_BASE = 100;
    static final int TARGET = 3;
    static final long PERIOD_MS = 3_000, NOTIFY_EVERY_MS = 30_000, MAX_RUN_MS = 12L * 3600_000,
            MAX_PRECISE_MS = 30L * 3600_000;

    /** Служба активна (приложение по нему пропускает BatteryMeter.tick). */
    static volatile boolean running;
    /** Сколько замеров получено за этот запуск. */
    static volatile int runCount;
    /** Идёт точный замер (один длинный разряд), а не быстрые 3 × 5 %. */
    static volatile boolean precise;
    /** Состояние точного замера (для экрана приложения). */
    static volatile PreciseMeter.State preciseState;
    private volatile boolean finishRequested;
    /** Тесты: не запускать реальный цикл опроса. */
    static boolean disableLoop;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private final List<Double> runCaps = new ArrayList<>();
    private long lastNotifyMs = -1;
    private boolean finished;
    double designMah;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        try { designMah = new BatteryPage().designMah(this); } catch (Throwable ignored) { }
        NotificationManager nm = nm();
        nm.createNotificationChannel(new NotificationChannel(CH_METER, "Замер износа (идёт)", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_RESULT, "Результаты замера", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (!running) { startFg(buildOngoing(0, 0)); }
            finish();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
            if (!running) { startFg(buildOngoing(0, 0)); finish(); return START_NOT_STICKY; }
            if (precise) finishRequested = true; else finish();
            return START_STICKY;
        }
        if (intent != null && !running) { // свежий запуск; null — перезапуск системой, продолжаем
            runCount = 0; runCaps.clear();
            precise = intent.getBooleanExtra(EXTRA_PRECISE, false);
            preciseState = precise ? new PreciseMeter.State() : null;
            if (precise) PreciseMeter.clearAbort(this);
        }
        startFg(buildOngoing(0, 0));
        if (!running) {
            running = true;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deviceinfo:meter");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(precise ? MAX_PRECISE_MS : MAX_RUN_MS);
            } catch (Throwable ignored) { }
            if (!disableLoop) {
                thread = new HandlerThread("battery-meter");
                thread.start();
                handler = new Handler(thread.getLooper());
                handler.post(loop);
                handler.postDelayed(this::finish, precise ? MAX_PRECISE_MS : MAX_RUN_MS);
            }
        }
        return START_STICKY;
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (finished) return;
            try {
                if (precise) {
                    double[] r = BatteryMeter.read(BatteryWearService.this);
                    if (r != null) onSample(android.os.SystemClock.elapsedRealtime(), (int) r[0], (int) r[1], (int) r[2], r[3]);
                } else {
                    BatteryMeter.Measurement m = BatteryMeter.sample(BatteryWearService.this, designMah);
                    handleResult(m, android.os.SystemClock.elapsedRealtime());
                }
            } catch (Throwable ignored) { }
            if (!finished && handler != null) handler.postDelayed(this, PERIOD_MS);
        }
    };

    /** Тестовая точка входа: подаёт отсчёт вместо чтения системы. */
    synchronized void onSample(long nowMs, int level, int status, double currentMa) {
        onSample(nowMs, level, status, status == 2 ? 1 : 0, currentMa);
    }

    synchronized void onSample(long nowMs, int level, int status, int plugged, double currentMa) {
        if (finished) return;
        if (precise) { preciseSample(nowMs, level, status, plugged, currentMa); return; }
        BatteryMeter.Measurement m = BatteryMeter.onSample(this, System.currentTimeMillis(), nowMs, level,
                status, plugged, currentMa, designMah);
        handleResult(m, nowMs);
    }

    private synchronized void handleResult(BatteryMeter.Measurement m, long nowMs) {
        if (finished) return;
        if (m != null) {
            runCaps.add(m.capacityMah);
            runCount = runCaps.size();
            getSharedPreferences(BatteryMeter.PREFS, MODE_PRIVATE).edit().putInt("run_count", runCount).apply();
            nm().notify(ID_RESULT_BASE + runCount, resultNotification(
                    "Ёмкость батареи измерена: " + Util.f("%.0f мА·ч", m.capacityMah) + ", " + wearText(m.capacityMah)
                            + " (замер " + runCount + " из " + TARGET + ")"));
            lastNotifyMs = nowMs;
            nm().notify(ID_FG, buildOngoing(0, 0, true));
            if (runCount >= TARGET) { finish(); return; }
        }
        if (lastNotifyMs < 0 || nowMs - lastNotifyMs >= NOTIFY_EVERY_MS || nowMs < lastNotifyMs) {
            lastNotifyMs = nowMs;
            nm().notify(ID_FG, buildOngoing(0, 0, true));
        }
    }

    private void preciseSample(long nowMs, int level, int status, int plugged, double currentMa) {
        PreciseMeter.State st = preciseState;
        if (st == null) { st = new PreciseMeter.State(); preciseState = st; }
        // быстрые замеры тоже копятся (тихо), ток идёт в график потребления
        BatteryMeter.onSample(this, System.currentTimeMillis(), nowMs, level, status, plugged, currentMa, designMah);
        boolean discharging = plugged == 0 && status != 2 && status != 5;
        PreciseMeter.Result r = PreciseMeter.step(st, nowMs, level, discharging, currentMa, finishRequested);
        finishRequested = false;
        if (r != null) {
            PreciseMeter.save(this, r);
            nm().notify(ID_FINAL, resultNotification("Точный замер готов: " + Util.f("%.0f мА·ч", r.capacityMah)
                    + ", " + wearText(r.capacityMah) + Util.f(" (точность ±%.0f %%, разряд на %d %%)", r.accuracyPct, r.delta)));
            finish();
            return;
        }
        if (st.done) {
            PreciseMeter.saveAbort(this, st.abort);
            nm().notify(ID_FINAL, resultNotification("Точный замер прерван: " + st.abort));
            finish();
            return;
        }
        if (lastNotifyMs < 0 || nowMs - lastNotifyMs >= NOTIFY_EVERY_MS || nowMs < lastNotifyMs) {
            lastNotifyMs = nowMs;
            nm().notify(ID_FG, buildOngoing(0, 0, true));
        }
    }

    /** Текст прогресса точного замера (общий для уведомления и экрана). */
    static String preciseProgressText(PreciseMeter.State st) {
        double[] p = PreciseMeter.progress(st);
        if (p[0] == 0) return "ждём, когда уровень заряда снизится на 1 % (старт не выше " + PreciseMeter.MAX_START
                + " %, зарядку не подключать)";
        String t = Util.f("пройдено %.0f из %.0f %%, накоплено %.0f мА·ч", p[1], p[2], p[3]);
        if (!Double.isNaN(p[4]) && p[1] >= 3) t += Util.f(" · сейчас ≈ %.0f мА·ч", p[4]);
        if (p[1] >= PreciseMeter.MIN_DELTA) t += " · можно завершить";
        return t;
    }

    private String wearText(double cap) {
        if (designMah <= 0) return "износ неизвестен";
        double w = Math.max(0, Math.min(100, 100 * (1 - cap / designMah)));
        return Util.f("износ ≈ %.0f %%", w);
    }

    private synchronized void finish() {
        if (finished) return;
        finished = true;
        if (!precise && !runCaps.isEmpty()) {
            List<Double> v = new ArrayList<>(runCaps);
            Collections.sort(v);
            double med = v.get(v.size() / 2);
            nm().notify(ID_FINAL, resultNotification("Замер завершён: медиана " + Util.f("%.0f мА·ч", med)
                    + ", " + wearText(med) + " (замеров: " + runCaps.size() + ")"));
        }
        running = false;
        precise = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        finished = true;
        running = false;
        precise = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ---------- уведомления ----------
    private NotificationManager nm() { return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_FG, n);
    }

    private Notification buildOngoing(double a, double b) { return buildOngoing(a, b, false); }

    private Notification buildOngoing(double a, double b, boolean live) {
        if (precise) return buildPreciseOngoing();
        double[] pr = BatteryMeter.progress();
        String text = Util.f("накоплено %.1f мА·ч за %.0f %%, нужно %d %%", pr[1], pr[2], BatteryMeter.NEED_LEVELS)
                + " · замеров " + runCount + "/" + TARGET;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BatteryWearService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_METER)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Идёт замер износа батареи")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .build();
    }

    private Notification buildPreciseOngoing() {
        String text = preciseProgressText(preciseState);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BatteryWearService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH_METER)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Точный замер ёмкости батареи")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Отменить", stop).build());
        double[] p = PreciseMeter.progress(preciseState);
        if (p[0] > 0) {
            b.setProgress((int) p[2], (int) p[1], false);
            if (p[1] >= PreciseMeter.MIN_DELTA) {
                PendingIntent fin = PendingIntent.getService(this, 3,
                        new Intent(this, BatteryWearService.class).setAction(ACTION_FINISH),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new Notification.Action.Builder(null, "Завершить сейчас", fin).build());
            }
        }
        return b.build();
    }

    private Notification resultNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_RESULT)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Износ батареи")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build();
    }
}
