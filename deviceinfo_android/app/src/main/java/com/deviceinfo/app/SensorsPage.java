package com.deviceinfo.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class SensorsPage implements Page {
    @Override public String title() { return "Датчики"; }

    @Override public boolean live() { return false; }

    /** Максимальная частота (Гц) по минимальной задержке (мкс); null если не определена. */
    public static String rateHz(int minDelayUs) {
        if (minDelayUs > 0) return Util.f("%.0f Гц", 1e6 / minDelayUs);
        if (minDelayUs == 0) return "по событию";
        return "—";
    }

    @Override public void build(Context ctx, Section out) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
        out.header("Датчики (" + list.size() + ")");
        if (list.isEmpty()) { out.note("Датчики не найдены."); return; }

        Map<String, Integer> counts = new TreeMap<>();
        for (Sensor s : list) {
            String t = shortType(s);
            Integer c = counts.get(t);
            counts.put(t, c == null ? 1 : c + 1);
        }
        out.header("По типам");
        for (Map.Entry<String, Integer> e : counts.entrySet()) out.row(e.getKey(), e.getValue());

        out.header("Список");
        List<String[]> rows = new ArrayList<>();
        for (Sensor s : list) {
            String flags = (s.isWakeUpSensor() ? "wake " : "") + (s.isDynamicSensor() ? "dyn" : "");
            rows.add(new String[]{s.getName(), s.getVendor(), shortType(s), rateHz(s.getMinDelay()), flags.trim()});
        }
        out.table(new String[]{"Название", "Производитель", "Тип", "Макс. частота", "Флаги"}, rows,
                new float[]{3f, 2f, 2f, 1.5f, 1f});

        out.header("Подробно");
        for (Sensor s : list) {
            out.row(s.getName(), shortType(s), Section.INFO);
            out.row("  Производитель / версия", s.getVendor() + " / v" + s.getVersion());
            out.row("  Тип", s.getStringType() + " (" + s.getType() + ")");
            out.row("  Диапазон", Util.f("%.4g", s.getMaximumRange()));
            out.row("  Разрешение", Util.f("%.4g", s.getResolution()));
            out.row("  Питание", Util.f("%.3f мА", s.getPower()));
            int md = s.getMinDelay();
            out.row("  Мин. задержка", md > 0 ? md + " мкс (" + rateHz(md) + ")" : rateHz(md));
            int mx = s.getMaxDelay();
            if (mx > 0) out.row("  Макс. задержка", mx + " мкс");
            out.row("  Wake-up", s.isWakeUpSensor() ? "Да" : "Нет");
            out.row("  Динамический", s.isDynamicSensor() ? "Да" : "Нет");
            out.row("  FIFO", s.getFifoMaxEventCount() + " событий");
        }
    }

    private static String shortType(Sensor s) {
        String t = s.getStringType();
        if (t == null) return "type " + s.getType();
        String p = "android.sensor.";
        if (t.startsWith(p)) t = t.substring(p.length());
        return t.toLowerCase(Locale.US);
    }
}
