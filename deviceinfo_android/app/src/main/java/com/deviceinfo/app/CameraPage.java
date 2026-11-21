package com.deviceinfo.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CameraPage implements Page {
    private static final double FULL_FRAME_DIAG = Math.hypot(36, 24);

    @Override public String title() { return "Камеры"; }

    @Override public boolean live() { return false; }

    /** Мегапиксели по размеру в пикселях. */
    public static double megapixels(long w, long h) { return w * h / 1e6; }

    /** Эквивалент фокусного расстояния для кадра 36x24 мм. */
    public static double equiv35(double focalMm, double sensorW, double sensorH) {
        return focalMm * FULL_FRAME_DIAG / Math.hypot(sensorW, sensorH);
    }

    /** Приблизительный оптический формат «1/x"» по диагонали сенсора в мм. */
    public static String opticalFormat(double diagMm) {
        double inch = diagMm / 16.0; // «видиконовский» дюйм: 16 мм
        return inch > 0 ? Util.f("1/%.2f\"", 1 / inch) : "—";
    }

    @Override public void build(Context ctx, Section out) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String[] ids;
        try {
            ids = cm.getCameraIdList();
        } catch (Throwable e) {
            out.header("Камеры");
            out.row("Список камер", null);
            return;
        }
        out.header("Камеры (" + ids.length + ")");
        Set<String> physical = new HashSet<>();
        for (String id : ids) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                if (Build.VERSION.SDK_INT >= 28) physical.addAll(c.getPhysicalCameraIds());
            } catch (Throwable ignored) { }
        }
        for (String id : ids) describe(cm, id, "Камера " + id, out, false);
        for (String id : physical) {
            if (Arrays.asList(ids).contains(id)) continue;
            describe(cm, id, "Физическая камера " + id, out, true);
        }
    }

    /** Паспортные мегапиксели основной (задней) и фронтальной камеры известных моделей; 0 — нет данных. */
    static int specMegapixels(Integer facing) {
        String m = (android.os.Build.MODEL == null ? "" : android.os.Build.MODEL).toUpperCase();
        if (facing == null) return 0;
        if (m.startsWith("NIC-")) // Honor X6c
            return facing == CameraCharacteristics.LENS_FACING_BACK ? 50 : 5;
        if (m.startsWith("JDY-")) // Honor X6b
            return facing == CameraCharacteristics.LENS_FACING_BACK ? 50 : 5;
        return 0;
    }

    private void describe(CameraManager cm, String id, String title, Section out, boolean phys) {
        out.header(title);
        CameraCharacteristics c;
        try {
            c = cm.getCameraCharacteristics(id);
        } catch (CameraAccessException e) {
            out.row("Характеристики", null);
            return;
        } catch (Throwable e) {
            out.row("Характеристики", null);
            return;
        }
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        out.row("Расположение", facing == null ? null : facing == CameraCharacteristics.LENS_FACING_BACK ? "Задняя"
                : facing == CameraCharacteristics.LENS_FACING_FRONT ? "Фронтальная" : "Внешняя");
        Integer hw = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        out.row("Уровень Camera2", hw == null ? null : level(hw));

        // Разрешение
        Size pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        double mpPix = pix == null ? 0 : megapixels(pix.getWidth(), pix.getHeight());
        double mpAct = act == null ? 0 : megapixels(act.width(), act.height());
        double mpOut = 0, mpMax = 0;
        Size bestOut = null, bestMax = null;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            bestOut = largest(map.getOutputSizes(ImageFormat.JPEG));
            if (bestOut == null) bestOut = largest(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (bestOut != null) mpOut = megapixels(bestOut.getWidth(), bestOut.getHeight());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                StreamConfigurationMap mm = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (mm != null) {
                    bestMax = largest(mm.getOutputSizes(ImageFormat.JPEG));
                    if (bestMax == null) bestMax = largest(mm.getOutputSizes(ImageFormat.RAW_SENSOR));
                    if (bestMax != null) mpMax = megapixels(bestMax.getWidth(), bestMax.getHeight());
                }
                Rect am = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
                if (am != null) mpAct = Math.max(mpAct, megapixels(am.width(), am.height()));
            } catch (Throwable ignored) { }
        }
        if (pix != null) out.row("Массив пикселей", pix.getWidth() + "×" + pix.getHeight() + " (" + Util.f("%.1f МП", mpPix) + ")");
        if (act != null) out.row("Активная область", act.width() + "×" + act.height() + " (" + Util.f("%.1f МП", megapixels(act.width(), act.height())) + ")");
        if (bestOut != null) out.row("Макс. JPEG", bestOut.getWidth() + "×" + bestOut.getHeight() + " (" + Util.f("%.1f МП", mpOut) + ")");
        if (bestMax != null) out.row("Макс. разрешение (full-res режим)", bestMax.getWidth() + "×" + bestMax.getHeight() + " (" + Util.f("%.1f МП", mpMax) + ")");
        double top = Math.max(Math.max(mpPix, mpAct), Math.max(mpOut, mpMax));
        out.row("Матрица (макс.)", top > 0 ? Util.f("%.1f МП", top) : null, Section.INFO);
        // Сенсоры Quad-Bayer (50 МП) в Camera2 часто видны только в режиме объединения 4-в-1
        // (12.5 МП), без карты полного разрешения. Для известных моделей показываем паспорт.
        int specMp = specMegapixels(facing);
        if (specMp > 0 && top > 0 && specMp > top * 2.5) {
            out.row("Матрица (по спецификации)", specMp + " МП", Section.OK);
            out.note(Util.f("Сенсор %d МП с объединением пикселей 4-в-1: системный интерфейс камеры отдаёт %.1f МП, "
                    + "полное разрешение доступно в режиме «%d МП» приложения «Камера».", specMp, top, specMp));
        }
        if (mpPix > 0 && mpOut > 0 && mpPix > mpOut * 1.5 && mpMax == 0)
            out.note("Пиксельный массив больше максимального выходного размера: сенсор, вероятно, работает с биннингом. "
                    + "Режим " + Math.round(mpPix) + " МП доступен в приложении камеры.");
        else if (mpPix > 0 && mpOut > 0 && mpPix > mpOut * 1.5)
            out.note("Стандартный выход ниже размера сенсора (биннинг); режим " + Math.round(mpPix) + " МП в приложении камеры.");

        // Оптика
        SizeF ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float[] ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (ps != null && ps.getWidth() > 0) {
            double diag = Math.hypot(ps.getWidth(), ps.getHeight());
            out.row("Размер сенсора", Util.f("%.2f×%.2f мм (диагональ %.2f мм, ~%s)", ps.getWidth(), ps.getHeight(), diag, opticalFormat(diag)));
            if (pix != null && pix.getWidth() > 0)
                out.row("Размер пикселя", Util.f("%.2f мкм", ps.getWidth() * 1000.0 / pix.getWidth()));
        } else {
            out.row("Размер сенсора", null);
        }
        if (fl != null && fl.length > 0) {
            StringBuilder f = new StringBuilder(), e = new StringBuilder(), v = new StringBuilder();
            for (float x : fl) {
                f.append(Util.f("%.2f мм ", x));
                if (ps != null && ps.getWidth() > 0) {
                    e.append(Util.f("%.0f мм ", equiv35(x, ps.getWidth(), ps.getHeight())));
                    double fov = Math.toDegrees(2 * Math.atan(ps.getWidth() / (2.0 * x)));
                    double fovd = Math.toDegrees(2 * Math.atan(Math.hypot(ps.getWidth(), ps.getHeight()) / (2.0 * x)));
                    v.append(Util.f("%.0f° (диаг. %.0f°) ", fov, fovd));
                }
            }
            out.row("Фокусное расстояние", f.toString().trim());
            if (e.length() > 0) out.row("Эквивалент 35 мм", e.toString().trim());
            if (v.length() > 0) out.row("Угол обзора (гор.)", v.toString().trim());
        }
        if (ap != null && ap.length > 0) {
            StringBuilder a = new StringBuilder();
            for (float x : ap) a.append(Util.f("f/%.2f ", x));
            out.row("Диафрагма", a.toString().trim());
        }
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocus != null) out.row("Мин. дистанция фокуса", minFocus > 0 ? Util.f("%.1f см", 100.0 / minFocus) : "фиксированный фокус");

        // Стабилизация, вспышка, АФ
        int[] ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        out.row("Оптическая стабилизация (OIS)", yn(ois != null && Arrays.stream(ois).anyMatch(x -> x == 1)));
        int[] eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        out.row("Электронная стабилизация", yn(eis != null && Arrays.stream(eis).anyMatch(x -> x == 1)));
        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        out.row("Вспышка", yn(flash != null && flash));
        int[] af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean hasAf = af != null && Arrays.stream(af).anyMatch(x -> x == 1 || x == 3 || x == 4);
        out.row("Автофокус", yn(hasAf));

        // Возможности
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) {
            StringBuilder s = new StringBuilder();
            for (int x : caps) {
                String n = cap(x);
                if (n != null) s.append(n).append(", ");
            }
            if (s.length() > 2) s.setLength(s.length() - 2);
            out.row("Возможности", s.toString());
        }
        Float zoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        out.row("Макс. цифровой зум", zoom == null ? null : Util.f("%.1f×", zoom));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) out.row("Диапазон zoom ratio", Util.f("%.1f–%.1f×", zr.getLower(), zr.getUpper()));
            } catch (Throwable ignored) { }
        }
        Range<Integer>[] fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fps != null && fps.length > 0) {
            int max = 0;
            StringBuilder s = new StringBuilder();
            for (Range<Integer> r : fps) { max = Math.max(max, r.getUpper()); s.append(r.getLower()).append('-').append(r.getUpper()).append(' '); }
            out.row("Диапазоны FPS", s.toString().trim());
            out.row("Макс. FPS (превью)", max);
        }
        int[] modes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
        boolean hdr = modes != null && Arrays.stream(modes).anyMatch(x -> x == 18);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Object p = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
                if (p != null) hdr = true;
            } catch (Throwable ignored) { }
        }
        out.row("HDR", yn(hdr));
        Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orient != null) out.row("Ориентация сенсора", orient + "°");
        if (Build.VERSION.SDK_INT >= 28 && !phys) {
            Set<String> ph = c.getPhysicalCameraIds();
            if (ph != null && !ph.isEmpty()) out.row("Физические камеры", ph.toString());
        }
    }

    private static Size largest(Size[] a) {
        if (a == null) return null;
        Size b = null;
        for (Size s : a) if (b == null || (long) s.getWidth() * s.getHeight() > (long) b.getWidth() * b.getHeight()) b = s;
        return b;
    }

    private static String yn(boolean b) { return b ? "Да" : "Нет"; }

    private static String level(int l) {
        switch (l) {
            case 0: return "LIMITED";
            case 1: return "FULL";
            case 2: return "LEGACY";
            case 3: return "LEVEL_3";
            case 4: return "EXTERNAL";
            default: return String.valueOf(l);
        }
    }

    private static String cap(int x) {
        switch (x) {
            case 1: return "Ручной сенсор";
            case 2: return "Ручная постобработка";
            case 3: return "RAW";
            case 6: return "Burst";
            case 8: return "Глубина";
            case 9: return "Высокая скорость";
            case 10: return "Отслеживание движения";
            case 11: return "Логическая мультикамера";
            case 12: return "Монохромная";
            case 16: return "Full-res (ultra high res)";
            default: return null;
        }
    }
}
