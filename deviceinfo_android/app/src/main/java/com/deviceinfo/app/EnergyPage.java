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

        // вместе с идущим сейчас отрезком (только пока служба работает — иначе он устарел)
        long el = android.os.SystemClock.elapsedRealtime(), upt = android.os.SystemClock.uptimeMillis();
        double lvlNow = Double.NaN;
        double[] rNow = BatteryMeter.read(ctx);
        if (rNow != null) lvlNow = rNow[0];
        EnergyStats.Totals offT = on ? s.withOpen(false, el, upt, lvlNow) : s.off;
        EnergyStats.Totals onT = on ? s.withOpen(true, el, upt, lvlNow) : s.on;
        long offOpen = on ? s.openMs(false, el) : 0, onOpen = on ? s.openMs(true, el) : 0;
        double offR = EnergyStats.offRate(offT), onR = EnergyStats.onRate(onT);
        int g = EnergyStats.offGrade(offR);
        out.header("Энергоэффективность", g == 0 ? null : GRADE[g], gradeColor(g));
        if (on) {
            out.row("Слежение за расходом", s.since > 0 ? "идёт с " + new java.text.SimpleDateFormat("dd.MM HH:mm",
                    java.util.Locale.US).format(new java.util.Date(s.since)) : "идёт", Section.OK);
            out.action("energy_stop", "Выключить слежение");
            boolean tok = !ctx.getSharedPreferences(MainActivity.GH, Context.MODE_PRIVATE).getString("token", "").isEmpty();
            if (!tok) out.row("Отчёты в GitHub", "нет токена (Настройки GitHub)", Section.MUTED);
            else if (!HourlyReport.enabled(ctx)) out.row("Отчёты в GitHub", "выключены в настройках", Section.MUTED);
            else {
                out.row("Отчёты в GitHub", "каждый час · " + HourlyReport.status(ctx));
                out.action("hourly_now", "Отправить часовой отчёт сейчас");
            }
        } else {
            out.row("Слежение за расходом", "выключено", Section.MUTED);
            out.action("energy_start", "Включить слежение", true);
            out.note("Слежение работает в фоне и замечает, когда экран включается и выключается. Телефон при этом "
                    + "спит как обычно — служба не держит его бодрым и сама почти ничего не тратит. Первая оценка "
                    + "простоя — после 2 ч с выключенным экраном (удобнее всего — ночь), экрана — после 30 мин использования.");
        }

        // --- экран выключен ---
        out.header("Экран выключен (простой)", Double.isNaN(offR) ? null : Util.f("%.1f %%/ч", offR), gradeColor(g));
        out.row("Накоплено", Util.duration(offT.ms / 1000.0) + Util.f(" · ушло %.0f %%", offT.pct));
        if (offOpen > 0) out.row("  сейчас", "экран выключен " + Util.duration(offOpen / 1000.0), Section.MUTED);
        if (Double.isNaN(offR)) {
            out.row("Расход", Util.f("сбор данных (%.1f из 2 ч, нужно ≥ 2 %%)", offT.ms / 3600_000.0), Section.MUTED);
        } else {
            double ma = EnergyStats.ma(offR, cap);
            out.row("Расход", Util.f("%.2f %%/ч", offR) + (Double.isNaN(ma) ? "" : Util.f(" ≈ %.0f мА", ma)), gradeColor(g));
            out.row("Оценка", GRADE[g], gradeColor(g));
            double h = EnergyStats.hoursFull(offR);
            if (!Double.isNaN(h)) out.row("Хватит в простое", Util.f("≈ %.0f ч (%.1f сут)", h, h / 24));
        }
        // сон — только по отрезкам простоя от 10 мин без музыки (на коротких процессор не успевает уснуть)
        long longMs = s.longOffMs, longAwake = s.longOffAwakeMs;
        if (offOpen >= EnergyStats.LONG_MS) { longMs += offOpen; longAwake += Math.max(0, offT.awakeMs - s.off.awakeMs); }
        double sleep = longMs < EnergyStats.MIN_LONG_MS ? Double.NaN : Math.min(1, Math.max(0, 1 - longAwake / (double) longMs));
        int sg = EnergyStats.sleepGrade(sleep);
        if (sg > 0) {
            out.bar("Глубокий сон в простое", Util.f("%.0f %%", sleep * 100), sleep * 100, gradeColor(sg));
            out.row("Сон", SLEEP[sg], gradeColor(sg));
        } else out.row("Глубокий сон в простое", Util.f("сбор данных (%.0f из 30 мин)", longMs / 60_000.0), Section.MUTED);
        out.note("Сон считается только по промежуткам простоя от 10 минут без музыки — сразу после выключения экрана "
                + "телефон ещё несколько минут досинхронизирует данные, это нормально.");
        if (s.lastNight != null) {
            double[] n = s.lastNight;
            double rate = n[2] / (n[1] / 3600_000.0);
            out.row("Последний долгий простой", new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
                    .format(new java.util.Date((long) n[0])) + " · " + Util.duration(n[1] / 1000.0)
                    + Util.f(" · −%.0f %% (%.1f %%/ч) · сон %.0f %%", n[2], rate, 100 * (1 - n[3])));
        }
        out.note("Норма для простоя: до ~1 %/ч (телефон почти всё время в глубоком сне). Если расход выше 3 %/ч или "
                + "сон ниже 30 % — какое-то приложение будит телефон: проверьте Настройки → Батарея → Потребление.");

        // --- экран выключен, играла музыка ---
        EnergyStats.Totals au = s.offAudio;
        long auOpen = on ? s.openAudioMs(el) : 0;
        if (au.ms > 0 || auOpen > 0) {
            double auR = au.ms >= 30 * 60_000L && au.pct >= 2 ? au.rate() : Double.NaN;
            out.header("Экран выключен, играла музыка", Double.isNaN(auR) ? null : Util.f("%.1f %%/ч", auR), Section.NORMAL);
            out.row("Накоплено", Util.duration(au.ms / 1000.0) + Util.f(" · ушло %.0f %%", au.pct));
            if (auOpen > 0) out.row("  сейчас", "играет музыка " + Util.duration(auOpen / 1000.0), Section.MUTED);
            if (!Double.isNaN(auR)) {
                double ma = EnergyStats.ma(auR, cap);
                out.row("Расход", Util.f("%.1f %%/ч", auR) + (Double.isNaN(ma) ? "" : Util.f(" ≈ %.0f мА", ma)));
            }
            out.note("Музыка (в том числе по Bluetooth) не даёт телефону уснуть, поэтому это время в простой не входит "
                    + "и на оценку не влияет.");
        }

        // --- экран включён ---
        int og = onR <= 10 ? 1 : onR <= 16 ? 2 : onR <= 25 ? 3 : 4;
        out.header("Экран включён", Double.isNaN(onR) ? null : Util.f("%.0f %%/ч", onR), Section.NORMAL);
        out.row("Накоплено", Util.duration(onT.ms / 1000.0) + Util.f(" · ушло %.0f %%", onT.pct));
        if (onOpen > 0) out.row("  сейчас", "экран включён " + Util.duration(onOpen / 1000.0), Section.MUTED);
        if (Double.isNaN(onR)) {
            out.row("Расход", Util.f("сбор данных (%.0f из 30 мин, нужно ≥ 3 %%)", onT.ms / 60_000.0), Section.MUTED);
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
            if (r != null && cap > 0 && r[2] == 0) {
                String left = Remaining.text(Remaining.hoursLeft(cap, r[0], System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtime()));
                out.row("Хватит при таком использовании", left != null ? left : "считаю… (≈ 30 с)",
                        left != null ? Section.INFO : Section.MUTED);
            } else if (r != null && cap > 0) {
                String full = Remaining.chargeText(Remaining.hoursToFull(cap, r[0], System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtime()));
                out.row("Зарядится примерно через", full != null ? full : "считаю… (≈ 30 с)",
                        full != null ? Section.OK : Section.MUTED);
            }
            if (r != null && r[2] != 0)
                out.note("Идёт зарядка: «+» — ток, который доходит до батареи (то, что тратит сам телефон, уже вычтено).");
            else if (BgSampler.foreground)
                out.note("Сейчас приложение открыто и экран включён — это расход «в работе», не в простое.");
            else out.note("Снято в фоне: это расход при текущем использовании телефона, не в простое.");
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
