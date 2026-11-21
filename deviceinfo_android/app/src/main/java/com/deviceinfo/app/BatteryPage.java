package com.deviceinfo.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Вкладка «Батарея»: порт BatteryTab/get_battery_info из aida_py. */
public class BatteryPage implements Page {
    @Override public String title() { return "Батарея"; }

    static final String CALIB = "battery_calib";
    static final double[] NO_UNITS = {Double.NaN, Double.NaN, -1};

    private BatteryHistory history;

    private static boolean valid(long v) {
        return v != Integer.MIN_VALUE && v != Integer.MAX_VALUE && v != Long.MIN_VALUE && v != Long.MAX_VALUE;
    }

    /**
     * Нормализация единиц (чистая функция, без Android).
     *
     * @param counterRaw сырое CHARGE_COUNTER (Integer.MIN_VALUE = нет данных)
     * @param currentRaw сырое CURRENT_NOW / CURRENT_AVERAGE (MIN_VALUE или 0 = нет данных)
     * @param levelPct   уровень, %
     * @param magicOs    true для Honor MagicOS
     * @return {counter_mAh (NaN если нет), current_mA (NaN если нет), unitFlag}:
     *         unitFlag 1 = счётчик в мА·ч (ток в мА), 0 = счётчик в µA·ч (ток в µA), -1 = единицы не определены
     *         (тогда ток в мА для MagicOS при |x| <= 20000, иначе эвристика |x| > 20000 => µA)
     */
    static double[] normalize(int counterRaw, int currentRaw, double levelPct, boolean magicOs) {
        int flag = -1;
        double counter = Double.NaN;
        if (valid(counterRaw) && counterRaw > 0 && levelPct > 0) {
            double perFull = counterRaw * 100.0 / Math.max(levelPct, 1);
            boolean mah = perFull < 500000;
            flag = mah ? 1 : 0;
            counter = mah ? counterRaw : counterRaw / 1000.0;
        }
        return new double[]{counter, currentToMa(currentRaw, flag, magicOs), flag};
    }

    /**
     * Ток в мА. Единица тока определяется НЕЗАВИСИМО от счётчика заряда: на Honor X6c
     * CHARGE_COUNTER приходит в µA·ч (3500000), а CURRENT_NOW — в мА (−675). Телефон с
     * работающим приложением (экран включён) потребляет десятки–тысячи мА, поэтому
     * |x| < 10000 — это мА, а |x| ≥ 10000 — µA. flag/magicOs оставлены для совместимости.
     */
    static double currentToMa(int x, int flag, boolean magicOs) {
        if (!valid(x) || x == 0) return Double.NaN;
        return Math.abs(x) >= 10000 ? x / 1000.0 : x;
    }

    /** Единица, в которой прошивка отдаёт ток (для подписи). */
    static String currentUnit(int x) {
        if (!valid(x) || x == 0) return "нет данных";
        return Math.abs(x) >= 10000 ? "µA" : "мА";
    }

    /** Голос за знак: +1 совпал со статусом, -1 нет, 0 нет вывода. status: BatteryManager.BATTERY_STATUS_*. */
    static int signVote(double currentMa, int status) {
        if (Double.isNaN(currentMa) || Math.abs(currentMa) < 5) return 0;
        if (status == 3) return currentMa < 0 ? 1 : -1;
        if (status == 2) return currentMa > 0 ? 1 : -1;
        return 0;
    }

    /** Медиана (список не пуст). */
    static double median(List<Double> l) {
        List<Double> s = new ArrayList<>(l);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    static final Object[][] KNOWN = {{"nic-lx", 5300}, {"x6c", 5300}, {"jdy-lx", 5200}};

    /** Паспортная ёмкость из справочника по имени модели (нижний регистр), 0 если нет. */
    static int knownCapacity(String names) {
        String n = names == null ? "" : names.toLowerCase();
        for (Object[] k : KNOWN) if (n.contains((String) k[0])) return (Integer) k[1];
        return 0;
    }

    static String fmtTime(long ms) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    private static boolean isMagicOs(Context ctx) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            if (pm.hasSystemFeature("com.hihonor.system.feature")
                    || pm.hasSystemFeature("com.hihonor.software.features.honor")) return true;
        } catch (Throwable ignored) { }
        return "HONOR".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private double[] designCache;   // {mAh}
    private String designSrc;

