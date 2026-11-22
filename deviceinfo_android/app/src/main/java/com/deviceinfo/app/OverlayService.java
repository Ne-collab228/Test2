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
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Плавающее окно поверх игр: заряд, ток, мощность, температура и частота ядер. Без wake lock. */
public class OverlayService extends Service {
    static final String ACTION_STOP = "com.deviceinfo.app.action.STOP_OVERLAY";
    static final String PREFS = "overlay";
    static final String CH = "overlay";
    static final int ID_FG = 3;

    /** Окно показано (для переключателя в настройках). */
    static volatile boolean running;
    /** Тесты: не добавлять реальное окно. */
    static boolean disableWindow;

    private WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private Handler handler;
    private boolean compact, screenOn = true, receiverOn;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            if (screenOn && handler != null) handler.postDelayed(this, Theme.period(OverlayService.this));
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (handler == null) return;
            handler.removeCallbacks(tick);
            screenOn = !Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            if (screenOn) handler.post(tick);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- чистые функции ----------

    static String fmtMa(double ma) {
        if (Double.isNaN(ma)) return "— мА";
        long r = Math.round(ma);
        return (r < 0 ? "−" : (r > 0 ? "+" : "")) + Math.abs(r) + " мА";
    }

    static String fmtWatts(double volts, double ma) {
        if (Double.isNaN(ma) || Double.isNaN(volts) || volts <= 0) return "— Вт";
        return String.format(Locale.US, "%.1f Вт", Math.abs(volts * ma) / 1000.0);
    }

    static String fmtTemp(double c) {
        return Double.isNaN(c) ? "— °C" : String.format(Locale.US, "%.1f °C", c);
    }

    static String fmtGhz(long khz) {
        return khz <= 0 ? "—" : String.format(Locale.US, "%.2f ГГц", khz / 1e6);
    }

    /** «0–5: 1.70 ГГц · 6–7: 2.00 ГГц»; clusters — {первое, последнее ядро, ...}, curKhz — по кластерам. */
    static String freqText(List<long[]> clusters, long[] curKhz) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clusters.size(); i++) {
            long[] c = clusters.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(c[0] == c[1] ? String.valueOf(c[0]) : c[0] + "–" + c[1]).append(": ")
                    .append(fmtGhz(i < curKhz.length ? curKhz[i] : 0));
        }
        return sb.toString();
    }

    /** Текст окна: compact — одна строка «% · мА · °C», иначе несколько строк. */
    static String format(int pct, double ma, double volts, double tempC, String freq, boolean compact) {
        String p = pct < 0 ? "—%" : pct + "%";
        if (compact) return p + " · " + fmtMa(ma) + " · " + fmtTemp(tempC);
        StringBuilder sb = new StringBuilder();
        sb.append(p).append(" · ").append(fmtMa(ma)).append('\n')
                .append(fmtWatts(volts, ma)).append(" · ").append(fmtTemp(tempC));
        if (freq != null && !freq.isEmpty()) sb.append('\n').append(freq);
        return sb.toString();
    }

    // ---------- служба ----------

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            startFg();
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();
        if (!running) {
            if (!disableWindow && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
            running = true;
            compact = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("compact", false);
            if (!disableWindow) {
                try { addWindow(); } catch (Throwable t) { running = false; stopSelf(); return START_NOT_STICKY; }
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);
                registerReceiver(screen, f);
                receiverOn = true;
                handler.post(tick);
            }
        }
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Плавающее окно", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Плавающее окно включено")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Закрыть", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_FG, n);
    }

    private void addWindow() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        final float d = getResources().getDisplayMetrics().density;
        view = new TextView(this);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding((int) (8 * d), (int) (5 * d), (int) (8 * d), (int) (5 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0101018);
        bg.setCornerRadius(8 * d);
        view.setBackground(bg);
        view.setText("…");

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("x", (int) (8 * d));
        lp.y = sp.getInt("y", (int) (60 * d));

        final int slop = (int) (6 * d);
        view.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float ax = e.getRawX() - dx, ay = e.getRawY() - dy;
                        if (!moved && Math.abs(ax) < slop && Math.abs(ay) < slop) return true;
                        moved = true;
                        lp.x = sx + (int) ax; lp.y = sy + (int) ay;
                        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        } else {
                            compact = !compact;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("compact", compact).apply();
                            update();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(view, lp);
    }

    private void update() {
        if (view == null) return;
        try {
            double[] r = BatteryMeter.read(this);
            Intent it = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            double volts = Double.NaN, temp = Double.NaN;
            if (it != null) {
                int mv = it.getIntExtra("voltage", -1), t = it.getIntExtra("temperature", Integer.MIN_VALUE);
                if (mv > 0) volts = mv / 1000.0;
                if (t != Integer.MIN_VALUE) temp = t / 10.0;
            }
            String freq = null;
            if (!compact) {
                List<long[]> pol = ThermalPage.cpuLimits();
                long[] cur = new long[pol.size()];
                for (int i = 0; i < cur.length; i++) cur[i] = ThermalPage.curFreq(pol.get(i)[0], pol.get(i)[1]);
                freq = freqText(pol, cur);
            }
            view.setText(format(r == null ? -1 : (int) r[0], r == null ? Double.NaN : r[3] * BatteryMeter.sign(), volts, temp, freq, compact));
        } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (receiverOn) { try { unregisterReceiver(screen); } catch (Throwable ignored) { } receiverOn = false; }
        if (view != null && wm != null) { try { wm.removeView(view); } catch (Throwable ignored) { } }
        view = null;
        super.onDestroy();
    }
}
