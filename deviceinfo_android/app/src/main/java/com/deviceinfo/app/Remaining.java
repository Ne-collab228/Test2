package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Устойчивая оценка «на сколько хватит батареи» и «через сколько зарядится».
 *
 * Мгновенный ток скачет в разы (открыли приложение, включилась связь, процессор уснул), поэтому
 * оценка по нему прыгает «от 4 часов до минут». Здесь:
 *  1) берутся средние по 10-секундным корзинам: первая оценка — уже через 30 с (3 корзины),
 *     дальше окно растёт до 10 минут и оценка стабилизируется;
 *  2) отбрасываются сбои датчика (больше 8 А) и точки другого направления (зарядка при разряде и наоборот);
 *  3) усечённое среднее — без 15 % самых больших и самых малых значений;
 *  4) сглаживание во времени (постоянная ~3 мин) и показ с округлением; число на экране меняется,
 *     только если новая оценка отличается больше чем на 10 %.
 *
 * Для зарядки учитывается, что после ~80 % телефон снижает ток (дозаряд постоянным напряжением):
 * последние 20 % идут медленнее.
 */
public final class Remaining {
    static final long WINDOW_MS = 10 * 60_000L, TAU_MS = 3 * 60_000L;
    /** Первая оценка — через 30 с (3 корзины по 10 с); полное окно — 10 мин (60 корзин). */
    static final int MIN_POINTS = 3, FULL_POINTS = 60;
    static final double MAX_MA = 8000, MIN_MA = 5, HYST = 0.10;
    /** Уровень, после которого ток зарядки снижается, и средний ток дозаряда относительно тока до него. */
    static final double TAPER_LEVEL = 80, TAPER_K = 0.45;

    /** Состояние одной оценки (разряд или зарядка). */
    static final class Est {
        double ema = Double.NaN, shown = Double.NaN;
        long emaT = -1;
        int lastPoints;

        /** Устойчивый ток, мА (положительный); charge — брать точки зарядки (+), иначе разряда (−). */
        synchronized double robust(List<Double> pts, boolean charge) {
            if (pts == null) return Double.NaN;
            List<Double> v = new ArrayList<>();
            for (double d : pts) {
                if (Double.isNaN(d) || (charge ? d <= 0 : d >= 0)) continue;
                double a = Math.abs(d);
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

        synchronized double smooth(double ma, long nowMs) {
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

        synchronized double stable(double hours) {
            if (Double.isNaN(hours)) return shown;
            if (Double.isNaN(shown) || Math.abs(hours - shown) > HYST * shown) shown = hours;
            return shown;
        }

        synchronized void reset() { ema = Double.NaN; shown = Double.NaN; emaT = -1; lastPoints = 0; }
    }

    static final Est DRAIN = new Est(), CHARGE = new Est();

    /** Устойчивый расход, мА (положительный) по точкам тока со знаком (− разряд); NaN — мало данных. */
    static double robustDrainMa(List<Double> pts) { return DRAIN.robust(pts, false); }

    /** Сглаживание во времени; разрыв больше 10 мин — начать заново. */
    static double smooth(double ma, long nowMs) { return DRAIN.smooth(ma, nowMs); }

    /** Показываемое значение меняется, только если новое отличается больше чем на 10 %. */
    static double stable(double hours) { return DRAIN.stable(hours); }

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
        double ma = DRAIN.smooth(DRAIN.robust(BatteryMeter.currentPoints(wallNow, WINDOW_MS), false), elapsedNow);
        if (Double.isNaN(ma) || ma < MIN_MA) return Double.NaN;
        return DRAIN.stable(capMah * levelPct / 100.0 / ma);
    }

    /**
     * Часы до 100 % при токе зарядки ma (мА) на уровне levelPct. До 80 % — ток постоянный,
     * дальше в среднем TAPER_K от тока до снижения. Если уровень уже выше 80 %, измеренный ток —
     * это уже сниженный ток, и до конца он в среднем ещё вдвое ниже.
     */
    static double hoursToFullFor(double capMah, double levelPct, double ma) {
        if (capMah <= 0 || Double.isNaN(levelPct) || Double.isNaN(ma) || ma < MIN_MA) return Double.NaN;
        if (levelPct >= 100) return 0;
        double h = 0;
        if (levelPct < TAPER_LEVEL) {
            h += capMah * (TAPER_LEVEL - levelPct) / 100.0 / ma;
            h += capMah * (100 - TAPER_LEVEL) / 100.0 / (ma * TAPER_K);
        } else {
            h += capMah * (100 - levelPct) / 100.0 / (ma * 0.5);
        }
        return h;
    }

    /** Итог: часы до полной зарядки (сглаженные, устойчивые) или NaN, пока мало данных; 0 — заряжен. */
    static double hoursToFull(double capMah, double levelPct, long wallNow, long elapsedNow) {
        if (capMah <= 0 || Double.isNaN(levelPct)) return Double.NaN;
        if (levelPct >= 100) return 0;
        double ma = CHARGE.smooth(CHARGE.robust(BatteryMeter.currentPoints(wallNow, WINDOW_MS), true), elapsedNow);
        if (Double.isNaN(ma) || ma < MIN_MA) return Double.NaN;
        return CHARGE.stable(hoursToFullFor(capMah, levelPct, ma));
    }

    static double drainMa() { synchronized (DRAIN) { return DRAIN.ema; } }

    static double chargeMa() { synchronized (CHARGE) { return CHARGE.ema; } }

    /** Окно ещё не набрало 10 минут — оценка уточняется. */
    static boolean settling() { synchronized (DRAIN) { return DRAIN.lastPoints < FULL_POINTS; } }

    static boolean chargeSettling() { synchronized (CHARGE) { return CHARGE.lastPoints < FULL_POINTS; } }

    /** Текст для экрана: оценка, средний расход и пометка, пока окно меньше 10 мин. */
    static String text(double hours) {
        String f = fmt(hours);
        if (f == null) return null;
        return f + Util.f(" (расход ≈ %.0f мА", drainMa()) + (settling() ? ", уточняется" : "") + ")";
    }

    /** Текст «через сколько зарядится»: null — мало данных. */
    static String chargeText(double hours) {
        if (!Double.isNaN(hours) && hours == 0) return "заряжен полностью";
        String f = fmt(hours);
        if (f == null) return null;
        return f + Util.f(" (ток зарядки ≈ %.0f мА", chargeMa()) + (chargeSettling() ? ", уточняется" : "") + ")";
    }

    /** Сбросить оценку разряда (началась зарядка) / тесты. */
    static void reset() { DRAIN.reset(); }

    /** Сбросить оценку зарядки (зарядку отключили). */
    static void resetCharge() { CHARGE.reset(); }
}