    private void design(Context ctx) {
        if (designCache != null) return;
        double cap = 0; String src = null;
        try {
            Class<?> c = Class.forName("com.android.internal.os.PowerProfile");
            Constructor<?> k = c.getConstructor(Context.class);
            Object pp = k.newInstance(ctx);
            cap = ((Number) c.getMethod("getBatteryCapacity").invoke(pp)).doubleValue();
            if (cap <= 0) {
                Method m = c.getMethod("getAveragePower", String.class);
                cap = ((Number) m.invoke(pp, "battery.capacity")).doubleValue();
            }
            if (cap > 500 && cap < 30000) src = "PowerProfile (power_profile.xml)"; else cap = 0;
        } catch (Throwable ignored) { cap = 0; }
        if (src == null) {
            for (String p : new String[]{"ro.config.hw_battery_capacity", "ro.boot.battery_capacity",
                    "persist.sys.battery_capacity", "ro.vendor.battery.capacity"}) {
                String v = Util.prop(p);
                try {
                    double d = Double.parseDouble(v.trim());
                    if (d > 500 && d < 30000) { cap = d; src = "свойство " + p; break; }
                } catch (Throwable ignored) { }
            }
        }
        if (src == null) {
            String names = Build.MODEL + " " + Util.prop("ro.product.marketname", "") + " "
                    + Util.prop("ro.product.model", "") + " " + Util.prop("ro.config.marketing_name", "");
            int k = knownCapacity(names);
            if (k > 0) { cap = k; src = "Справочник модели"; }
        }
        designCache = new double[]{cap};
        designSrc = src;
    }

    private static String ru(Map<Integer, String> m, int k, String def) { String v = m.get(k); return v == null ? def : v; }

