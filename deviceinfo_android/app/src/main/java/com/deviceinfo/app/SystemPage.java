package com.deviceinfo.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class SystemPage implements Page {
    @Override public String title() { return "Система"; }

    @Override public boolean live() { return false; }

    private static String yn(boolean b) { return b ? "Да" : "Нет"; }

    private static String propAny(String... keys) {
        for (String k : keys) { String v = Util.prop(k); if (v != null) return v; }
        return null;
    }

    private static String pkg(Context ctx, String name) {
        try {
            ctx.getPackageManager().getPackageInfo(name, 0);
            return "Да";
        } catch (PackageManager.NameNotFoundException e) {
            return "Нет / не определено";
        } catch (Throwable e) {
            return "не определено";
        }
    }

    @Override public void build(Context ctx, Section out) {
        PackageManager pm = ctx.getPackageManager();

        out.header("Устройство");
        String market = propAny("ro.product.marketname", "ro.config.marketing_name", "ro.product.vendor.marketname",
                "ro.product.odm.marketname", "msc.sys.oemName");
        out.row("Маркетинговое название", market);
        out.row("Модель", Build.MODEL);
        out.row("Производитель", Build.MANUFACTURER);
        out.row("Бренд", Build.BRAND);
        out.row("Устройство", Build.DEVICE);
        out.row("Продукт", Build.PRODUCT);
        out.row("Плата (board)", Build.BOARD);
        out.row("Платформа (hardware)", Build.HARDWARE);
        if (Build.VERSION.SDK_INT >= 31) {
            out.row("SKU", Build.SKU);
            out.row("ODM SKU", Build.ODM_SKU);
        }
        out.row("Серийный номер", null);
        out.note("Серийный номер недоступен приложениям без привилегий.");

        out.header("Операционная система");
        out.row("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        String magic = propAny("ro.build.version.magic", "ro.build.version.magicui", "msc.sys.version");
        out.row("MagicOS", magic);
        String emui = propAny("ro.build.version.emui", "ro.build.hw_emui_api_level");
        if (emui != null) out.row("EMUI / HarmonyOS", emui);
        String hos = Util.prop("hw_sc.build.platform.version");
        if (hos != null) out.row("Платформа HarmonyOS", hos);
        out.row("Патч безопасности", Build.VERSION.SECURITY_PATCH);
        out.row("Сборка (display id)", Build.DISPLAY);
        out.row("Incremental", Build.VERSION.INCREMENTAL);
        out.row("Отпечаток (fingerprint)", Build.FINGERPRINT);
        out.row("Дата сборки", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(Build.TIME)));
        out.row("Тип / теги", Build.TYPE + " / " + Build.TAGS);
        out.row("Bootloader", Build.BOOTLOADER);
        String radio = null;
        try { radio = Build.getRadioVersion(); } catch (Throwable ignored) { }
        out.row("Baseband", radio);
        out.row("Ядро", System.getProperty("os.version"));
        out.note("/proc/version недоступен приложениям; версия ядра взята из os.version.");
        out.row("Java VM", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        String se = Util.read("/sys/fs/selinux/enforce");
        if (se != null) se = "1".equals(se) ? "Enforcing" : "0".equals(se) ? "Permissive" : se;
        else se = propAny("ro.boot.selinux", "ro.build.selinux");
        out.row("SELinux", se);
        out.row("Project Treble", Util.prop("ro.treble.enabled") == null ? null : yn("true".equals(Util.prop("ro.treble.enabled"))));
        String ab = Util.prop("ro.build.ab_update");
        out.row("A/B обновления", ab == null ? "Нет / не определено" : yn("true".equals(ab)));
        String dp = Util.prop("ro.boot.dynamic_partitions");
        out.row("Динамические разделы", dp == null ? "Нет / не определено" : yn("true".equals(dp)));
        out.row("Первый API-уровень", Util.prop("ro.product.first_api_level"));
        out.row("Google Mobile Services", pkg(ctx, "com.google.android.gms"));
        out.row("HMS Core / Honor ID", combine(pkg(ctx, "com.hihonor.id"), pkg(ctx, "com.huawei.hwid")));

        out.header("Безопасность и режим разработчика");
        boolean root = false;
        String[] su = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
                "/su/bin/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/app/Superuser.apk"};
        StringBuilder found = new StringBuilder();
        for (String p : su) if (Util.exists(p)) { root = true; found.append(p).append(' '); }
        String mg = pkg(ctx, "com.topjohnwu.magisk");
        if (mg.equals("Да")) { root = true; found.append("Magisk "); }
        out.row("Root", root ? "Обнаружен: " + found.toString().trim() : "Не обнаружен", root ? Section.CRIT : Section.OK);
        try {
            out.row("Режим разработчика", yn(Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1));
            out.row("Отладка по USB (ADB)", yn(Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1));
        } catch (Throwable e) {
            out.row("Режим разработчика", null);
        }

        out.header("Время работы");
        long up = SystemClock.elapsedRealtime(), awake = SystemClock.uptimeMillis();
        out.row("Аптайм", Util.duration(up / 1000.0));
        double deep = up > 0 ? (1 - awake / (double) up) * 100 : 0;
        out.row("Глубокий сон", Util.f("%.1f %%", deep));
        out.row("Язык", Locale.getDefault().toString() + " (" + Locale.getDefault().getDisplayName() + ")");
        TimeZone tz = TimeZone.getDefault();
        out.row("Часовой пояс", tz.getID() + " (" + tz.getDisplayName(false, TimeZone.SHORT) + ")");
        out.row("Текущее время", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));

        buildNetwork(ctx, out, pm);
        buildFeatures(out, pm);
    }

    private static String combine(String a, String b) {
        return "Honor ID: " + a + "; Huawei HwID: " + b;
    }

    private void buildNetwork(Context ctx, Section out, PackageManager pm) {
        out.header("Связь");
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            String t = "Нет подключения";
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t = "Wi-Fi";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t = "Мобильная сеть";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t = "Ethernet";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t = "VPN";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) t = "Bluetooth";
                else t = "Другое";
            }
            out.row("Активная сеть", t);
            if (nc != null) {
                out.row("Нисходящая оценка", nc.getLinkDownstreamBandwidthKbps() / 1000 + " Мбит/с");
                out.row("Восходящая оценка", nc.getLinkUpstreamBandwidthKbps() / 1000 + " Мбит/с");
            }
        } catch (Throwable e) {
            out.row("Активная сеть", null);
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            out.row("Wi-Fi включён", yn(wm.isWifiEnabled()));
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getNetworkId() != -1) {
                String ssid = wi.getSSID();
                if (ssid == null || ssid.contains("unknown ssid")) ssid = "скрыт (нужно разрешение на геолокацию)";
                out.row("SSID", ssid);
                out.row("Скорость связи", wi.getLinkSpeed() + " Мбит/с");
                out.row("Частота", wi.getFrequency() + " МГц");
                out.row("RSSI", wi.getRssi() + " дБм");
            }
            out.row("Wi-Fi 5 ГГц", yn(wm.is5GHzBandSupported()));
            if (Build.VERSION.SDK_INT >= 30) out.row("Wi-Fi 6 (802.11ax)", yn(wm.isWifiStandardSupported(6)));
        } catch (Throwable e) {
            out.row("Wi-Fi", null);
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String h = a.getHostAddress();
                    int z = h.indexOf('%');
                    if (z > 0) h = h.substring(0, z);
                    sb.append(ni.getName()).append(": ").append(h).append('\n');
                }
            }
            out.row("IP-адреса", sb.toString().trim());
        } catch (Throwable e) {
            out.row("IP-адреса", null);
        }
        out.row("Bluetooth", yn(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)));
        out.row("Bluetooth LE", yn(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)));
        out.row("NFC", yn(pm.hasSystemFeature(PackageManager.FEATURE_NFC)));
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            int sims = Build.VERSION.SDK_INT >= 30 ? tm.getActiveModemCount() : tm.getPhoneCount();
            out.row("Модемов / SIM-слотов", sims);
        } catch (Throwable e) {
            out.row("Модемов / SIM-слотов", null);
        }
    }

    private void buildFeatures(Section out, PackageManager pm) {
        out.header("Возможности");
        String[][] f = {
                {PackageManager.FEATURE_FINGERPRINT, "Сканер отпечатка"},
                {PackageManager.FEATURE_NFC, "NFC"},
                {PackageManager.FEATURE_WIFI_DIRECT, "Wi-Fi Direct"},
                {PackageManager.FEATURE_BLUETOOTH_LE, "Bluetooth LE"},
                {PackageManager.FEATURE_CAMERA_FLASH, "Вспышка"},
                {PackageManager.FEATURE_CAMERA_FRONT, "Фронтальная камера"},
                {PackageManager.FEATURE_USB_HOST, "USB host (OTG)"},
                {PackageManager.FEATURE_TELEPHONY, "Телефония"},
                {PackageManager.FEATURE_TELEPHONY_MBMS, "MBMS"},
                {PackageManager.FEATURE_SENSOR_BAROMETER, "Барометр"},
                {PackageManager.FEATURE_SENSOR_COMPASS, "Компас"},
                {PackageManager.FEATURE_SENSOR_GYROSCOPE, "Гироскоп"},
                {PackageManager.FEATURE_SENSOR_LIGHT, "Датчик освещённости"},
                {PackageManager.FEATURE_SENSOR_PROXIMITY, "Датчик приближения"},
                {PackageManager.FEATURE_SENSOR_STEP_COUNTER, "Шагомер"},
                {PackageManager.FEATURE_SENSOR_ACCELEROMETER, "Акселерометр"},
                {PackageManager.FEATURE_SENSOR_HEART_RATE, "Пульс"},
                {PackageManager.FEATURE_LOCATION_GPS, "GPS"},
                {PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, "Vulkan (уровень)"},
                {PackageManager.FEATURE_OPENGLES_EXTENSION_PACK, "OpenGL ES AEP"},
                {PackageManager.FEATURE_USB_ACCESSORY, "USB accessory"},
                {PackageManager.FEATURE_CONSUMER_IR, "ИК-порт"},
                {PackageManager.FEATURE_AUDIO_LOW_LATENCY, "Низкая задержка аудио"},
                {PackageManager.FEATURE_AUDIO_PRO, "Pro-аудио"},
                {PackageManager.FEATURE_WIFI_AWARE, "Wi-Fi Aware"},
                {PackageManager.FEATURE_WIFI_RTT, "Wi-Fi RTT"},
                {PackageManager.FEATURE_ETHERNET, "Ethernet"},
                {PackageManager.FEATURE_FACE, "Распознавание лица"},
                {PackageManager.FEATURE_STRONGBOX_KEYSTORE, "StrongBox"},
                {PackageManager.FEATURE_SECURE_LOCK_SCREEN, "Безопасный экран блокировки"},
        };
        for (String[] e : f) {
            boolean has;
            try { has = pm.hasSystemFeature(e[0]); } catch (Throwable t) { continue; }
            out.row(e[1], yn(has), has ? Section.OK : Section.MUTED);
        }
        out.note("Поддержка 5G этим способом не определяется.");
    }
}
