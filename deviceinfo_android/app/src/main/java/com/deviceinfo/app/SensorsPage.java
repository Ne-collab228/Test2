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

    @Override public boolean live() { return true; }

    @Override public void onHide(Context ctx) { SensorLive.stop(); }

    /** Максимальная частота (Гц) по минимальной задержке (мкс); null если не определена. */
    public static String rateHz(int minDelayUs) {
        if (minDelayUs > 0) return Util.f("%.0f Гц", 1e6 / minDelayUs);
        if (minDelayUs == 0) return "по событию";
        return "—";
    }

    @Override public void build(Context ctx, Section out) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
        if (list.isEmpty()) { out.header("Датчики (0)"); out.note("Датчики не найдены."); return; }
        SensorLive.ensureRegistered(sm, list);
        buildLive(ctx, out, list);
        out.header("Датчики (" + list.size() + ")");

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

    private void buildLive(Context ctx, Section out, List<Sensor> list) {
        out.header("Показания (в реальном времени)");
        long now = android.os.SystemClock.elapsedRealtime();
        long started = SensorLive.startedMs();
        Map<String, Integer> labelCount = new java.util.HashMap<>();
        for (Sensor s : list) {
            String l = SensorLive.typeLabel(s.getType(), s.getName());
            Integer c = labelCount.get(l);
            labelCount.put(l, c == null ? 1 : c + 1);
        }
        java.util.Set<Integer> types = new java.util.HashSet<>();
        for (Sensor s : list) {
            types.add(s.getType());
            String label = SensorLive.typeLabel(s.getType(), s.getName());
            if (labelCount.get(label) > 1 && !label.equals(s.getName())) label += " (" + s.getName() + ")";
            if (SensorLive.oneShot(s)) { out.row(label, "срабатывает по событию", Section.MUTED); continue; }
            SensorLive.Reading r = SensorLive.READINGS.get(SensorLive.key(s));
            float[] v = r == null ? null : r.values;
            if (v == null) {
                if (SensorLive.permissionMissing(ctx, s.getType()) && started > 0 && now - started > 5000)
                    out.row(label, "нужно разрешение «Физическая активность»", Section.WARN);
                else out.row(label, "ожидание данных…", Section.MUTED);
                continue;
            }
            String txt = SensorLive.format(s.getType(), v, s.getMaximumRange(), r.count);
            if (r.rateHz > 0) txt += " · " + Util.f("%.0f Гц", r.rateHz);
            out.row(label, txt);
        }
        long t = android.os.SystemClock.elapsedRealtime();
        if (types.contains(Sensor.TYPE_ACCELEROMETER) && SensorLive.ACC.values(t).length > 1)
            out.chart("Ускорение |a|", Util.f("%.2f м/с²", SensorLive.ACC.last()), SensorLive.ACC.values(t),
                    Double.NaN, Double.NaN, " м/с²", Section.NORMAL);
        if (types.contains(Sensor.TYPE_LIGHT) && SensorLive.LIGHT.values(t).length > 1)
            out.chart("Освещённость", Util.f("%.0f лк", SensorLive.LIGHT.last()), SensorLive.LIGHT.values(t),
                    Double.NaN, Double.NaN, " лк", Section.NORMAL);
        StringBuilder miss = new StringBuilder();
        if (!types.contains(Sensor.TYPE_GYROSCOPE)) miss.append("гироскоп");
        if (!types.contains(Sensor.TYPE_MAGNETIC_FIELD)) miss.append(miss.length() > 0 ? ", " : "").append("компас/магнитометр");
        if (!types.contains(Sensor.TYPE_PRESSURE)) miss.append(miss.length() > 0 ? ", " : "").append("барометр");
        if (miss.length() > 0) out.note("Нет в устройстве: " + miss + ".");
    }

    private static String shortType(Sensor s) {
        String t = s.getStringType();
        if (t == null) return "type " + s.getType();
        String p = "android.sensor.";
        if (t.startsWith(p)) t = t.substring(p.length());
        return t.toLowerCase(Locale.US);
    }
}