    @Override public void build(Context ctx, Section out) {
        Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (it == null) {
            out.header("Состояние");
            out.row("Батарея", "Не обнаружена или нет доступа", Section.MUTED);
            return;
        }
        Map<String, String> raw = new LinkedHashMap<>();
        int level = it.getIntExtra("level", -1), scale = it.getIntExtra("scale", 100);
        if (scale <= 0) scale = 100;
        double lvl = level >= 0 ? 100.0 * level / scale : Double.NaN;
        int status = it.getIntExtra("status", 1), healthI = it.getIntExtra("health", 1);
        int plugged = it.getIntExtra("plugged", 0);
        int tRaw = it.getIntExtra("temperature", Integer.MIN_VALUE);
        int vRaw = it.getIntExtra("voltage", -1);
        String tech = it.getStringExtra("technology");
        boolean low = it.getBooleanExtra("battery_low", false);
        int cyc = it.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
        int cs = it.getIntExtra("android.os.extra.CHARGING_STATUS", -1);
        int mcc = it.getIntExtra("max_charging_current", -1), mcv = it.getIntExtra("max_charging_voltage", -1);
        raw.put("level", "" + level); raw.put("scale", "" + scale); raw.put("status", "" + status);
        raw.put("health", "" + healthI); raw.put("plugged", "" + plugged); raw.put("temperature", "" + tRaw);
        raw.put("voltage", "" + vRaw); raw.put("cycle_count", "" + cyc); raw.put("charging_status", "" + cs);
        raw.put("max_charging_current", "" + mcc); raw.put("max_charging_voltage", "" + mcv);

        Double temp = (tRaw > -1000 && tRaw < 1000 && tRaw != Integer.MIN_VALUE) ? tRaw / 10.0 : null;
        Double volt = null;
        if (vRaw > 0) volt = vRaw < 100 ? (double) vRaw : (vRaw > 100000 ? vRaw / 1e6 : vRaw / 1000.0);

        int ccRaw = Integer.MIN_VALUE, curRaw = Integer.MIN_VALUE, avgRaw = Integer.MIN_VALUE;
        long energy = Long.MIN_VALUE; long chargeMs = -1; boolean isCharging = false;
        Integer soh = null;
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                ccRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                curRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                avgRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                isCharging = bm.isCharging();
                if (Build.VERSION.SDK_INT >= 28) chargeMs = bm.computeChargeTimeRemaining();
                try {
                    int s = bm.getIntProperty(10);
                    if (valid(s) && s > 0 && s <= 100) soh = s;
                } catch (SecurityException ignored) { } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        raw.put("charge_counter", "" + ccRaw); raw.put("current_now", "" + curRaw);
        raw.put("current_average", "" + avgRaw); raw.put("energy_counter", "" + energy);
        raw.put("charge_time_remaining_ms", "" + chargeMs); raw.put("is_charging", "" + isCharging);
        raw.put("state_of_health", soh == null ? "недоступно" : "" + soh);

        boolean magic = isMagicOs(ctx);
        double[] nu = normalize(ccRaw, curRaw, Double.isNaN(lvl) ? 0 : lvl, magic);
        int flag = (int) nu[2];
        double counter = nu[0];
        double curMa = nu[1], avgMa = currentToMa(avgRaw, flag, magic);

        SharedPreferences calib = ctx.getSharedPreferences(CALIB, Context.MODE_PRIVATE);
        int votes = calib.getInt("sign_votes", 0);
        int vote = signVote(curMa, status);
        if (vote != 0) {
            votes = Math.max(-20, Math.min(20, votes + vote));
            calib.edit().putInt("sign_votes", votes).apply();
        }
        // Устойчивое решение по накопленным голосам; пока голосов мало — решаем по
        // текущему замеру, чтобы с первого обновления знак не противоречил статусу.
        boolean inverted = votes <= -3 || (votes < 3 && vote < 0);
        int sign = inverted ? -1 : 1;
        if (!Double.isNaN(curMa)) curMa *= sign;
        if (!Double.isNaN(avgMa)) avgMa *= sign;

        design(ctx);
        double designMah = designCache[0];

        // Проверка счётчика: у части прошивок (Honor X6c: ровно 3500000) он не меняется
        // вместе с уровнем — это заглушка, по ней нельзя оценивать ёмкость.
        boolean counterStatic = calib.getBoolean("cc_static", false);
        if (!Double.isNaN(counter) && !Double.isNaN(lvl)) {
            float lastLvl = calib.getFloat("cc_last_level", -1f);
            float lastCc = calib.getFloat("cc_last_val", -1f);
            if (lastLvl < 0) {
                calib.edit().putFloat("cc_last_level", (float) lvl).putFloat("cc_last_val", (float) counter).apply();
            } else if (Math.abs(lvl - lastLvl) >= 2) {
                counterStatic = Math.abs(counter - lastCc) < 1;
                calib.edit().putFloat("cc_last_level", (float) lvl).putFloat("cc_last_val", (float) counter)
                        .putBoolean("cc_static", counterStatic).apply();
                if (counterStatic) calib.edit().putString("fcc", "[]").apply();
            }
        }
        // FCC
        List<Double> fccs = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(calib.getString("fcc", "[]"));
            for (int i = 0; i < a.length(); i++) fccs.add(a.getDouble(i));
        } catch (Throwable ignored) { }
        if (!counterStatic && !Double.isNaN(counter) && !Double.isNaN(lvl) && lvl >= 80) {
            double fcc = counter * 100.0 / lvl;
            if (designMah <= 0 || (fcc > 0.4 * designMah && fcc < 1.25 * designMah)) {
                if (fccs.isEmpty() || Math.abs(fccs.get(fccs.size() - 1) - fcc) >= 1) {
                    fccs.add(Math.round(fcc * 10) / 10.0);
                    while (fccs.size() > 30) fccs.remove(0);
                    JSONArray a = new JSONArray();
                    for (double d : fccs) try { a.put(d); } catch (Throwable ignored) { }
                    calib.edit().putString("fcc", a.toString()).apply();
                }
            }
        }
        double fullMah = fccs.isEmpty() || counterStatic ? Double.NaN : median(fccs);

