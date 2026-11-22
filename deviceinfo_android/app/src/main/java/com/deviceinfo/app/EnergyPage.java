package com.deviceinfo.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Вкладка «Энергия»: расход с экраном выключенным / включённым, глубокий сон, оценка. */
public class EnergyPage implements Page {
    @Override public String title() { return "Энергия"; }

    static final String[] GRADE = {"—", "Отлично", "Нормально", "Повышенный", "Что-то будит телефон"};
    static final String[] SLEEP = {"—", "Отлично", "Нормально", "Часто просыпается", "Почти не спит"};

    static int gradeColor(int g) {
        return g == 1 ? Section.OK : g == 2 ? Section.OK : g == 3 ? Section.WARN : g == 4 ? Section.CRIT : Section.MUTED;
    }

    /** Техпроцесс чипа по коду MediaTek / названию (нм), 0 — неизвестен. */
    static int processNm(String soc) {
        String s = soc == null ? "" : soc.toUpperCase(java.util.Locale.ROOT);
        if (s.contains("MT6768") || s.contains("MT6769") || s.contains("MT6785")) return 12;
        if (s.contains("MT6789")) return 6;
        if (s.contains("MT6833") || s.contains("MT6835")) return 7;
        return 0;
    }

    private BatteryPage bp;

    @Override public void build(Context ctx, Section out) {
        EnergyStats s = EnergyService.stats;
        if (s == null) s = EnergyStats.load(ctx);
        boolean on = EnergyService.running;
        if (bp == null) bp = new BatteryPage();
        PreciseMeter.Result pr = PreciseMeter.last(ctx);
        double cap = pr != null ? pr.capacityMah : bp.designMah(ctx);
        String capSrc = pr != null ? "по точному замеру" : "по паспорту";

        double offR = s.offRate(), onR = s.onRate();
        int g = EnergyStats.offGrade(offR);
        out.header("Энергоэффективность", g == 0 ? null : GRADE[g], gradeColor(g));
        if (on) {
            out.row("Слежение за расходом", s.since > 0 ? "идёт с " + new java.text.SimpleDateFormat("dd.MM HH:mm",
                    java.util.Locale.US).format(new java.util.Date(s.since)) : "идёт", Section.OK);
            out.action("energy_stop", "Выключить слежение");
        } else {
            out.row("Слежение за расходом", "выключено", Section.MUTED);
            out.action("energy_start", "Включить слежение", true);
            out.note("Слежение работает в фоне и замечает, когда экран включается и выключается. Телефон при этом "
                    + "спит как обычно — служба не держит его бодрым и сама почти ничего не тратит. Первая оценка "
                    + "простоя — после 2 ч с выключенным экраном (удобнее всего — ночь), экрана — после 30 мин использования.");
        }

        // --- экран выключен ---
        out.header("Экран выключен (простой)", Double.isNaN(offR) ? null : Util.f("%.1f %%/ч", offR), gradeColor(g));
        out.row("Накоплено", Util.duration(s.off.ms / 1000.0) + Util.f(" · ушло %.0f %%", s.off.pct));
        if (Double.isNaN(offR)) {
            out.row("Расход", Util.f("сбор данных (%.1f из 2 ч, нужно ≥ 2 %%)", s.off.ms / 3600_000.0), Section.MUTED);
        } else {
            double ma = EnergyStats.ma(offR, cap);
            out.row("Расход", Util.f("%.2f %%/ч", offR) + (Double.isNaN(ma) ? "" : Util.f(" ≈ %.0f мА", ma)), gradeColor(g));
            out.row("Оценка", GRADE[g], gradeColor(g));
            double h = EnergyStats.hoursFull(offR);
            if (!Double.isNaN(h)) out.row("Хватит в простое", Util.f("≈ %.0f ч (%.1f сут)", h, h / 24));
        }
        double sleep = 1 - s.off.awakeShare();
        int sg = EnergyStats.sleepGrade(s.off.ms >= 30 * 60_000L ? sleep : Double.NaN);
        if (sg > 0) {
            out.bar("Глубокий сон в простое", Util.f("%.0f %%", sleep * 100), sleep * 100, gradeColor(sg));
            out.row("Сон", SLEEP[sg], gradeColor(sg));
        } else out.row("Глубокий сон в простое", "сбор данных (нужно ≥ 30 мин)", Section.MUTED);
        if (s.lastNight != null) {
            double[] n = s.lastNight;
            double rate = n[2] / (n[1] / 3600_000.0);
            out.row("Последний долгий простой", new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
                    .format(new java.util.Date((long) n[0])) + " · " + Util.duration(n[1] / 1000.0)
                    + Util.f(" · −%.0f %% (%.1f %%/ч) · сон %.0f %%", n[2], rate, 100 * (1 - n[3])));
        }
        out.note("Норма для простоя: до ~1 %/ч (телефон почти всё время в глубоком сне). Если расход выше 3 %/ч или "
                + "сон ниже 50 % — какое-то приложение будит телефон: проверьте Настройки → Батарея → Потребление.");

        // --- экран включён ---
        int og = onR <= 10 ? 1 : onR <= 16 ? 2 : onR <= 25 ? 3 : 4;
        out.header("Экран включён", Double.isNaN(onR) ? null : Util.f("%.0f %%/ч", onR), Section.NORMAL);
        out.row("Накоплено", Util.duration(s.on.ms / 1000.0) + Util.f(" · ушло %.0f %%", s.on.pct));
        if (Double.isNaN(onR)) {
            out.row("Расход", Util.f("сбор данных (%.0f из 30 мин, нужно ≥ 3 %%)", s.on.ms / 60_000.0), Section.MUTED);
        } else {
            double ma = EnergyStats.ma(onR, cap);
            out.row("Расход", Util.f("%.1f %%/ч", onR) + (Double.isNaN(ma) ? "" : Util.f(" ≈ %.0f мА", ma)), gradeColor(og));
            double h = EnergyStats.hoursFull(onR);
            if (!Double.isNaN(h)) out.row("Полного заряда хватит на", Util.f("≈ %.1f ч экрана", h), Section.INFO);
        }

        // --- прямо сейчас ---
        out.header("Прямо сейчас");
        double[] r = BatteryMeter.read(ctx);
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int mv = b == null ? -1 : b.getIntExtra("voltage", -1);
        double v = mv > 0 ? (mv > 100 ? mv / 1000.0 : mv) : Double.NaN;
        double ma = r == null ? Double.NaN : r[3] * BatteryMeter.sign();
        if (!Double.isNaN(ma)) {
            out.row("Ток", Util.f("%+.0f мА", ma));
            if (!Double.isNaN(v)) out.row("Мощность", Util.f("%.2f Вт", Math.abs(v * ma / 1000.0)));
            if (ma < -5 && r != null && cap > 0)
                out.row("При таком расходе хватит на", Util.duration(cap * r[0] / 100.0 / Math.abs(ma) * 3600), Section.INFO);
            out.note("Сейчас приложение открыто и экран включён — это расход «в работе», не в простое.");
        } else out.row("Ток", null);
        out.row("Ёмкость для расчёта", cap > 0 ? Util.f("%.0f мА·ч (%s)", cap, capSrc) : null,
                pr != null ? Section.NORMAL : Section.MUTED);

        // --- железо ---
        out.header("Что влияет на расход");
        String soc = Util.prop("ro.soc.model", "");
        int nm = processNm(soc + " " + Util.prop("ro.board.platform", ""));
        if (nm > 0) out.row("Техпроцесс чипа", nm + " нм" + (nm >= 10 ? " — старый, под нагрузкой тратит больше современных 4–6 нм" : ""),
                nm >= 10 ? Section.WARN : Section.OK);
        out.note("Больше всего экономят: частота экрана 60 Гц вместо 120 Гц, яркость ниже, ограничение графики/FPS в играх, "
                + "запрет фоновой активности лишним приложениям.");
        if (on || s.off.ms > 0 || s.on.ms > 0) out.action("energy_reset", "Сбросить статистику");
    }
}
