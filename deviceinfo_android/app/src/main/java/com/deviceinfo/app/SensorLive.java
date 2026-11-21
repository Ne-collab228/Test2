package com.deviceinfo.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Живые показания датчиков: слушатель на отдельном потоке + форматирование значений. */
final class SensorLive {
    private SensorLive() { }

    static final class Reading {
        volatile float[] values;
        volatile long lastMs;
        volatile int count;
        volatile float rateHz = -1;
        long winStartMs, winCount;
    }

    static final ConcurrentHashMap<String, Reading> READINGS = new ConcurrentHashMap<>();
    private static HandlerThread thread;
    private static SensorManager registeredOn;
    private static long startedMs;

    static String key(Sensor s) { return s.getName() + "#" + s.getType(); }

    static boolean oneShot(Sensor s) {
        try { return s.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT; } catch (Throwable t) { return false; }
    }

    static long startedMs() { return startedMs; }

    private static final SensorEventListener LISTENER = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            String k = key(e.sensor);
            Reading r = READINGS.get(k);
            if (r == null) {
                r = new Reading();
                Reading prev = READINGS.putIfAbsent(k, r);
                if (prev != null) r = prev;
            }
            long now = SystemClock.elapsedRealtime();
            r.values = e.values.clone();
            r.lastMs = now;
            r.count++;
            if (r.winStartMs == 0) { r.winStartMs = now; r.winCount = 0; }
            r.winCount++;
            long dt = now - r.winStartMs;
            if (dt >= 2000) {
                r.rateHz = r.winCount * 1000f / dt;
                r.winStartMs = now;
                r.winCount = 0;
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    static synchronized void ensureRegistered(SensorManager sm, List<Sensor> list) {
        if (thread != null || sm == null) return;
        try {
            HandlerThread t = new HandlerThread("sensors");
            t.start();
            Handler h = new Handler(t.getLooper());
            for (Sensor s : list) {
                if (oneShot(s)) continue;
                try { sm.registerListener(LISTENER, s, SensorManager.SENSOR_DELAY_UI, h); } catch (Throwable ignored) { }
            }
            thread = t;
            registeredOn = sm;
            startedMs = SystemClock.elapsedRealtime();
        } catch (Throwable ignored) { }
    }

    static synchronized void stop() {
        if (thread == null) return;
        try { if (registeredOn != null) registeredOn.unregisterListener(LISTENER); } catch (Throwable ignored) { }
        try { thread.quitSafely(); } catch (Throwable ignored) { }
        thread = null;
        registeredOn = null;
        READINGS.clear();
    }

    static boolean permissionMissing(Context ctx, int type) {
        if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return false;
        try {
            return ctx.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    // ---- чистые функции форматирования ----

    static String orientationName(int v) {
        switch (v) {
            case 0: return "портрет";
            case 1: return "альбомная влево";
            case 2: return "перевёрнутый портрет";
            case 3: return "альбомная вправо";
            default: return "неизвестно (" + v + ")";
        }
    }

    private static double mag(float[] v) {
        double s = 0;
        int n = Math.min(3, v.length);
        for (int i = 0; i < n; i++) s += v[i] * v[i];
        return Math.sqrt(s);
    }

    private static String xyz(float[] v, String unit) {
        if (v.length < 3) return Util.f("%.2f", v.length > 0 ? v[0] : 0f) + " " + unit;
        return Util.f("%.2f / %.2f / %.2f %s", v[0], v[1], v[2], unit);
    }

    static String format(int type, float[] v, float maxRange, int eventCount) {
        if (v == null || v.length == 0) return "—";
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return xyz(v, "м/с²") + " (|a| = " + Util.f("%.2f", mag(v)) + ")";
            case Sensor.TYPE_GYROSCOPE:
                return xyz(v, "рад/с");
            case Sensor.TYPE_MAGNETIC_FIELD:
                return xyz(v, "мкТл") + " (|B| = " + Util.f("%.1f", mag(v)) + ")";
            case Sensor.TYPE_LIGHT:
                return Util.f("%.0f лк", v[0]);
            case Sensor.TYPE_PROXIMITY:
                return Util.f("%.1f см", v[0]) + (v[0] < maxRange ? " (близко)" : " (далеко)");
            case Sensor.TYPE_PRESSURE:
                return Util.f("%.2f гПа", v[0]);
            case Sensor.TYPE_STEP_COUNTER:
                return Util.f("%.0f шагов с перезагрузки", v[0]);
            case Sensor.TYPE_STEP_DETECTOR:
                return eventCount + " шагов за сеанс";
            case 27: // TYPE_DEVICE_ORIENTATION
                return orientationName((int) v[0]);
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                try {
                    float[] m = new float[9], o = new float[3];
                    SensorManager.getRotationMatrixFromVector(m, v);
                    SensorManager.getOrientation(m, o);
                    return Util.f("азимут %.0f°, наклон %.0f°, крен %.0f°",
                            Math.toDegrees(o[0]), Math.toDegrees(o[1]), Math.toDegrees(o[2]));
                } catch (Throwable t) { return joined(v); }
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                return Util.f("%.1f °C", v[0]);
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                return Util.f("%.0f %%", v[0]);
            case Sensor.TYPE_HEART_RATE:
                return Util.f("%.0f уд/мин", v[0]);
            default:
                return joined(v);
        }
    }

    static String joined(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && i < 6; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(Util.f("%.3g", v[i]));
        }
        return sb.toString();
    }

    static String typeLabel(int type, String fallback) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "Акселерометр";
            case Sensor.TYPE_MAGNETIC_FIELD: return "Магнитометр";
            case Sensor.TYPE_GYROSCOPE: return "Гироскоп";
            case Sensor.TYPE_LIGHT: return "Освещённость";
            case Sensor.TYPE_PRESSURE: return "Барометр";
            case Sensor.TYPE_PROXIMITY: return "Приближение";
            case Sensor.TYPE_GRAVITY: return "Гравитация";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "Линейное ускорение";
            case Sensor.TYPE_ROTATION_VECTOR: return "Вектор поворота";
            case Sensor.TYPE_GAME_ROTATION_VECTOR: return "Игровой вектор поворота";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "Влажность";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "Температура";
            case Sensor.TYPE_STEP_COUNTER: return "Шагомер";
            case Sensor.TYPE_STEP_DETECTOR: return "Детектор шагов";
            case Sensor.TYPE_SIGNIFICANT_MOTION: return "Значимое движение";
            case Sensor.TYPE_HEART_RATE: return "Пульс";
            case 27: return "Ориентация устройства";
            default: return fallback;
        }
    }
}
