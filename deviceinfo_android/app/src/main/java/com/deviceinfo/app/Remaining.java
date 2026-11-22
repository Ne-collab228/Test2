package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Устойчивая оценка «на сколько хватит батареи».
 *
 * Мгновенный ток скачет в разы (открыли приложение, включилась связь, процессор уснул), поэтому
 * оценка по нему прыгает «от 4 часов до минут». Здесь:
 *  1) берутся средние по 10-секундным корзинам: первая оценка — уже через 30 с (3 корзины),
 *     дальше окно растёт до 10 минут и оценка стабилизируется;
 *  2) отбрасываются сбои датчика (больше 8 А) и отрезки зарядки;
 *  3) усечённое среднее — без 15 % самых больших и самых малых значений;
 *  4) сглаживание во времени (постоянная ~3 мин) и показ с округлением; число на экране меняется,
 *     только если новая оценка отличается больше чем на 10 %.
 */
public final class Remaining {
    static final long WINDOW_MS = 10 * 60_000L, TAU_MS = 3 * 60_000L;
    /** Первая оценка — через 30 с (3 корзины по 10 с); полное окно — 10 мин (60 корзин). */
    static final int MIN_POINTS = 3, FULL_POINTS = 60;
    static final double MAX_MA = 8000, MIN_MA = 5, HYST = 0.10;

    private static double ema = Double.NaN, shown = Double.NaN;
    private static long emaT = -1;
    private static int lastPoints;

    /** Устойчивый расход, мА (положительный) по точкам тока со знаком (− разряд); NaN — мало данных. */
    static double robustDrainMa(List<Double> pts) {
        if (pts == null) return Double.NaN;
        List<Double> v = new ArrayList<>();
        for (double d : pts) {
            if (Double.isNaN(d) || d >= 0) continue;   // зарядка / ноль
            double a = -d;
            if (a < MIN_MA || a > MAX_MA) continue;     // сбой датчика
            v.add(a);
        }
        lastPoints = v.size();
        if (v.size() < MIN_POINTS) return Double.NaN;
        Collections.sort(v);
        int cut = (int) Math.floor(v.size() * 0.15);
        double s = 0; int n = 0;
        for (int i = cut; i < v.size() - cut; i++) { s += v.get(i); n++; }
        return n == 0 ? Double.NaN : s / n;
    }

    /** Сглаживание во времени; разрыв больше 10 мин — начать заново. */
    static synchronized double smooth(double ma, long nowMs) {
        if (Double.isNaN(ma)) return ema;
        if (Double.isNaN(ema) || emaT < 0 || nowMs - emaT > WINDOW_MS || nowMs < emaT) {
            ema = ma; emaT = nowMs; return ema;
        }
        double tau = lastPoints < FULL_POINTS ? 30_000 : TAU_MS;
        double a = 1 - Math.exp(-(nowMs - emaT) / tau);
        ema += a * (ma - ema);
        emaT = nowMs;
        return ema;
    }

    /** Показываемое значение меняется, только если новое отличается больше чем на 10 %. */
    static synchronized double stable(double hours) {
        if (Double.isNaN(hours)) return shown;
        if (Double.isNaN(shown) || Math.abs(hours - shown) > HYST * shown) shown = hours;
        return shown;
    }

    /** Часы → текст с округлением: до 1 ч — 5 мин, до 10 ч — 10 мин, больше — 30 мин. */
    static String fmt(double hours) {
        if (Double.isNaN(hours) || hours <= 0) return null;
        double min = hours * 60;
        int step = hours < 1 ? 5 : (hours < 10 ? 10 : 30);
        long m = Math.max(step, Math.round(min / step) * step);
        long h = m / 60, r = m % 60;
        if (h >= 48) return Util.f("≈ %d ч (%.1f сут)", h, m / 1440.0);
        if (h == 0) return "≈ " + r + " мин";
        return r == 0 ? "≈ " + h + " ч" : "≈ " + h + " ч " + r + " мин";
    }

    /** Итог: часы до разряда (сглаженные, устойчивые) или NaN, пока мало данных. */
    static double hoursLeft(double capMah, double levelPct, long wallNow, long elapsedNow) {
        if (capMah <= 0 || Double.isNaN(levelPct)) return Double.NaN;
        double ma = smooth(robustDrainMa(BatteryMeter.currentPoints(wallNow, WINDOW_MS)), elapsedNow);
        if (Double.isNaN(ma) || ma < MIN_MA) return Double.NaN;
        return stable(capMah * levelPct / 100.0 / ma);
    }

    static synchronized double drainMa() { return ema; }

    /** Окно ещё не набрало 10 минут — оценка уточняется. */
    static synchronized boolean settling() { return lastPoints < FULL_POINTS; }

    /** Текст для экрана: оценка, средний расход и пометка, пока окно меньше 10 мин. */
    static String text(double hours) {
        String f = fmt(hours);
        if (f == null) return null;
        return f + Util.f(" (расход ≈ %.0f мА", drainMa()) + (settling() ? ", уточняется" : "") + ")";
    }

    /** Тесты / зарядка: сбросить состояние. */
    static synchronized void reset() { ema = Double.NaN; shown = Double.NaN; emaT = -1; lastPoints = 0; }
}