        // История
        boolean chargingNow = status == 2 || status == 5;
        long now = System.currentTimeMillis();
        if (history == null) history = new BatteryHistory(ctx);
        if (!Double.isNaN(lvl))
            history.add(now, lvl, status, chargingNow, temp == null ? Double.NaN : temp, volt == null ? Double.NaN : volt);

        // --- Состояние ---
        out.header("Состояние");
        if (!Double.isNaN(lvl)) out.bar("Уровень заряда", Util.f("%.0f %%", lvl), lvl,
                lvl <= 15 ? Section.CRIT : (lvl <= 30 ? Section.WARN : Section.OK));
        Map<Integer, String> st = new LinkedHashMap<>();
        st.put(2, "Заряжается"); st.put(3, "Разряжается"); st.put(4, "Не заряжается"); st.put(5, "Полностью заряжена");
        out.row("Статус", ru(st, status, "Неизвестно"), chargingNow ? Section.OK : Section.NORMAL);
        Map<Integer, String> pl = new LinkedHashMap<>();
        pl.put(1, "Сеть (AC)"); pl.put(2, "USB"); pl.put(4, "Беспроводная"); pl.put(8, "Док-станция");
        out.row("Источник питания", plugged == 0 ? "Батарея" : ru(pl, plugged, "Другой"));
        Map<Integer, String> hl = new LinkedHashMap<>();
        hl.put(1, "Неизвестно"); hl.put(2, "Хорошее"); hl.put(3, "Перегрев"); hl.put(4, "Износ (неисправна)");
        hl.put(5, "Перенапряжение"); hl.put(6, "Сбой"); hl.put(7, "Переохлаждение");
        out.row("Состояние (здоровье)", ru(hl, healthI, "Неизвестно"), healthI == 2 ? Section.OK : Section.WARN);
        out.row("Технология", tech);
        out.row("Температура", temp == null ? null : Util.f("%.1f °C", temp), temp == null ? 0 : Section.temp(temp + 20));
        out.row("Напряжение", volt == null ? null : Util.f("%.3f В", volt));
        if (!Double.isNaN(curMa)) out.row("Ток", Util.f("%+.0f мА (%s)", curMa, curMa > 0 ? "+ в батарею" : "− из батареи"),
                curMa > 0 ? Section.OK : Section.NORMAL);
        else out.row("Ток", null);
        if (!Double.isNaN(avgMa)) out.row("Средний ток", Util.f("%+.0f мА", avgMa));
        if (volt != null && !Double.isNaN(curMa)) {
            double p = volt * curMa / 1000.0;
            out.row("Мощность", Util.f("%.2f Вт (%s)", Math.abs(p), p > 0 ? "зарядка" : "расход"));
        }
        if (!Double.isNaN(lvl) && low) out.row("Низкий заряд", "Да (системный флаг)", Section.WARN);

