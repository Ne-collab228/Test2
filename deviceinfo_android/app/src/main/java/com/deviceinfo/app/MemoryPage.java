package com.deviceinfo.app;

import android.app.ActivityManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemoryPage implements Page {
    private static final long GB = 1L << 30;
    private static final int[] STD = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64};

    @Override public String title() { return "Память"; }

    /** Номинальный объём в ГБ: округление вверх до стандартного (5.6 ГБ -> 6). */
    public static int nominalGb(long bytes) {
        double gb = bytes / (double) GB;
        for (int s : STD) if (gb <= s + 0.05) return s;
        return (int) Math.ceil(gb);
    }

    /** Номинальный объём накопителя: 128/256/512... (степень двойки, не меньше объёма). */
    static int nominalStorageGb(long bytes) {
        // StorageStats возвращает десятичные ГБ (1e9) на многих устройствах
        double gb = bytes / 1e9;
        int n = 8;
        while (n < gb * 0.98 && n < 16384) n *= 2;
        return n;
    }

    private static Map<String, Long> meminfo() {
        Map<String, Long> m = new HashMap<>();
        String s = Util.read("/proc/meminfo");
        if (s == null) return m;
        for (String line : s.split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String[] v = line.substring(c + 1).trim().split("\\s+");
            try { m.put(line.substring(0, c).trim(), Long.parseLong(v[0]) * 1024); } catch (Exception ignored) { }
        }
        return m;
    }

    static final Series USED = new Series(300, 120_000);
    private static volatile long usedAt = -1;

    static void recordUsed(double pct) {
        long t = android.os.SystemClock.elapsedRealtime();
        if (usedAt >= 0 && t - usedAt < 800) return;
        usedAt = t;
        USED.add(t, pct);
    }

    @Override public void tick(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) recordUsed((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem);
        } catch (Throwable ignored) { }
    }

    @Override public void build(Context ctx, Section out) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        Map<String, Long> m = meminfo();

        long total = mi.totalMem > 0 ? mi.totalMem : val(m, "MemTotal");
        long avail = mi.availMem > 0 ? mi.availMem : val(m, "MemAvailable");
        long used = total - avail;
        double usedPct = total > 0 ? used * 100.0 / total : 0;
        if (total > 0) recordUsed(usedPct);
        out.header("Оперативная память (RAM)", total > 0 ? Util.f("%.0f %%", usedPct) : null,
                Section.level(usedPct, 75, 90));
        out.row("Номинальный объём", nominalGb(total) + " ГБ");
        out.row("Доступно системе (видимо)", Util.bytes(total));
        out.bar("Использовано", Util.bytes(used) + " из " + Util.bytes(total),
                total > 0 ? used * 100.0 / total : 0, Section.level(total > 0 ? used * 100.0 / total : 0, 75, 90));
        out.chart("Использование RAM", Util.f("%.0f %%", usedPct),
                USED.values(android.os.SystemClock.elapsedRealtime()), 0, 100, "%", Section.level(usedPct, 75, 90));
        out.row("Свободно (доступно)", Util.bytes(avail));
        out.row("Порог малой памяти", Util.bytes(mi.threshold));
        out.row("Мало памяти", mi.lowMemory ? "Да" : "Нет", mi.lowMemory ? Section.CRIT : Section.OK);
        String[][] keys = {{"Cached", "Кэш"}, {"Buffers", "Буферы"}, {"Active", "Активная"}, {"Inactive", "Неактивная"},
                {"Shmem", "Общая (Shmem)"}, {"Slab", "Slab"}, {"MemFree", "Полностью свободно"}};
        for (String[] k : keys) if (m.containsKey(k[0])) out.row(k[1], Util.bytes(m.get(k[0])));
        if (m.isEmpty()) out.note("/proc/meminfo недоступен — показаны данные ActivityManager.");

        long swapT = val(m, "SwapTotal"), swapF = val(m, "SwapFree");
        out.header("Подкачка / ZRAM / RAM Turbo");
        if (swapT > 0) {
            long su = swapT - swapF;
            out.bar("Swap", Util.bytes(su) + " из " + Util.bytes(swapT), su * 100.0 / swapT,
                    Section.level(su * 100.0 / swapT, 70, 90));
            out.note("Расширенная память (RAM Turbo / ZRAM) на Honor использует часть накопителя или сжатый ZRAM как виртуальную RAM; "
                    + "это не увеличивает физическую память (" + nominalGb(total) + " ГБ).");
        } else {
            out.row("Swap", "Отключён или недоступен");
        }
        Long zram = Util.readLong("/sys/block/zram0/disksize");
        if (zram != null) out.row("ZRAM (размер диска)", Util.bytes(zram));
        String ext = Util.firstNonEmpty(Util.prop("ro.config.hw_ramturbo"), Util.prop("persist.sys.hn.ramturbo"));
        if (ext != null) out.row("RAM Turbo (свойство)", ext);
        String ve = Util.prop("persist.vendor.mtk_ram_expansion");
        if (ve == null) ve = Util.prop("ro.vendor.mtk_ram_expansion_support");
        if (ve != null) out.row("MTK RAM expansion", ve);

        out.header("Лимиты приложений");
        out.row("Класс памяти", am.getMemoryClass() + " МБ");
        out.row("Большой класс (largeHeap)", am.getLargeMemoryClass() + " МБ");
        out.row("Малая RAM (низкобюджетное)", am.isLowRamDevice() ? "Да" : "Нет");
        Runtime rt = Runtime.getRuntime();
        out.row("Куча приложения (max)", Util.bytes(rt.maxMemory()));
        out.row("Куча использовано", Util.bytes(rt.totalMemory() - rt.freeMemory()));

        buildStorage(ctx, out);
    }

    private static long val(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    private void buildStorage(Context ctx, Section out) {
        out.header("Накопитель");
        try {
            StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            long t = ssm.getTotalBytes(StorageManager.UUID_DEFAULT);
            long f = ssm.getFreeBytes(StorageManager.UUID_DEFAULT);
            out.row("Номинальный объём", nominalStorageGb(t) + " ГБ");
            out.row("Объём по данным системы", Util.bytes(t));
            double pct = (t - f) * 100.0 / t;
            out.bar("Занято (вся память)", Util.bytes(t - f) + " из " + Util.bytes(t), pct, Section.level(pct, 80, 92));
            out.row("Свободно", Util.bytes(f));
        } catch (Throwable e) {
            out.row("Номинальный объём", null);
        }
        try {
            File d = Environment.getDataDirectory();
            StatFs sf = new StatFs(d.getPath());
            long t = sf.getTotalBytes(), f = sf.getAvailableBytes();
            double pct = t > 0 ? (t - f) * 100.0 / t : 0;
            out.bar("Раздел данных (/data)", Util.bytes(t - f) + " из " + Util.bytes(t), pct, Section.level(pct, 80, 92));
        } catch (Throwable e) {
            out.row("Раздел данных (/data)", null);
        }
        try {
            File d = Environment.getExternalStorageDirectory();
            StatFs sf = new StatFs(d.getPath());
            long t = sf.getTotalBytes(), f = sf.getAvailableBytes();
            double pct = t > 0 ? (t - f) * 100.0 / t : 0;
            out.bar("Внутренний общий (" + d.getPath() + ")", Util.bytes(t - f) + " из " + Util.bytes(t), pct,
                    Section.level(pct, 80, 92));
        } catch (Throwable ignored) { }

        String type = null;
        if (Util.exists("/sys/block/sda")) type = "UFS (по /sys/block/sda)";
        else if (Util.exists("/sys/block/mmcblk0")) type = "eMMC (по /sys/block/mmcblk0)";
        out.row("Тип накопителя", type == null ? "Не определён" : type, type == null ? Section.MUTED : Section.NORMAL);
        String bd = Util.prop("ro.boot.bootdevice");
        if (bd != null) out.row("Загрузочное устройство", bd);
        String st = Util.prop("ro.boot.storage_type");
        if (st != null) out.row("Тип (ro.boot.storage_type)", st);
        String fs = Util.firstNonEmpty(Util.prop("ro.crypto.type"), Util.prop("ro.crypto.state"));
        if (fs != null) out.row("Шифрование", fs);

        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            List<StorageVolume> vols = sm.getStorageVolumes();
            out.header("Тома хранилища (" + vols.size() + ")");
            for (StorageVolume v : vols) {
                String name = v.getDescription(ctx);
                String kind = (v.isPrimary() ? "основной, " : "") + (v.isRemovable() ? "съёмный" : "встроенный")
                        + (v.isEmulated() ? ", эмулируемый" : "") + ", " + v.getState();
                out.row(name, kind);
                if (Build.VERSION.SDK_INT >= 30 && v.getDirectory() != null && v.isRemovable()
                        && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                    try {
                        StatFs sf = new StatFs(v.getDirectory().getPath());
                        long t = sf.getTotalBytes(), f = sf.getAvailableBytes();
                        double pct = t > 0 ? (t - f) * 100.0 / t : 0;
                        out.bar("  " + name, Util.bytes(t - f) + " из " + Util.bytes(t), pct, Section.level(pct, 80, 92));
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable e) {
            out.row("Тома хранилища", null);
        }
    }
}