        // --- Зарядка ---
        out.header("Зарядка");
        if (cs > 0) {
            Map<Integer, String> cm = new LinkedHashMap<>();
            cm.put(1, "Обычная"); cm.put(2, "Остановлена: холодно"); cm.put(3, "Остановлена: перегрев");
            cm.put(4, "Щадящий режим (Long Life)"); cm.put(5, "Адаптивная (умная) зарядка");
            out.row("Режим зарядки", ru(cm, cs, "Код " + cs));
        } else out.row("Режим зарядки", "Прошивка не сообщает", Section.MUTED);
        if (mcc > 0 && chargingNow) {
            double a = mcc / 1e6, v = (mcv > 0 ? mcv : 5000000) / 1e6;
            out.row("Зарядное устройство (лимит)", Util.f("%.2f А × %.1f В ≈ %.1f Вт", a, v, a * v));
        } else if (mcc > 0) out.row("Лимит тока зарядки", Util.f("%.2f А", mcc / 1e6));
        if (mcv > 0) out.row("Макс. напряжение зарядки", Util.f("%.1f В", mcv / 1e6));
        if (chargeMs > 0) out.row("До полной (оценка Android)", Util.duration(chargeMs / 1000.0), Section.INFO);
        out.row("isCharging()", isCharging ? "Да" : "Нет");
        double[] est = BatteryHistory.estimate(history.samples, now, Double.isNaN(lvl) ? 0 : lvl, chargingNow);
        if (est[0] == 2) out.row("До полной (оценка по истории)", Util.duration(est[1]), Section.INFO);
        double cr = BatteryHistory.chargeRate(history.samples, now);
        out.row("Скорость зарядки", Double.isNaN(cr) ? null : Util.f("%.1f %%/ч", cr));
        if (valid(energy) && energy > 0) out.row("Энергия", Util.f("%.2f Вт·ч", energy / 1e9));

        // --- Ёмкость и износ ---
        out.header("Ёмкость и износ");
        if (!Double.isNaN(counter)) out.row("Текущий заряд (счётчик)", Util.f("%.0f мА·ч", counter)
                + (counterStatic ? " — не меняется, заглушка прошивки" : ""), counterStatic ? Section.MUTED : 0);
        if (designMah > 0 && !Double.isNaN(lvl) && (counterStatic || Double.isNaN(counter)))
            out.row("Остаток (по уровню)", Util.f("≈ %.0f мА·ч", designMah * lvl / 100.0));
        out.row("Паспортная ёмкость", designMah > 0 ? Util.f("%.0f мА·ч", designMah) : null);
        if (designSrc != null) out.row("  источник", designSrc, Section.MUTED);
        if (!Double.isNaN(fullMah)) {
            out.row("Фактическая ёмкость", Util.f("%.0f мА·ч", fullMah));
            out.row("  источник", "Оценка: счётчик заряда / уровень (" + fccs.size() + " замер.)", Section.MUTED);
        } else if (counterStatic) {
            out.row("Фактическая ёмкость", "Не определяется: счётчик контроллера не меняется", Section.MUTED);
        } else out.row("Фактическая ёмкость", "Нужен заряд ≥ 80 % (оценка по счётчику)", Section.MUTED);
        double healthPct = Double.NaN, wear = Double.NaN;
        if (!Double.isNaN(fullMah) && designMah > 0) {
            wear = Math.max(0, 100 * (1 - fullMah / designMah));
            healthPct = Math.min(100, 100 * fullMah / designMah);
        }
        if (soh != null) healthPct = soh;
        if (!Double.isNaN(healthPct)) out.bar("Здоровье батареи", Util.f("%.0f %%", healthPct), healthPct,
                healthPct < 60 ? Section.CRIT : (healthPct < 80 ? Section.WARN : Section.OK));
        if (!Double.isNaN(wear)) out.row("Износ", Util.f("%.1f %%", wear), Section.level(wear, 20, 40));
        out.row("Циклы зарядки", cyc > 0 ? "" + cyc : "Прошивка не сообщает", cyc > 0 ? 0 : Section.MUTED);
        if (cyc > 0 && Double.isNaN(wear)) {
            // Типичный Li-ion телефона: ~80 % ёмкости к 800 циклам (линейная оценка).
            double cycWear = Math.min(40, cyc / 800.0 * 20.0);
            out.row("Износ по циклам (ориентир)", Util.f("≈ %.0f %%", cycWear), Section.level(cycWear, 20, 40));
            out.note("Ориентир: обычно ~20 % износа к 800 полным циклам. Точное значение прошивка не отдаёт.");
        }
        out.row("Единицы тока", currentUnit(curRaw));
        out.row("Единицы счётчика заряда", flag == 1 ? "мА·ч" : (flag == 0 ? "µA·ч" : "нет данных"));
        out.row("Платформа", magic ? "Honor MagicOS" : "Android");
        out.row("Знак тока", inverted ? "Инвертирован прошивкой (исправлено)" : "Стандартный (+ в батарею / − из батареи)",
                inverted ? Section.WARN : Section.MUTED);
        if (!Double.isNaN(fullMah) && designMah > 0)
            out.note("Фактическая ёмкость оценивается по счётчику заряда контроллера. "
                    + "На MediaTek он часто равен профилю батареи (константа), тогда износ не виден.");

        // --- Время работы ---
        out.header("Время работы");
        out.row("С момента включения", Util.duration(SystemClock.elapsedRealtime() / 1000.0));
        if (est[0] == 1) out.row("Осталось работы (оценка)", Util.duration(est[1]), Section.OK);
        else if (est[0] == 2) out.row("До полной зарядки (оценка)", Util.duration(est[1]), Section.INFO);
        else out.row("Оценка времени", "Сбор данных…", Section.MUTED);
        double dr = BatteryHistory.dischargeRate(history.samples, now);
        out.row("Скорость разряда", Double.isNaN(dr) ? null : Util.f("%.1f %%/ч", dr));
        if (!chargingNow)
            for (BatteryHistory.Session s : history.allSessions())
                if (!s.open) { out.row("С последней зарядки", Util.duration((now - s.end) / 1000.0)); break; }
        double[] sb = BatteryHistory.stability(history.samples, now);
        if (sb[0] == 0) {
            out.row("Стабильность разряда", "Сбор данных…", Section.MUTED);
            out.note("Нужно не менее 4 замеров при разрядке (≈5 мин)");
        } else {
            out.row("Стабильность разряда", sb[0] == 1 ? "Стабильная" : (sb[0] == 2 ? "Умеренная" : "Нестабильная"),
                    sb[0] == 1 ? Section.OK : (sb[0] == 2 ? Section.WARN : Section.CRIT));
            out.row("Коэффициент вариации", Util.f("%.0f %%", sb[1]));
            out.note(Util.f("Средний расход %.1f %%/ч, скачков: %d", sb[2], (int) sb[3])
                    + (Double.isNaN(sb[4]) ? "" : Util.f(", разброс напряжения ±%.0f мВ", sb[4])));
        }

        // --- История зарядок ---
        out.header("История зарядок");
        List<String[]> rows = new ArrayList<>();
        for (BatteryHistory.Session s : history.allSessions()) {
            if (rows.size() >= 10) break;
            rows.add(new String[]{fmtTime(s.start),
                    Util.f("%.0f » %s", s.from, s.open ? "…" : Util.f("%.0f %%", s.to)),
                    Util.duration(((s.open ? now : s.end) - s.start) / 1000.0)});
        }
        if (rows.isEmpty())
            out.note("Зарядок пока не зафиксировано. Подключите зарядное устройство, пока приложение открыто.");
        else out.table(new String[]{"Начало", "Заряд", "Длительность"}, rows, new float[]{0.4f, 0.3f, 0.3f});
        out.note("Замеры делаются раз в минуту, пока приложение открыто (сохранено: " + history.samples.size() + ").");

        // --- Сырые значения ---
        out.header("Сырые значения (диагностика)");
        List<String[]> rr = new ArrayList<>();
        for (Map.Entry<String, String> e : raw.entrySet()) rr.add(new String[]{e.getKey(), e.getValue()});
        out.table(new String[]{"Поле", "Значение"}, rr, new float[]{0.55f, 0.45f});
    }
}
