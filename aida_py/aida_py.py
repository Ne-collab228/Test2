# -*- coding: utf-8 -*-
"""
AIDA Py — информация об устройстве в стиле AIDA64 для Pydroid 3 (Kivy).
Всё приложение в одном файле.

Запуск в Pydroid 3: откройте этот файл и нажмите ▶ (Kivy и pyjnius уже
входят в Pydroid 3). На ПК: pip install kivy  →  python aida_py.py

Разделы файла:
  1. ДАННЫЕ     — получение информации (Android API, /proc, /sys, getprop,
                  platform, os), история батареи, монитор сообщений.
  2. ИНТЕРФЕЙС  — тема, виджеты и вкладки (Процессор, Батарея, Система,
                  Сообщения).
  3. ПРИЛОЖЕНИЕ — окно, панель вкладок, автообновление.

Как добавить новую вкладку — в разделе 2 (или в конце файла перед запуском):

    @register_tab(order=50)        # order — позиция среди вкладок
    class NetworkTab(BaseTab):
        title = "Сеть"

        def toolbar(self):          # необязательно: кнопки над вкладкой
            return [("Обновить", lambda btn: self.refresh())]

        def build(self):            # вызывается при каждом обновлении
            self.section("Сеть")
            self.row("Имя хоста", self.data.system.get("hostname"))
            self.bar("Сигнал", "75 %", 75)
            self.table(["Интерфейс", "IP"], [["wlan0", "192.168.1.5"]])

Кнопка и экран создаются автоматически. Данные: self.data (cpu, battery,
system, history, monitor и словарь extra для ваших значений).
"""

import glob
import json
import os
import platform
import re
import subprocess
import time
from datetime import datetime

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Line, Rectangle
from kivy.metrics import dp, sp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.screenmanager import NoTransition, Screen, ScreenManager
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from kivy.utils import get_color_from_hex as hexc

try:
    import psutil  # необязательно; на Android обычно не нужен
except Exception:  # pragma: no cover
    psutil = None


# ===========================================================================
#   1. ДАННЫЕ
# ===========================================================================


# ---------------------------------------------------------------------------
#   Базовые помощники
# ---------------------------------------------------------------------------

def _app_dir():
    """Каталог для хранения истории батареи и журнала сообщений."""
    for base in (os.path.expanduser("~"), os.getcwd(), "/sdcard"):
        path = os.path.join(base, ".aida_py")
        try:
            os.makedirs(path, exist_ok=True)
            test = os.path.join(path, ".w")
            with open(test, "w") as f:
                f.write("1")
            os.remove(test)
            return path
        except Exception:
            continue
    return os.getcwd()


APP_DIR = _app_dir()


def read_file(path, default=None):
    try:
        with open(path, "r", errors="ignore") as f:
            return f.read().strip()
    except Exception:
        return default


def read_int(path, default=None):
    value = read_file(path)
    if value is None:
        return default
    try:
        return int(value.split()[0])
    except Exception:
        return default


def run_cmd(args, timeout=3):
    try:
        out = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             timeout=timeout)
        return out.stdout.decode("utf-8", "ignore")
    except Exception:
        return ""


def _natural_key(text):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", text)]


def is_android():
    return ("ANDROID_ROOT" in os.environ or "ANDROID_ARGUMENT" in os.environ
            or os.path.exists("/system/build.prop"))


IS_ANDROID = is_android()


# ---------------------------------------------------------------------------
#   Форматирование
# ---------------------------------------------------------------------------

def fmt_bytes(num):
    if num is None:
        return None
    num = float(num)
    for unit in ("Б", "КБ", "МБ", "ГБ", "ТБ"):
        if abs(num) < 1024 or unit == "ТБ":
            return ("%.0f %s" if unit == "Б" else "%.2f %s") % (num, unit)
        num /= 1024.0


def fmt_freq_khz(khz):
    if not khz:
        return None
    mhz = khz / 1000.0
    return "%.2f ГГц" % (mhz / 1000.0) if mhz >= 1000 else "%.0f МГц" % mhz


def fmt_duration(seconds):
    if seconds is None or seconds < 0:
        return None
    seconds = int(seconds)
    d, rem = divmod(seconds, 86400)
    h, rem = divmod(rem, 3600)
    m, s = divmod(rem, 60)
    if d:
        return "%d д %d ч %d мин" % (d, h, m)
    if h:
        return "%d ч %d мин" % (h, m)
    if m:
        return "%d мин %d с" % (m, s)
    return "%d с" % s


def fmt_time(ts, with_date=False):
    return datetime.fromtimestamp(ts).strftime("%d.%m %H:%M" if with_date else "%H:%M:%S")


# ---------------------------------------------------------------------------
#   Android API (pyjnius) и getprop
# ---------------------------------------------------------------------------

_jni_cache = {}


def android_context():
    """Возвращает Activity Pydroid/Kivy или None (на ПК или без pyjnius)."""
    if "ctx" in _jni_cache:
        return _jni_cache["ctx"]
    ctx = None
    if IS_ANDROID:
        try:
            from jnius import autoclass
            ctx = autoclass("org.kivy.android.PythonActivity").mActivity
        except Exception:
            ctx = None
    _jni_cache["ctx"] = ctx
    return ctx


def jclass(name):
    if name not in _jni_cache:
        try:
            from jnius import autoclass
            _jni_cache[name] = autoclass(name)
        except Exception:
            _jni_cache[name] = None
    return _jni_cache[name]


_props = None


def getprop(key=None, default=None):
    """Свойства Android (getprop). Читаются один раз и кэшируются."""
    global _props
    if _props is None:
        _props = {}
        if IS_ANDROID:
            for line in run_cmd(["getprop"]).splitlines():
                m = re.match(r"\[(.+?)\]: \[(.*)\]", line)
                if m:
                    _props[m.group(1)] = m.group(2)
    if key is None:
        return _props
    value = _props.get(key)
    return value if value else default


def android_build():
    """Информация из android.os.Build (или из getprop)."""
    info = {}
    Build = jclass("android.os.Build") if android_context() else None
    if Build is not None:
        try:
            Version = jclass("android.os.Build$VERSION")
            info.update(
                model=Build.MODEL, manufacturer=Build.MANUFACTURER, brand=Build.BRAND,
                device=Build.DEVICE, product=Build.PRODUCT, board=Build.BOARD,
                hardware=Build.HARDWARE, build_id=Build.DISPLAY, fingerprint=Build.FINGERPRINT,
                release=Version.RELEASE, sdk=Version.SDK_INT,
            )
            try:
                info["security_patch"] = Version.SECURITY_PATCH
            except Exception:
                pass
            try:  # Android 12+
                info["soc_manufacturer"] = Build.SOC_MANUFACTURER
                info["soc_model"] = Build.SOC_MODEL
            except Exception:
                pass
        except Exception:
            pass
    fallback = {
        "model": "ro.product.model", "manufacturer": "ro.product.manufacturer",
        "brand": "ro.product.brand", "device": "ro.product.device",
        "product": "ro.product.name", "board": "ro.product.board",
        "hardware": "ro.hardware", "build_id": "ro.build.display.id",
        "fingerprint": "ro.build.fingerprint", "release": "ro.build.version.release",
        "sdk": "ro.build.version.sdk", "security_patch": "ro.build.version.security_patch",
        "soc_manufacturer": "ro.soc.manufacturer", "soc_model": "ro.soc.model",
    }
    for k, prop in fallback.items():
        if not info.get(k) or info.get(k) == "unknown":
            v = getprop(prop)
            if v:
                info[k] = v
    return info


# ---------------------------------------------------------------------------
#   Процессор
# ---------------------------------------------------------------------------

# Коды ядер ARM из поля "CPU part" в /proc/cpuinfo
ARM_CORES = {
    "0xd03": "Cortex-A53", "0xd04": "Cortex-A35", "0xd05": "Cortex-A55",
    "0xd07": "Cortex-A57", "0xd08": "Cortex-A72", "0xd09": "Cortex-A73",
    "0xd0a": "Cortex-A75", "0xd0b": "Cortex-A76", "0xd0d": "Cortex-A77",
    "0xd41": "Cortex-A78", "0xd44": "Cortex-X1", "0xd46": "Cortex-A510",
    "0xd47": "Cortex-A710", "0xd48": "Cortex-X2", "0xd4d": "Cortex-A715",
    "0xd4e": "Cortex-X3", "0xd80": "Cortex-A520", "0xd81": "Cortex-A720",
    "0xd82": "Cortex-X4", "0xd85": "Cortex-X925", "0xd87": "Cortex-A725",
    "0x800": "Kryo Gold", "0x801": "Kryo Silver", "0x802": "Kryo 385 Gold",
    "0x803": "Kryo 385 Silver", "0x804": "Kryo 485 Gold", "0x805": "Kryo 485 Silver",
}
ARM_VENDORS = {"0x41": "ARM", "0x51": "Qualcomm", "0x53": "Samsung",
               "0x48": "HiSilicon", "0x61": "Apple", "0x4e": "NVIDIA"}

# Кодовые имена платформ -> маркетинговые названия SoC (можно дополнять)
SOC_NAMES = {
    "sm8750": "Snapdragon 8 Elite", "sun": "Snapdragon 8 Elite",
    "sm8650": "Snapdragon 8 Gen 3", "pineapple": "Snapdragon 8 Gen 3",
    "sm8550": "Snapdragon 8 Gen 2", "kalama": "Snapdragon 8 Gen 2",
    "sm8475": "Snapdragon 8+ Gen 1", "sm8450": "Snapdragon 8 Gen 1",
    "taro": "Snapdragon 8 Gen 1", "sm8350": "Snapdragon 888", "lahaina": "Snapdragon 888",
    "sm8250": "Snapdragon 865", "kona": "Snapdragon 865", "sm8150": "Snapdragon 855",
    "msmnile": "Snapdragon 855", "sdm845": "Snapdragon 845", "sm7325": "Snapdragon 778G",
    "sm7250": "Snapdragon 765G", "lito": "Snapdragon 765G", "sm7150": "Snapdragon 730",
    "sm6375": "Snapdragon 695", "sm6225": "Snapdragon 680", "bengal": "Snapdragon 662/665",
    "trinket": "Snapdragon 665", "sdm660": "Snapdragon 660", "msm8953": "Snapdragon 625",
    "mt6989": "Dimensity 9300", "mt6985": "Dimensity 9200", "mt6983": "Dimensity 9000",
    "mt6893": "Dimensity 1200", "mt6877": "Dimensity 900", "mt6833": "Dimensity 700",
    "mt6853": "Dimensity 720", "mt6785": "Helio G90/G95", "mt6769": "Helio G85",
    "mt6768": "Helio G80/P65", "mt6765": "Helio P35", "mt6762": "Helio P22",
    "exynos2400": "Exynos 2400", "s5e9945": "Exynos 2400", "exynos2200": "Exynos 2200",
    "exynos2100": "Exynos 2100", "exynos990": "Exynos 990", "exynos9825": "Exynos 9825",
    "exynos9611": "Exynos 9611", "exynos850": "Exynos 850", "s5e8825": "Exynos 1280",
    "s5e8835": "Exynos 1380", "gs101": "Google Tensor", "gs201": "Google Tensor G2",
    "zuma": "Google Tensor G3", "zumapro": "Google Tensor G4",
}

_prev_stat = {}


def parse_cpuinfo():
    txt = read_file("/proc/cpuinfo", "")
    blocks, cur = [], {}
    for line in txt.splitlines():
        if not line.strip():
            if cur:
                blocks.append(cur)
                cur = {}
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            k, v = k.strip(), v.strip()
            if k == "processor" and "processor" in cur:
                blocks.append(cur)
                cur = {}
            cur[k] = v
    if cur:
        blocks.append(cur)
    procs = [b for b in blocks if "processor" in b]
    common = {}
    for b in blocks:
        if "processor" not in b:
            common.update(b)
    return procs, common


def soc_name(build, common):
    candidates = [
        build.get("soc_model"), getprop("ro.soc.model"), getprop("ro.hardware.chipname"),
        getprop("ro.mediatek.platform"), getprop("ro.chipname"), getprop("ro.board.platform"),
        build.get("hardware"), common.get("Hardware"),
    ]
    for c in candidates:
        if c and c.lower() in SOC_NAMES:
            return SOC_NAMES[c.lower()], c
    for c in candidates:
        if c:
            return c, c
    return None, None


def cpu_core_freqs():
    cores = []
    paths = glob.glob("/sys/devices/system/cpu/cpu[0-9]*")
    for p in sorted(paths, key=_natural_key):
        idx = int(re.findall(r"(\d+)$", p)[0])
        base = p + "/cpufreq/"
        online = read_file(p + "/online")
        cores.append({
            "id": idx,
            "online": online is None or online.strip() == "1",
            "cur": read_int(base + "scaling_cur_freq"),
            "min": read_int(base + "cpuinfo_min_freq"),
            "max": read_int(base + "cpuinfo_max_freq"),
            "governor": read_file(base + "scaling_governor"),
        })
    if not cores and psutil is not None:
        try:
            for i, f in enumerate(psutil.cpu_freq(percpu=True) or []):
                cores.append({"id": i, "online": True, "cur": int(f.current * 1000),
                              "min": int(f.min * 1000), "max": int(f.max * 1000),
                              "governor": None})
        except Exception:
            pass
    if not cores:
        for i in range(os.cpu_count() or 1):
            cores.append({"id": i, "online": True, "cur": None, "min": None,
                          "max": None, "governor": None})
    return cores


def cpu_usage():
    """Загрузка ЦП в % по /proc/stat (на Android 8+ обычно нет доступа)."""
    txt = read_file("/proc/stat")
    if txt:
        result = {}
        for line in txt.splitlines():
            if not line.startswith("cpu"):
                continue
            parts = line.split()
            name, vals = parts[0], [int(x) for x in parts[1:8]]
            idle, total = vals[3] + vals[4], sum(vals)
            prev = _prev_stat.get(name)
            _prev_stat[name] = (idle, total)
            if prev and total > prev[1]:
                result[name] = 100.0 * (1 - (idle - prev[0]) / float(total - prev[1]))
        if result:
            return result.get("cpu"), [result.get("cpu%d" % i) for i in range(len(result) - 1)]
        if _prev_stat:
            return None, []  # первый замер — данных для разницы ещё нет
    if psutil is not None:
        try:
            return psutil.cpu_percent(), psutil.cpu_percent(percpu=True)
        except Exception:
            pass
    return None, []


def thermal_zones():
    zones = []
    for z in sorted(glob.glob("/sys/class/thermal/thermal_zone*"), key=_natural_key):
        value = read_int(z + "/temp")
        if value is None:
            continue
        c = value / 1000.0 if abs(value) > 1000 else (value / 10.0 if abs(value) > 200 else value)
        if -40 < c < 150 and c != 0:
            zones.append((read_file(z + "/type") or os.path.basename(z), c))
    if not zones and psutil is not None and hasattr(psutil, "sensors_temperatures"):
        try:
            for name, entries in psutil.sensors_temperatures().items():
                for e in entries:
                    zones.append(("%s %s" % (name, e.label or ""), e.current))
        except Exception:
            pass
    return zones


CPU_TEMP_RE = re.compile(r"cpu|tsens|soc|apc|cluster|mtktscpu|cpuss|core|package|x86_pkg|k10temp|big|little|mid", re.I)


def get_cpu_info():
    build = android_build() if IS_ANDROID else {}
    procs, common = parse_cpuinfo()
    name, codename = soc_name(build, common)
    first = procs[0] if procs else {}
    if not name:
        name = first.get("model name") or platform.processor() or platform.machine()

    cores = cpu_core_freqs()
    # кластеры: группируем ядра по максимальной частоте и типу ядра
    parts = {int(p.get("processor", -1)): p.get("CPU part", "").lower() for p in procs}
    clusters = {}
    for c in cores:
        part = parts.get(c["id"], "")
        key = (c["max"], part)
        cl = clusters.setdefault(key, {"cores": [], "max": c["max"], "min": c["min"],
                                       "name": ARM_CORES.get(part, part or None)})
        cl["cores"].append(c["id"])
    cluster_list = sorted(clusters.values(), key=lambda x: (x["max"] or 0))

    total, per_core = cpu_usage()
    zones = thermal_zones()
    cpu_zones = [t for n, t in zones if CPU_TEMP_RE.search(n)]
    load = None
    try:
        load = os.getloadavg()
    except Exception:
        pass

    abi = getprop("ro.product.cpu.abilist") or getprop("ro.product.cpu.abi")
    implementer = first.get("CPU implementer", "").lower()
    if build:
        os_name = "Android %s (API %s)" % (build.get("release", "?"), build.get("sdk", "?"))
    else:
        os_name = "%s %s" % (platform.system(), platform.release())
    return {
        "name": name,
        "codename": codename,
        "vendor": ARM_VENDORS.get(implementer) or build.get("soc_manufacturer")
        or first.get("vendor_id"),
        "arch": platform.machine(),
        "abi": abi,
        "bits": platform.architecture()[0],
        "cores_total": len(cores),
        "cores_online": sum(1 for c in cores if c["online"]),
        "cores": cores,
        "clusters": cluster_list,
        "governor": next((c["governor"] for c in cores if c["governor"]), None),
        "usage": total,
        "usage_per_core": per_core,
        "temp": max(cpu_zones) if cpu_zones else None,
        "zones": zones,
        "loadavg": load,
        "features": first.get("Features") or first.get("flags"),
        "revision": first.get("CPU revision") or first.get("stepping"),
        "bogomips": first.get("BogoMIPS") or first.get("bogomips"),
        "os": os_name,
        "kernel": platform.release(),
        "python_arch": platform.architecture()[0],
    }


# ---------------------------------------------------------------------------
#   Батарея
# ---------------------------------------------------------------------------

STATUS_API = {1: "unknown", 2: "charging", 3: "discharging", 4: "not_charging", 5: "full"}
HEALTH_API = {1: "Неизвестно", 2: "Хорошее", 3: "Перегрев", 4: "Износ (неисправна)",
              5: "Перенапряжение", 6: "Сбой", 7: "Переохлаждение"}
PLUGGED_API = {0: None, 1: "Сеть (AC)", 2: "USB", 4: "Беспроводная", 8: "Док-станция"}
HEALTH_SYSFS = {"good": "Хорошее", "overheat": "Перегрев", "dead": "Износ (неисправна)",
                "over voltage": "Перенапряжение", "cold": "Переохлаждение",
                "unspecified failure": "Сбой", "unknown": "Неизвестно"}
CHARGING_STATUS_API = {1: "Обычная", 2: "Остановлена: холодно", 3: "Остановлена: перегрев",
                       4: "Щадящий режим (Long Life)", 5: "Адаптивная (умная) зарядка"}

INT_MIN, INT_MAX = -2147483648, 2147483647
LONG_MIN = -9223372036854775808

# Паспортная ёмкость известных моделей — последний запасной вариант, если
# PowerProfile ничего не вернул. Ключ — подстрока модели/маркетингового имени.
KNOWN_BATTERY_MAH = {"x6c": 5300, "jdy-lx": 5300}


def _empty_battery():
    return {"available": False, "source": None, "level": None, "status": "unknown",
            "plugged": None, "health": None, "temp": None, "voltage": None,
            "current": None, "current_avg": None, "technology": None,
            "capacity_full": None, "capacity_full_src": None,
            "capacity_design": None, "capacity_design_src": None,
            "cycles": None, "charge_counter": None, "charging_status": None,
            "battery_low": None, "charge_time_left": None, "energy": None,
            "max_charger": None, "soh": None, "units": None, "raw": {}}


class BatteryCalibration(object):
    """Запоминает выводы о единицах/знаке тока и замеры фактической ёмкости.

    У Honor (MagicOS) ток и счётчик заряда приходят в мА/мА·ч вместо µA/µA·ч,
    а у части прошивок знак тока перевёрнут — это определяется по наблюдениям
    и сохраняется, чтобы значения не «прыгали»."""

    def __init__(self, path=None):
        self.path = path or os.path.join(APP_DIR, "battery_calibration.json")
        self.d = {"sign_votes": 0, "fcc": []}
        try:
            with open(self.path) as f:
                self.d.update(json.load(f))
        except Exception:
            pass

    def save(self):
        try:
            with open(self.path, "w") as f:
                json.dump(self.d, f)
        except Exception:
            pass

    def vote_sign(self, current_ma, status):
        """+1 если знак совпадает со статусом (заряд «+», разряд «−»), иначе −1."""
        if current_ma is None or abs(current_ma) < 5:
            return
        if status == "discharging":
            good = current_ma < 0
        elif status == "charging":
            good = current_ma > 0
        else:
            return
        v = self.d.get("sign_votes", 0) + (1 if good else -1)
        self.d["sign_votes"] = max(-20, min(20, v))
        self.save()

    @property
    def inverted(self):
        return self.d.get("sign_votes", 0) <= -3

    def add_fcc(self, mah):
        lst = self.d.setdefault("fcc", [])
        if lst and abs(lst[-1] - mah) < 1:
            return
        lst.append(round(mah, 1))
        self.d["fcc"] = lst[-30:]
        self.save()

    def fcc_median(self):
        lst = sorted(self.d.get("fcc") or [])
        if not lst:
            return None, 0
        return lst[len(lst) // 2], len(lst)

    def reset(self):
        self.d = {"sign_votes": 0, "fcc": []}
        self.save()


CALIB = None


def battery_calibration():
    global CALIB
    if CALIB is None:
        CALIB = BatteryCalibration()
    return CALIB


def is_magicos():
    """Honor MagicOS (единицы тока и заряда — мА / мА·ч)."""
    if "magicos" in _jni_cache:
        return _jni_cache["magicos"]
    res = False
    ctx = android_context()
    if ctx is not None:
        try:
            pm = ctx.getPackageManager()
            res = bool(pm.hasSystemFeature("com.hihonor.system.feature")
                       or pm.hasSystemFeature("com.hihonor.software.features.honor"))
        except Exception:
            pass
    if not res:
        res = (getprop("ro.product.manufacturer", "") or "").lower() == "honor" or \
            bool(getprop("ro.build.version.magic") or getprop("msc.sys.version"))
    _jni_cache["magicos"] = res
    return res


def design_capacity():
    """Паспортная ёмкость (мА·ч) и откуда она взята."""
    if "design_cap" in _jni_cache:
        return _jni_cache["design_cap"]
    result = (None, None)
    ctx = android_context()
    if ctx is not None:
        try:
            PowerProfile = jclass("com.android.internal.os.PowerProfile")
            pp = PowerProfile(ctx)
            cap = float(pp.getBatteryCapacity())
            if cap <= 0:
                cap = float(pp.getAveragePower("battery.capacity"))
            if 500 < cap < 30000:
                result = (cap, "PowerProfile (power_profile.xml)")
        except Exception:
            pass
    if result[0] is None:
        for prop in ("ro.config.hw_battery_capacity", "ro.boot.battery_capacity",
                     "persist.sys.battery_capacity", "ro.vendor.battery.capacity"):
            v = getprop(prop)
            if v and v.isdigit() and 500 < int(v) < 30000:
                result = (float(v), "getprop %s" % prop)
                break
    if result[0] is None:
        names = " ".join(filter(None, [getprop("ro.product.model"),
                                       getprop("ro.product.marketname"),
                                       getprop("ro.config.marketing_name")])).lower()
        for key, mah in KNOWN_BATTERY_MAH.items():
            if key in names:
                result = (float(mah), "Справочник модели")
                break
    _jni_cache["design_cap"] = result
    return result


def _valid_int(v):
    return v is not None and v not in (INT_MIN, INT_MAX, LONG_MIN)


def _battery_android(b):
    ctx = android_context()
    if ctx is None:
        return False
    raw = b["raw"]
    try:
        Intent = jclass("android.content.Intent")
        IntentFilter = jclass("android.content.IntentFilter")
        BM = jclass("android.os.BatteryManager")
        intent = ctx.registerReceiver(None, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if intent is None:
            return False
        level = intent.getIntExtra("level", -1)
        scale = intent.getIntExtra("scale", 100) or 100
        b["level"] = 100.0 * level / scale if level >= 0 else None
        st = intent.getIntExtra("status", 1)
        b["status"] = STATUS_API.get(st, "unknown")
        b["health"] = HEALTH_API.get(intent.getIntExtra("health", 1))
        b["plugged"] = PLUGGED_API.get(intent.getIntExtra("plugged", 0), "Другой")
        t = intent.getIntExtra("temperature", INT_MIN)
        raw.update(level=level, scale=scale, status=st, temperature=t)
        if -1000 < t < 1000:
            b["temp"] = t / 10.0
        v = intent.getIntExtra("voltage", -1)
        raw["voltage"] = v
        if v > 0:  # мВ по спецификации; страховка от В и µВ
            b["voltage"] = v if v < 100 else (v / 1e6 if v > 100000 else v / 1000.0)
        b["technology"] = intent.getStringExtra("technology")
        try:
            b["battery_low"] = bool(intent.getBooleanExtra("battery_low", False))
        except Exception:
            pass
        cyc = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)  # API 34+
        raw["cycle_count"] = cyc
        if cyc > 0:
            b["cycles"] = cyc
        cs = intent.getIntExtra("android.os.extra.CHARGING_STATUS", -1)  # API 34+
        raw["charging_status"] = cs
        b["charging_status"] = CHARGING_STATUS_API.get(cs)
        mcc = intent.getIntExtra("max_charging_current", -1)   # µA
        mcv = intent.getIntExtra("max_charging_voltage", -1)   # µV
        raw.update(max_charging_current=mcc, max_charging_voltage=mcv)
        if mcc > 0 and b["status"] in ("charging", "full"):
            volts = (mcv if mcv > 0 else 5000000) / 1e6
            amps = mcc / 1e6
            b["max_charger"] = (amps, volts, amps * volts)

        bm = ctx.getSystemService(jclass("android.content.Context").BATTERY_SERVICE)
        cc = cur = cur_avg = None
        try:
            cc = bm.getIntProperty(BM.BATTERY_PROPERTY_CHARGE_COUNTER)
            raw["charge_counter"] = cc
        except Exception:
            pass
        try:
            cur = bm.getIntProperty(BM.BATTERY_PROPERTY_CURRENT_NOW)
            raw["current_now"] = cur
        except Exception:
            pass
        try:
            cur_avg = bm.getIntProperty(BM.BATTERY_PROPERTY_CURRENT_AVERAGE)
            raw["current_average"] = cur_avg
        except Exception:
            pass
        try:
            e = bm.getLongProperty(BM.BATTERY_PROPERTY_ENERGY_COUNTER)
            raw["energy_counter"] = e
            if _valid_int(e) and e > 0:
                b["energy"] = e / 1e9  # нВт·ч -> Вт·ч
        except Exception:
            pass
        try:
            ms = bm.computeChargeTimeRemaining()
            raw["charge_time_remaining"] = ms
            if ms > 0:
                b["charge_time_left"] = ms / 1000.0
        except Exception:
            pass
        try:  # системное API (BATTERY_STATS) — обычно SecurityException
            soh = bm.getIntProperty(10)
            if _valid_int(soh) and 0 < soh <= 100:
                b["soh"] = soh
        except Exception:
            pass

        # --- Единицы счётчика заряда: µA·ч по спецификации, мА·ч у Honor ---
        honor = is_magicos()
        counter_in_mah = None
        if _valid_int(cc) and cc > 0 and level > 0:
            per_full = cc * 100.0 / max(b["level"], 1)
            counter_in_mah = per_full < 500000  # < 500 мА·ч в µA·ч не бывает
            b["charge_counter"] = cc if counter_in_mah else cc / 1000.0

        # --- Единицы тока ---
        def to_ma(x):
            if not _valid_int(x) or x == 0:
                return None
            if counter_in_mah is True or (counter_in_mah is None and honor):
                # мА; но очень большое значение — всё же µA
                return x / 1000.0 if abs(x) > 20000 else float(x)
            if counter_in_mah is False:
                return x / 1000.0
            return x / 1000.0 if abs(x) > 20000 else float(x)  # эвристика vtools

        unit = ("мА / мА·ч" if counter_in_mah else "µA / µA·ч") if counter_in_mah is not None \
            else ("мА (MagicOS)" if honor else "авто")
        b["units"] = unit

        calib = battery_calibration()
        cur_ma = to_ma(cur)
        calib.vote_sign(cur_ma, b["status"])
        sign = -1 if calib.inverted else 1
        if cur_ma is not None:
            b["current"] = sign * cur_ma
        avg_ma = to_ma(cur_avg)
        if avg_ma is not None:
            b["current_avg"] = sign * avg_ma
        b["sign_inverted"] = calib.inverted

        b["available"] = b["level"] is not None
        b["source"] = "Android API" + (" (Honor MagicOS)" if honor else "")
        return b["available"]
    except Exception as e:
        raw["error"] = str(e)
        return False


def _battery_sysfs(b):
    """Запасной путь: /sys/class/power_supply. На Android 10+ обычно закрыт SELinux."""
    base = None
    for p in glob.glob("/sys/class/power_supply/*"):
        if (read_file(p + "/type") or "").lower() == "battery":
            base = p
            break
    if not base:
        return False
    level = read_int(base + "/capacity")
    if level is None:
        return False

    def micro(name):
        v = read_int(base + "/" + name)
        if v is None or v <= 0:
            return None
        return v / 1000.0 if v > 100000 else float(v)  # µA·ч или мА·ч

    if b["level"] is None:
        b["level"] = float(level)
    status = (read_file(base + "/status") or "unknown").lower().replace(" ", "_")
    if b["status"] == "unknown":
        b["status"] = status
    if not b["health"]:
        h = (read_file(base + "/health") or "").lower()
        b["health"] = HEALTH_SYSFS.get(h, h.capitalize() or None)
    if b["temp"] is None:
        t = read_int(base + "/temp")
        b["temp"] = t / 10.0 if t is not None else None
    if b["voltage"] is None:
        v = read_int(base + "/voltage_now")
        if v:
            b["voltage"] = v / 1e6 if v > 100000 else v / 1000.0
    if b["current"] is None:
        cur = read_int(base + "/current_now")
        if cur:
            b["current"] = cur / 1000.0 if abs(cur) > 20000 else float(cur)
    b["technology"] = b["technology"] or read_file(base + "/technology")
    full = micro("charge_full")
    if full:
        b["capacity_full"], b["capacity_full_src"] = full, "sysfs charge_full"
    design = micro("charge_full_design")
    if design:
        b["capacity_design"], b["capacity_design_src"] = design, "sysfs charge_full_design"
    if b["cycles"] is None:
        c = read_int(base + "/cycle_count")
        b["cycles"] = c if c and c > 0 else None
    if b["charge_counter"] is None:
        b["charge_counter"] = micro("charge_counter") or micro("charge_now")
    b["available"] = True
    b["source"] = b["source"] or "sysfs (%s)" % base
    return True


def _battery_psutil(b):
    if psutil is None or not hasattr(psutil, "sensors_battery"):
        return False
    try:
        s = psutil.sensors_battery()
    except Exception:
        s = None
    if s is None:
        return False
    b["level"] = float(s.percent)
    b["status"] = ("full" if s.percent >= 100 else "charging") if s.power_plugged else "discharging"
    b["plugged"] = "Сеть (AC)" if s.power_plugged else None
    b["available"] = True
    b["source"] = "psutil"
    return True


def get_battery_info():
    b = _empty_battery()
    _battery_android(b)
    try:
        _battery_sysfs(b)  # дополняет недостающие поля, если вдруг доступно
    except Exception:
        pass
    if not b["available"]:
        _battery_psutil(b)
    if b["plugged"] is None and b["status"] in ("charging", "full"):
        b["plugged"] = "Подключено"

    if not b["capacity_design"]:
        cap, src = design_capacity() if IS_ANDROID else (None, None)
        b["capacity_design"], b["capacity_design_src"] = cap, src

    # Фактическая ёмкость: счётчик заряда / уровень (только при высоком уровне,
    # иначе погрешность целочисленного процента слишком велика). Медиана замеров.
    if not b["capacity_full"] and b.get("charge_counter") and (b.get("level") or 0) >= 80:
        fcc = b["charge_counter"] * 100.0 / b["level"]
        design = b.get("capacity_design")
        if not design or 0.4 * design < fcc < 1.25 * design:
            battery_calibration().add_fcc(fcc)
    if not b["capacity_full"]:
        med, n = battery_calibration().fcc_median()
        if med:
            b["capacity_full"] = med
            b["capacity_full_src"] = "Оценка: счётчик заряда / уровень (%d замер.)" % n

    if b["capacity_full"] and b["capacity_design"]:
        b["wear"] = max(0.0, 100.0 * (1 - b["capacity_full"] / b["capacity_design"]))
        b["health_pct"] = min(100.0, 100.0 * b["capacity_full"] / b["capacity_design"])
    else:
        b["wear"] = b["health_pct"] = None
    if b.get("soh"):
        b["health_pct"] = float(b["soh"])
    if b["voltage"] and b["current"] is not None:
        b["power"] = b["voltage"] * b["current"] / 1000.0  # Вт, «+» = в батарею
    else:
        b["power"] = None
    return b


class BatteryHistory(object):
    """История замеров батареи и сеансов зарядки, хранится в JSON."""

    MAX_SAMPLES = 2000
    MAX_SESSIONS = 50
    MIN_INTERVAL = 60  # секунд между замерами

    def __init__(self, path=None):
        self.path = path or os.path.join(APP_DIR, "battery_history.json")
        self.samples = []
        self.sessions = []
        self._open = None
        self.load()

    def load(self):
        try:
            with open(self.path) as f:
                data = json.load(f)
            self.samples = data.get("samples", [])
            self.sessions = data.get("sessions", [])
            self._open = data.get("open")
        except Exception:
            pass

    def save(self):
        try:
            with open(self.path, "w") as f:
                json.dump({"samples": self.samples[-self.MAX_SAMPLES:],
                           "sessions": self.sessions[-self.MAX_SESSIONS:],
                           "open": self._open}, f)
        except Exception:
            pass

    def clear(self):
        self.samples, self.sessions, self._open = [], [], None
        self.save()

    @staticmethod
    def _charging(status):
        return status in ("charging", "full")

    def add(self, b, force=False):
        if not b.get("available") or b.get("level") is None:
            return False
        now = time.time()
        last = self.samples[-1] if self.samples else None
        status_changed = last is not None and last["s"] != b["status"]
        if not force and not status_changed and last and now - last["t"] < self.MIN_INTERVAL:
            return False
        self.samples.append({"t": now, "l": round(b["level"], 1), "s": b["status"],
                             "tc": b.get("temp"), "v": b.get("voltage")})
        self.samples = self.samples[-self.MAX_SAMPLES:]

        charging = self._charging(b["status"])
        if charging and self._open is None:
            self._open = {"start": now, "from": b["level"]}
        elif not charging and self._open is not None:
            s = self._open
            s.update({"end": now, "to": b["level"]})
            if s["end"] - s["start"] > 30:
                self.sessions.append(s)
            self._open = None
        self.save()
        return True

    def recent(self, hours=24):
        since = time.time() - hours * 3600
        return [s for s in self.samples if s["t"] >= since]

    def _segment_rates(self, charging, hours=12):
        """Скорость изменения заряда (%/ч) по соседним замерам одного состояния."""
        rates = []
        pts = self.recent(hours)
        for a, b in zip(pts, pts[1:]):
            dt = (b["t"] - a["t"]) / 3600.0
            if dt <= 0 or dt > 2:
                continue
            if self._charging(a["s"]) == charging == self._charging(b["s"]):
                rate = (b["l"] - a["l"]) / dt
                # отбрасываем скачки (например, зарядка, пока приложение было закрыто)
                if (rate < -1 if charging else rate > 1) or abs(rate) > 100:
                    continue
                rates.append(rate)
        return rates

    def discharge_rate(self):
        rates = [-r for r in self._segment_rates(False)]
        return sum(rates) / len(rates) if rates else None

    def charge_rate(self):
        rates = self._segment_rates(True)
        return sum(rates) / len(rates) if rates else None

    def estimate(self, b):
        """Оценка оставшегося времени работы / до полной зарядки (сек)."""
        level = b.get("level")
        if level is None:
            return None, None
        if self._charging(b["status"]):
            rate = self.charge_rate()
            if rate and rate > 0.1:
                return "charge", (100 - level) / rate * 3600
        else:
            rate = self.discharge_rate()
            if rate and rate > 0.05:
                return "discharge", level / rate * 3600
        return None, None

    def stability(self):
        """Оценка стабильности разряда и напряжения.

        Возвращает (оценка, коэффициент вариации в %, описание).
        """
        rates = [-r for r in self._segment_rates(False)]
        volts = [s["v"] for s in self.recent(6) if s.get("v")]
        if len(rates) < 3:
            return "Сбор данных…", None, "Нужно не менее 4 замеров при разрядке (≈5 мин)"
        mean = sum(rates) / len(rates)
        sd = (sum((r - mean) ** 2 for r in rates) / len(rates)) ** 0.5
        cv = 100.0 * sd / abs(mean) if abs(mean) > 0.01 else 0.0
        jumps = sum(1 for r in rates if r > max(3 * abs(mean), 15))
        if cv < 40 and not jumps:
            grade = "Стабильная"
        elif cv < 90 and jumps <= 1:
            grade = "Умеренная"
        else:
            grade = "Нестабильная"
        note = "Средний расход %.1f %%/ч, скачков: %d" % (mean, jumps)
        if len(volts) > 2:
            vmean = sum(volts) / len(volts)
            vsd = (sum((v - vmean) ** 2 for v in volts) / len(volts)) ** 0.5
            note += ", разброс напряжения ±%.0f мВ" % (vsd * 1000)
        return grade, cv, note

    def all_sessions(self):
        items = list(self.sessions)
        if self._open:
            items.append(dict(self._open, end=None, to=None))
        return list(reversed(items))


# ---------------------------------------------------------------------------
#   Система
# ---------------------------------------------------------------------------

def meminfo():
    mem = {}
    for line in (read_file("/proc/meminfo") or "").splitlines():
        parts = line.split()
        if len(parts) >= 2:
            try:
                mem[parts[0].rstrip(":")] = int(parts[1]) * 1024
            except ValueError:
                pass
    total = mem.get("MemTotal")
    avail = mem.get("MemAvailable")
    if avail is None and total:
        avail = mem.get("MemFree", 0) + mem.get("Buffers", 0) + mem.get("Cached", 0)
    if not total and psutil is not None:
        vm = psutil.virtual_memory()
        total, avail = vm.total, vm.available
    return {"total": total, "available": avail,
            "used": (total - avail) if total and avail is not None else None,
            "swap_total": mem.get("SwapTotal"), "swap_free": mem.get("SwapFree"),
            "cached": mem.get("Cached")}


def storage_info():
    candidates = [("Внутренняя (/data)", "/data"), ("Общая память", "/storage/emulated/0"),
                  ("Система", "/system"), ("Корень", "/"), ("Домашний каталог", os.path.expanduser("~"))]
    result, seen = [], set()
    for label, path in candidates:
        try:
            st = os.statvfs(path)
        except Exception:
            continue
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        key = (total, st.f_blocks)
        if total <= 0 or key in seen:
            continue
        seen.add(key)
        result.append({"label": label, "path": path, "total": total,
                       "free": free, "used": total - free})
    return result


def uptime_seconds():
    up = read_file("/proc/uptime")
    if up:
        try:
            return float(up.split()[0])
        except Exception:
            pass
    if psutil is not None:
        try:
            return time.time() - psutil.boot_time()
        except Exception:
            pass
    return None


def is_rooted():
    for p in ("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
              "/data/adb/magisk", "/system/app/Superuser.apk"):
        if os.path.exists(p):
            return True
    return False


def get_system_info():
    build = android_build() if IS_ANDROID else {}
    d = {"android": bool(build)}
    if build:
        d.update(
            model=build.get("model"), manufacturer=(build.get("manufacturer") or "").capitalize(),
            brand=build.get("brand"), device=build.get("device"), product=build.get("product"),
            board=build.get("board"), hardware=build.get("hardware"),
            os="Android %s" % build.get("release", "?"), sdk=build.get("sdk"),
            security_patch=build.get("security_patch"), build_id=build.get("build_id"),
            fingerprint=build.get("fingerprint"),
            bootloader=getprop("ro.bootloader"), baseband=getprop("gsm.version.baseband"),
            selinux=getprop("ro.boot.selinux") or getprop("ro.build.selinux"),
            treble=getprop("ro.treble.enabled"),
            ab=("Да" if getprop("ro.build.ab_update") == "true" else "Нет"),
            lang=getprop("persist.sys.locale") or getprop("ro.product.locale"),
            timezone=getprop("persist.sys.timezone"),
        )
    else:
        d.update(
            model=read_file("/sys/class/dmi/id/product_name") or platform.node(),
            manufacturer=read_file("/sys/class/dmi/id/sys_vendor"),
            os="%s %s" % (platform.system(), platform.release()),
            board=read_file("/sys/class/dmi/id/board_name"),
        )
    d.update(
        kernel=platform.release(),
        kernel_full=(read_file("/proc/version") or platform.version()),
        arch=platform.machine(),
        hostname=platform.node(),
        python="%s %s" % (platform.python_implementation(), platform.python_version()),
        uptime=uptime_seconds(),
        root=is_rooted(),
        memory=meminfo(),
        storage=storage_info(),
        time=time.strftime("%d.%m.%Y %H:%M:%S"),
    )
    if not d.get("timezone"):
        d["timezone"] = time.strftime("%Z")
    return d


# ---------------------------------------------------------------------------
#   Сообщения и предупреждения
# ---------------------------------------------------------------------------

INFO, WARNING, CRITICAL = "INFO", "WARNING", "CRITICAL"


class DeviceMonitor(object):
    """Анализирует показания и формирует сообщения при смене состояний."""

    MAX = 300

    def __init__(self, path=None):
        self.path = path or os.path.join(APP_DIR, "messages.json")
        self.messages = []
        self._state = {}
        try:
            with open(self.path) as f:
                self.messages = json.load(f)
        except Exception:
            pass

    def save(self):
        try:
            with open(self.path, "w") as f:
                json.dump(self.messages[-self.MAX:], f, ensure_ascii=False)
        except Exception:
            pass

    def add(self, level, source, text):
        self.messages.append({"t": time.time(), "level": level, "src": source, "text": text})
        self.messages = self.messages[-self.MAX:]
        self.save()

    def clear(self):
        self.messages = []
        self.save()

    def _transition(self, key, bucket, level, source, text):
        """Сообщение только при изменении «корзины» состояния."""
        if self._state.get(key) != bucket:
            first = key not in self._state
            self._state[key] = bucket
            if bucket is not None and (not first or level != INFO):
                self.add(level, source, text)

    @staticmethod
    def _bucket(value, warn, crit, reverse=False):
        if value is None:
            return None
        if reverse:
            return "crit" if value <= crit else ("warn" if value <= warn else "ok")
        return "crit" if value >= crit else ("warn" if value >= warn else "ok")

    def check(self, cpu, bat, sysinfo):
        t = cpu.get("temp")
        b = self._bucket(t, 70, 85)
        msg = {"ok": "Температура ЦП в норме: %.1f °C",
               "warn": "Высокая температура ЦП: %.1f °C",
               "crit": "Критическая температура ЦП: %.1f °C — возможен троттлинг"}
        if b:
            self._transition("cpu_temp", b, {"ok": INFO, "warn": WARNING, "crit": CRITICAL}[b],
                             "Процессор", msg[b] % t)

        if bat.get("available"):
            lvl = bat.get("level")
            charging = bat["status"] in ("charging", "full")
            b = self._bucket(lvl, 20, 10, reverse=True)
            if charging:  # о начале зарядки сообщит смена статуса ниже
                self._state["bat_level"] = b = "ok"
            msg = {"ok": "Уровень заряда в норме: %.0f %%", "warn": "Низкий заряд батареи: %.0f %%",
                   "crit": "Критически низкий заряд: %.0f %% — подключите зарядку"}
            self._transition("bat_level", b, {"ok": INFO, "warn": WARNING, "crit": CRITICAL}[b],
                             "Батарея", msg[b] % lvl)
            st = bat["status"]
            names = {"charging": "Зарядка начата (%s)" % (bat.get("plugged") or "источник"),
                     "discharging": "Зарядное устройство отключено",
                     "full": "Батарея полностью заряжена",
                     "not_charging": "Подключено, но не заряжается"}
            if st in names:
                self._transition("bat_status", st,
                                 WARNING if st == "not_charging" else INFO, "Батарея", names[st])
            bt = bat.get("temp")
            b = self._bucket(bt, 42, 50)
            msg = {"ok": "Температура батареи в норме: %.1f °C",
                   "warn": "Батарея нагрелась: %.1f °C", "crit": "Перегрев батареи: %.1f °C!"}
            if b:
                self._transition("bat_temp", b, {"ok": INFO, "warn": WARNING, "crit": CRITICAL}[b],
                                 "Батарея", msg[b] % bt)
            h = bat.get("health")
            if h and h not in ("Хорошее", "Неизвестно"):
                self._transition("bat_health", h, CRITICAL, "Батарея", "Состояние батареи: %s" % h)

        mem = sysinfo.get("memory", {})
        if mem.get("total") and mem.get("available") is not None:
            free_pct = 100.0 * mem["available"] / mem["total"]
            b = self._bucket(free_pct, 15, 7, reverse=True)
            msg = {"ok": "Свободной ОЗУ достаточно: %.0f %%", "warn": "Мало свободной ОЗУ: %.0f %%",
                   "crit": "ОЗУ почти заполнена: %.0f %% свободно"}
            self._transition("ram", b, {"ok": INFO, "warn": WARNING, "crit": CRITICAL}[b],
                             "Память", msg[b] % free_pct)
        for st in sysinfo.get("storage", []):
            free_pct = 100.0 * st["free"] / st["total"]
            b = self._bucket(free_pct, 10, 3, reverse=True)
            msg = {"ok": "%s: свободно %.0f %%", "warn": "%s: заканчивается место (%.0f %%)",
                   "crit": "%s: место почти закончилось (%.0f %%)"}
            self._transition("st_" + st["path"], b,
                             {"ok": INFO, "warn": WARNING, "crit": CRITICAL}[b],
                             "Хранилище", msg[b] % (st["label"], free_pct))


def read_system_log(lines=80):
    """Последние предупреждения/ошибки из logcat (на Android — только своего
    процесса без root) или из dmesg/journalctl на ПК."""
    out = run_cmd(["logcat", "-d", "-t", str(lines), "*:W"], timeout=4)
    if not out.strip():
        out = run_cmd(["dmesg"], timeout=3)
        out = "\n".join(out.splitlines()[-lines:])
    return out.strip() or "Журнал недоступен (нет прав доступа)."


# ---------------------------------------------------------------------------
#   Общий источник данных для всех вкладок
# ---------------------------------------------------------------------------

class DeviceData(object):
    """Хранит последний снимок данных; вкладки читают отсюда."""

    def __init__(self):
        self.history = BatteryHistory()
        self.monitor = DeviceMonitor()
        self.cpu, self.battery, self.system = {}, {}, {}
        self.updated = None
        self.extra = {}  # место для данных новых вкладок
        if not self.monitor.messages:
            self.monitor.add(INFO, "Приложение", "Первый запуск. Данные: %s" % APP_DIR)
        self.monitor.add(INFO, "Приложение", "Мониторинг запущен (%s)" %
                         ("Android" if IS_ANDROID else platform.system()))

    def update(self):
        for name, fn in (("cpu", get_cpu_info), ("battery", get_battery_info),
                         ("system", get_system_info)):
            try:
                setattr(self, name, fn())
            except Exception as e:  # данные не должны «ронять» интерфейс
                self.monitor.add(WARNING, "Приложение", "Ошибка чтения %s: %s" % (name, e))
        self.history.add(self.battery)
        try:
            self.monitor.check(self.cpu, self.battery, self.system)
        except Exception as e:
            self.monitor.add(WARNING, "Приложение", "Ошибка анализа: %s" % e)
        self.updated = time.time()


# ===========================================================================
#   2. ИНТЕРФЕЙС
# ===========================================================================


# ---------------------------------------------------------------------------
#   Тема в стиле AIDA64 (тёмная, красный акцент)
# ---------------------------------------------------------------------------

THEME = {
    "bg": hexc("#121417"),
    "header": hexc("#1a1d22"),
    "section": hexc("#2a2f37"),
    "row1": hexc("#1d2127"),
    "row2": hexc("#23282f"),
    "accent": hexc("#e53935"),
    "text": hexc("#eceff1"),
    "muted": hexc("#9aa5b1"),
    "ok": hexc("#43a047"),
    "warn": hexc("#fb8c00"),
    "crit": hexc("#e53935"),
    "info": hexc("#1e88e5"),
    "button": hexc("#2f353e"),
    "button_down": hexc("#e53935"),
    "bar_bg": hexc("#33383f"),
}
NA = "Нет доступа"


def paint(widget, color):
    """Заливает фон виджета цветом и следит за его размером."""
    with widget.canvas.before:
        c = Color(*color)
        rect = Rectangle(pos=widget.pos, size=widget.size)

    def _upd(*_):
        rect.pos, rect.size = widget.pos, widget.size
    widget.bind(pos=_upd, size=_upd)
    widget._bg_color = c
    return c


def level_color(pct, reverse=False, warn=60, crit=85):
    """Цвет по уровню: зелёный/оранжевый/красный."""
    if pct is None:
        return THEME["muted"]
    if reverse:
        pct = 100 - pct
    return THEME["crit"] if pct >= crit else (THEME["warn"] if pct >= warn else THEME["ok"])


def temp_color(t):
    if t is None:
        return THEME["text"]
    return THEME["crit"] if t >= 80 else (THEME["warn"] if t >= 60 else THEME["ok"])


# ---------------------------------------------------------------------------
#   Базовые виджеты
# ---------------------------------------------------------------------------

class WrapLabel(Label):
    """Метка с переносом строк и автоматической высотой."""

    def __init__(self, min_height=dp(34), pad=dp(10), **kw):
        kw.setdefault("halign", "left")
        kw.setdefault("valign", "middle")
        kw.setdefault("font_size", sp(14))
        kw.setdefault("color", THEME["text"])
        kw.setdefault("markup", True)
        super(WrapLabel, self).__init__(size_hint_y=None, **kw)
        self.min_height, self.pad = min_height, pad
        self.bind(width=self._wrap, texture_size=self._height)
        self.height = min_height

    def _wrap(self, *_):
        self.text_size = (self.width - self.pad, None)

    def _height(self, *_):
        self.height = max(self.min_height, self.texture_size[1] + dp(10))


class AidaButton(Button):
    def __init__(self, active=False, **kw):
        kw.setdefault("font_size", sp(14))
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(40))
        super(AidaButton, self).__init__(background_normal="", background_down="", **kw)
        self.set_active(active)

    def set_active(self, active):
        self.background_color = THEME["button_down"] if active else THEME["button"]
        self.color = THEME["text"]


class SectionHeader(BoxLayout):
    def __init__(self, title, **kw):
        super(SectionHeader, self).__init__(size_hint_y=None, height=dp(36), **kw)
        paint(self, THEME["section"])
        bar = Widget(size_hint_x=None, width=dp(4))
        paint(bar, THEME["accent"])
        self.add_widget(bar)
        self.add_widget(WrapLabel(text="[b]%s[/b]" % title, font_size=sp(15),
                                  min_height=dp(36), padding=(dp(10), 0)))


class InfoRow(BoxLayout):
    """Строка «Параметр — Значение», как в таблицах AIDA64."""

    def __init__(self, key, value, odd=False, color=None, **kw):
        super(InfoRow, self).__init__(size_hint_y=None, **kw)
        paint(self, THEME["row2"] if odd else THEME["row1"])
        self.k = WrapLabel(text=str(key), color=THEME["muted"], size_hint_x=0.45,
                           padding=(dp(10), 0))
        self.v = WrapLabel(text=str(value), color=color or THEME["text"], size_hint_x=0.55,
                           padding=(dp(6), 0))
        for lbl in (self.k, self.v):
            lbl.bind(height=self._fit)
            self.add_widget(lbl)
        self._fit()

    def _fit(self, *_):
        self.height = max(self.k.height, self.v.height)


class LevelBar(Widget):
    def __init__(self, value=0, color=None, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(8))
        super(LevelBar, self).__init__(**kw)
        self.value = max(0.0, min(100.0, value or 0))
        with self.canvas:
            Color(*THEME["bar_bg"])
            self._bg = Rectangle()
            Color(*(color or THEME["accent"]))
            self._fg = Rectangle()
        self.bind(pos=self._draw, size=self._draw)

    def _draw(self, *_):
        self._bg.pos, self._bg.size = self.pos, self.size
        self._fg.pos = self.pos
        self._fg.size = (self.width * self.value / 100.0, self.height)


class BarRow(BoxLayout):
    """Строка с подписью, значением и полосой заполнения."""

    def __init__(self, key, value_text, pct, color=None, odd=False, **kw):
        super(BarRow, self).__init__(orientation="vertical", size_hint_y=None,
                                     height=dp(52), padding=(dp(10), dp(4)), **kw)
        paint(self, THEME["row2"] if odd else THEME["row1"])
        top = BoxLayout(size_hint_y=None, height=dp(28))
        key_lbl = Label(text=str(key), color=THEME["muted"], font_size=sp(14),
                        halign="left", valign="middle", size_hint_x=0.4, shorten=True)
        key_lbl.bind(size=lambda w, s: setattr(w, "text_size", s))
        top.add_widget(key_lbl)
        val = Label(text=str(value_text), color=THEME["text"], font_size=sp(14),
                    halign="right", valign="middle", size_hint_x=0.6, markup=True,
                    shorten=True, shorten_from="left")
        val.bind(size=lambda w, s: setattr(w, "text_size", s))
        top.add_widget(val)
        self.add_widget(top)
        self.add_widget(LevelBar(pct, color or level_color(pct)))


class Table(GridLayout):
    """Таблица с заголовком; rows — список списков строк (можно с [color])."""

    def __init__(self, headers, rows, ratios=None, **kw):
        super(Table, self).__init__(cols=len(headers), size_hint_y=None, **kw)
        self.bind(minimum_height=self.setter("height"))
        ratios = ratios or [1.0 / len(headers)] * len(headers)
        self._cells = []
        for i, h in enumerate(headers):
            self._cell("[b]%s[/b]" % h, THEME["section"], ratios[i], THEME["accent"])
        for r, row in enumerate(rows):
            bg = THEME["row2"] if r % 2 else THEME["row1"]
            for i, val in enumerate(row):
                self._cell("—" if val is None else str(val), bg, ratios[i])

    def _cell(self, text, bg, ratio, color=None):
        lbl = WrapLabel(text=text, font_size=sp(13), min_height=dp(30),
                        color=color or THEME["text"], size_hint_x=ratio,
                        padding=(dp(6), 0))
        paint(lbl, bg)
        self.add_widget(lbl)


class LineGraph(Widget):
    """Простой график значений 0..100 (история заряда)."""

    def __init__(self, points, ymin=0, ymax=100, color=None, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(150))
        super(LineGraph, self).__init__(**kw)
        self.points, self.ymin, self.ymax = points, ymin, ymax
        self.color = color or THEME["accent"]
        self.bind(pos=self._draw, size=self._draw)

    def _draw(self, *_):
        self.canvas.clear()
        x0, y0, w, h = self.x + dp(8), self.y + dp(8), self.width - dp(16), self.height - dp(16)
        with self.canvas:
            Color(*THEME["row1"])
            Rectangle(pos=self.pos, size=self.size)
            Color(*THEME["bar_bg"])
            for i in range(5):
                y = y0 + h * i / 4.0
                Line(points=[x0, y, x0 + w, y], width=1)
            if len(self.points) < 2:
                return
            tmin, tmax = self.points[0][0], self.points[-1][0]
            span = (tmax - tmin) or 1
            pts = []
            for t, v in self.points:
                pts += [x0 + w * (t - tmin) / span,
                        y0 + h * (v - self.ymin) / float(self.ymax - self.ymin)]
            Color(*self.color)
            Line(points=pts, width=dp(1.5))


# ---------------------------------------------------------------------------
#   Базовая вкладка и реестр вкладок
# ---------------------------------------------------------------------------

TAB_REGISTRY = []


def register_tab(order=100):
    """Декоратор: регистрирует вкладку. order — позиция в панели вкладок."""
    def deco(cls):
        cls.order = order
        TAB_REGISTRY.append(cls)
        return cls
    return deco


def get_tabs():
    return sorted(TAB_REGISTRY, key=lambda c: c.order)


class BaseTab(BoxLayout):
    title = "Вкладка"
    auto_refresh = True  # обновлять ли по таймеру

    def __init__(self, data, **kw):
        super(BaseTab, self).__init__(orientation="vertical", **kw)
        self.data = data
        paint(self, THEME["bg"])
        buttons = self.toolbar()
        if buttons:
            bar = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6),
                            padding=(dp(8), dp(4)))
            paint(bar, THEME["header"])
            self.toolbar_buttons = []
            for text, cb in buttons:
                b = AidaButton(text=text)
                b.bind(on_release=lambda btn, cb=cb: cb(btn))
                bar.add_widget(b)
                self.toolbar_buttons.append(b)
            self.add_widget(bar)
        self.scroll = ScrollView(do_scroll_x=False, bar_width=dp(4),
                                 bar_color=THEME["accent"])
        self.content = BoxLayout(orientation="vertical", size_hint_y=None,
                                 spacing=dp(1), padding=(0, 0, 0, dp(12)))
        self.content.bind(minimum_height=self.content.setter("height"))
        self.scroll.add_widget(self.content)
        self.add_widget(self.scroll)
        self._odd = False

    # --- переопределяется в наследниках ---
    def toolbar(self):
        """Список кнопок панели инструментов: [(текст, callback), ...]."""
        return []

    def build(self):
        raise NotImplementedError

    # --- общие методы ---
    def refresh(self):
        self.content.clear_widgets()
        self._odd = False
        try:
            self.build()
        except Exception as e:  # ошибка одной вкладки не ломает приложение
            self.section("Ошибка")
            self.row("Исключение", "%s: %s" % (type(e).__name__, e), color=THEME["crit"])

    def add(self, widget):
        self.content.add_widget(widget)
        return widget

    def section(self, title):
        self._odd = False
        self.add(Widget(size_hint_y=None, height=dp(6)))
        return self.add(SectionHeader(title))

    def row(self, key, value, color=None, unit=""):
        if value is None or value == "":
            value, color = NA, THEME["muted"]
        else:
            value = "%s%s" % (value, unit)
        self._odd = not self._odd
        return self.add(InfoRow(key, value, odd=self._odd, color=color))

    def bar(self, key, value_text, pct, color=None):
        self._odd = not self._odd
        return self.add(BarRow(key, value_text, pct, color=color, odd=self._odd))

    def table(self, headers, rows, ratios=None):
        return self.add(Table(headers, rows, ratios))

    def note(self, text, color=None):
        lbl = WrapLabel(text=text, color=color or THEME["muted"], font_size=sp(13),
                        padding=(dp(10), dp(4)))
        return self.add(lbl)


# ---------------------------------------------------------------------------
#   Вкладка «Процессор»
# ---------------------------------------------------------------------------

@register_tab(order=10)
class CpuTab(BaseTab):
    title = "Процессор"

    def build(self):
        c = self.data.cpu
        self.section("Центральный процессор")
        self.row("Название", c.get("name"))
        if c.get("codename") and c.get("codename") != c.get("name"):
            self.row("Кодовое имя", c.get("codename"))
        self.row("Производитель", c.get("vendor"))
        self.row("Архитектура", c.get("arch"))
        self.row("Набор инструкций (ABI)", c.get("abi"))
        self.row("Разрядность", c.get("bits"))
        self.row("Ядра", "%s (активно: %s)" % (c.get("cores_total"), c.get("cores_online")))
        if c.get("revision"):
            self.row("Ревизия", c.get("revision"))
        if c.get("bogomips"):
            self.row("BogoMIPS", c.get("bogomips"))
        self.row("Регулятор частоты", c.get("governor"))
        self.row("Операционная система", c.get("os"))
        self.row("Ядро Linux", c.get("kernel"))

        self.section("Кластеры")
        rows = []
        for cl in c.get("clusters", []):
            ids = cl["cores"]
            rng = "%d–%d" % (ids[0], ids[-1]) if len(ids) > 1 else str(ids[0])
            rows.append(["%d × %s" % (len(ids), cl.get("name") or "ядро"), rng,
                         "%s – %s" % (fmt_freq_khz(cl.get("min")) or "?",
                                      fmt_freq_khz(cl.get("max")) or "?")])
        self.table(["Тип", "Ядра", "Частота"], rows, [0.42, 0.2, 0.38])

        self.section("Частоты ядер")
        usage = c.get("usage_per_core") or []
        for core in c.get("cores", []):
            if not core["online"]:
                self.row("Ядро %d" % core["id"], "Отключено", color=THEME["muted"])
                continue
            cur, mx = core.get("cur"), core.get("max")
            pct = 100.0 * cur / mx if cur and mx else 0
            text = fmt_freq_khz(cur) or NA
            if core["id"] < len(usage) and usage[core["id"]] is not None:
                text += "  [color=#9aa5b1]| %.0f %%[/color]" % usage[core["id"]]
            self.bar("Ядро %d" % core["id"], text, pct, color=THEME["info"])

        self.section("Загрузка и температура")
        u = c.get("usage")
        if u is not None:
            self.bar("Загрузка ЦП", "%.1f %%" % u, u, color=level_color(u))
        else:
            self.row("Загрузка ЦП", None)
            self.note("На Android 8+ /proc/stat недоступен обычным приложениям.")
        if c.get("loadavg"):
            self.row("Средняя нагрузка", "%.2f / %.2f / %.2f" % tuple(c["loadavg"]))
        t = c.get("temp")
        if t is not None:
            self.bar("Температура ЦП", "%.1f °C" % t, t, color=temp_color(t))
        else:
            self.row("Температура ЦП", None)
        zones = c.get("zones") or []
        if zones:
            self.section("Датчики температуры")
            self.table(["Датчик", "°C"],
                       [[n, "[color=%s]%.1f[/color]" % (_hex(temp_color(v)), v)]
                        for n, v in zones[:40]], [0.7, 0.3])
        if c.get("features"):
            self.section("Возможности (флаги)")
            self.note(c["features"], color=THEME["text"])


def _hex(color):
    return "#%02x%02x%02x" % tuple(int(x * 255) for x in color[:3])


# ---------------------------------------------------------------------------
#   Вкладка «Батарея»
# ---------------------------------------------------------------------------

STATUS_RU = {"charging": "Заряжается", "discharging": "Разряжается", "full": "Заряжена",
             "not_charging": "Не заряжается", "unknown": "Неизвестно"}


@register_tab(order=20)
class BatteryTab(BaseTab):
    title = "Батарея"

    def toolbar(self):
        return [("Замер сейчас", self._sample), ("Очистить историю", self._clear),
                ("Сброс калибровки", self._reset_calib)]

    def _reset_calib(self, *_):
        battery_calibration().reset()
        self.data.monitor.add(INFO, "Батарея", "Калибровка тока и ёмкости сброшена")
        self.refresh()

    def _sample(self, *_):
        self.data.history.add(get_battery_info(), force=True)
        self.refresh()

    def _clear(self, *_):
        self.data.history.clear()
        self.data.monitor.add(INFO, "Батарея", "История батареи очищена")
        self.refresh()

    def build(self):
        b, h = self.data.battery, self.data.history
        self.section("Состояние батареи")
        if not b.get("available"):
            self.row("Батарея", "Не обнаружена или нет доступа", color=THEME["muted"])
            self.note("На Android данные читаются через BatteryManager (pyjnius). "
                      "Запустите приложение в Pydroid 3 на телефоне.")
            return
        lvl = b["level"]
        self.bar("Уровень заряда", "[b]%.0f %%[/b]" % lvl, lvl,
                 color=level_color(lvl, reverse=True, warn=70, crit=85))
        self.row("Статус", STATUS_RU.get(b["status"], b["status"]),
                 color=THEME["ok"] if b["status"] in ("charging", "full") else None)
        self.row("Источник питания", b.get("plugged") or "Батарея")
        self.row("Состояние (здоровье)", b.get("health"),
                 color=THEME["ok"] if b.get("health") == "Хорошее" else THEME["warn"])
        self.row("Технология", b.get("technology"))
        t = b.get("temp")
        self.row("Температура", "%.1f °C" % t if t is not None else None,
                 color=temp_color(t + 20) if t is not None else None)
        self.row("Напряжение", "%.3f В" % b["voltage"] if b.get("voltage") else None)
        cur = b.get("current")
        if cur is not None:
            self.row("Ток", "%+.0f мА (%s)" % (cur, "в батарею" if cur > 0 else "из батареи"),
                     color=THEME["ok"] if cur > 0 else None)
        else:
            self.row("Ток", None)
        if b.get("current_avg") is not None:
            self.row("Средний ток", "%+.0f мА" % b["current_avg"])
        if b.get("power") is not None:
            p = b["power"]
            self.row("Мощность", "%.2f Вт (%s)" % (abs(p), "зарядка" if p > 0 else "расход"))
        if b.get("max_charger"):
            a, v, w = b["max_charger"]
            self.row("Зарядное устройство (лимит)", "%.2f А × %.1f В ≈ %.1f Вт" % (a, v, w))
        if b.get("charging_status"):
            self.row("Режим зарядки", b["charging_status"])
        if b.get("charge_time_left"):
            self.row("До полной (оценка Android)", fmt_duration(b["charge_time_left"]),
                     color=THEME["info"])

        self.section("Ёмкость и износ")
        if b.get("charge_counter"):
            self.row("Текущий заряд", "%.0f мА·ч" % b["charge_counter"])
        self.row("Паспортная ёмкость", "%.0f мА·ч" % b["capacity_design"]
                 if b.get("capacity_design") else None)
        if b.get("capacity_design_src"):
            self.row("  источник", b["capacity_design_src"], color=THEME["muted"])
        if b.get("capacity_full"):
            self.row("Фактическая ёмкость", "%.0f мА·ч" % b["capacity_full"])
            self.row("  источник", b.get("capacity_full_src"), color=THEME["muted"])
        else:
            self.row("Фактическая ёмкость", "Нужен заряд ≥ 80 %", color=THEME["muted"])
        if b.get("health_pct") is not None:
            hp = b["health_pct"]
            self.bar("Здоровье батареи", "%.0f %%" % hp, hp,
                     color=level_color(hp, reverse=True, warn=20, crit=40))
        if b.get("wear") is not None:
            self.row("Износ", "%.1f %%" % b["wear"], color=level_color(b["wear"], warn=20, crit=40))
        if b.get("energy"):
            self.row("Энергия", "%.2f Вт·ч" % b["energy"])
        self.row("Циклы зарядки", b.get("cycles") if b.get("cycles") is not None else
                 "Прошивка не сообщает")
        if b.get("battery_low"):
            self.row("Низкий заряд", "Да (системный флаг)", color=THEME["warn"])
        self.row("Источник данных", b.get("source"))
        self.row("Единицы тока/заряда", b.get("units"))
        if b.get("sign_inverted"):
            self.row("Знак тока", "Инвертирован прошивкой (исправлено)", color=THEME["muted"])
        if (b.get("capacity_full_src") or "").startswith("Оценка") and b.get("capacity_design"):
            self.note("Фактическая ёмкость оценивается по счётчику заряда контроллера. "
                      "На MediaTek он иногда равен профилю батареи, тогда износ не виден.")

        self.section("Время работы")
        self.row("С момента включения", fmt_duration(self.data.system.get("uptime")))
        kind, secs = h.estimate(b)
        if kind == "discharge":
            self.row("Осталось работы (оценка)", fmt_duration(secs), color=THEME["ok"])
        elif kind == "charge":
            self.row("До полной зарядки (оценка)", fmt_duration(secs), color=THEME["info"])
        else:
            self.row("Оценка времени", "Сбор данных…", color=THEME["muted"])
        dr, cr = h.discharge_rate(), h.charge_rate()
        self.row("Скорость разряда", "%.1f %%/ч" % dr if dr else None)
        self.row("Скорость зарядки", "%.1f %%/ч" % cr if cr else None)
        if b["status"] not in ("charging", "full"):
            last_charge = next((s for s in h.all_sessions() if s.get("end")), None)
            if last_charge:
                self.row("С последней зарядки", fmt_duration(time.time() - last_charge["end"]))

        self.section("Стабильность")
        grade, cv, note = h.stability()
        col = {"Стабильная": THEME["ok"], "Умеренная": THEME["warn"],
               "Нестабильная": THEME["crit"]}.get(grade, THEME["muted"])
        self.row("Оценка", grade, color=col)
        if cv is not None:
            self.row("Коэффициент вариации", "%.0f %%" % cv)
        self.note(note)

        self.section("График заряда за 24 ч")
        pts = [(s["t"], s["l"]) for s in h.recent(24)]
        self.add(LineGraph(pts))
        if pts:
            self.note("Замеров: %d, с %s по %s. Замер делается раз в минуту, пока "
                      "приложение открыто." % (len(pts), fmt_time(pts[0][0], True),
                                               fmt_time(pts[-1][0], True)))

        self.section("История зарядок")
        rows = []
        for s in h.all_sessions()[:20]:
            end = s.get("end")
            rows.append([fmt_time(s["start"], True),
                         "%.0f » %s" % (s["from"], "%.0f %%" % s["to"] if end else "…"),
                         fmt_duration((end or time.time()) - s["start"])])
        if rows:
            self.table(["Начало", "Заряд", "Длительность"], rows, [0.33, 0.32, 0.35])
        else:
            self.note("Зарядок пока не зафиксировано. Подключите зарядное устройство, "
                      "пока приложение открыто.")

        raw = b.get("raw") or {}
        if raw:
            self.section("Сырые значения (диагностика)")
            self.table(["Поле", "Значение"], [[k, str(v)] for k, v in sorted(raw.items())],
                       [0.55, 0.45])


# ---------------------------------------------------------------------------
#   Вкладка «Система»
# ---------------------------------------------------------------------------

@register_tab(order=30)
class SystemTab(BaseTab):
    title = "Система"

    def build(self):
        s = self.data.system
        self.section("Устройство")
        self.row("Модель", s.get("model"))
        self.row("Производитель", s.get("manufacturer"))
        if s.get("android"):
            self.row("Бренд", s.get("brand"))
            self.row("Кодовое имя", s.get("device"))
            self.row("Продукт", s.get("product"))
            self.row("Плата", s.get("board"))
            self.row("Оборудование", s.get("hardware"))
        else:
            self.row("Плата", s.get("board"))

        self.section("Операционная система")
        self.row("ОС", s.get("os"))
        if s.get("android"):
            self.row("Уровень API", s.get("sdk"))
            self.row("Патч безопасности", s.get("security_patch"))
            self.row("Сборка", s.get("build_id"))
            self.row("Загрузчик", s.get("bootloader"))
            self.row("Модем (baseband)", s.get("baseband"))
            self.row("Project Treble", "Да" if s.get("treble") == "true" else "Нет")
            self.row("A/B-обновления", s.get("ab"))
            self.row("Язык", s.get("lang"))
        self.row("Ядро", s.get("kernel"))
        self.row("Архитектура", s.get("arch"))
        self.row("Root-доступ", "Обнаружен" if s.get("root") else "Нет",
                 color=THEME["warn"] if s.get("root") else THEME["ok"])
        self.row("Время работы", fmt_duration(s.get("uptime")))
        self.row("Часовой пояс", s.get("timezone"))
        self.row("Дата и время", s.get("time"))
        self.row("Python", s.get("python"))

        self.section("Оперативная память")
        m = s.get("memory") or {}
        if m.get("total"):
            pct = 100.0 * m["used"] / m["total"] if m.get("used") is not None else 0
            self.bar("Использовано", "%s / %s" % (fmt_bytes(m["used"]),
                                                  fmt_bytes(m["total"])), pct,
                     color=level_color(pct, warn=75, crit=90))
            self.row("Всего", fmt_bytes(m["total"]))
            self.row("Доступно", fmt_bytes(m.get("available")))
            if m.get("cached"):
                self.row("Кэш", fmt_bytes(m["cached"]))
            if m.get("swap_total"):
                self.row("Swap / zRAM", "%s (свободно %s)" % (
                    fmt_bytes(m["swap_total"]), fmt_bytes(m.get("swap_free"))))
        else:
            self.row("ОЗУ", None)

        self.section("Хранилище")
        for st in s.get("storage", []):
            pct = 100.0 * st["used"] / st["total"]
            self.bar(st["label"], "своб. %s из %s" % (fmt_bytes(st["free"]),
                                                        fmt_bytes(st["total"])),
                     pct, color=level_color(pct, warn=80, crit=93))

        self.section("Экран")
        try:
            from kivy.core.window import Window
            from kivy.metrics import Metrics
            self.row("Разрешение окна", "%d × %d пикс." % tuple(Window.size))
            self.row("Плотность", "%.0f dpi (×%.2f)" % (Metrics.dpi, Metrics.density))
        except Exception:
            self.row("Экран", None)

        if s.get("android") and s.get("fingerprint"):
            self.section("Отпечаток сборки")
            self.note(s["fingerprint"], color=THEME["text"])
        self.section("Версия ядра")
        self.note(s.get("kernel_full") or NA, color=THEME["text"])


# ---------------------------------------------------------------------------
#   Вкладка «Сообщения»
# ---------------------------------------------------------------------------

LEVEL_STYLE = {INFO: ("ИНФО", "info"), WARNING: ("ВНИМАНИЕ", "warn"),
               CRITICAL: ("КРИТИЧНО", "crit")}


class MessageItem(BoxLayout):
    def __init__(self, msg, odd=False, **kw):
        super(MessageItem, self).__init__(size_hint_y=None, **kw)
        paint(self, THEME["row2"] if odd else THEME["row1"])
        name, key = LEVEL_STYLE.get(msg["level"], (msg["level"], "info"))
        stripe = Widget(size_hint_x=None, width=dp(5))
        paint(stripe, THEME[key])
        self.add_widget(stripe)
        text = ("[color=%s][b]%s[/b][/color]  [color=#9aa5b1]%s · %s[/color]\n%s"
                % (_hex(THEME[key]), name, fmt_time(msg["t"], True), msg["src"],
                   msg["text"]))
        self.lbl = WrapLabel(text=text, padding=(dp(10), dp(4)), min_height=dp(52))
        self.lbl.bind(height=lambda w, h: setattr(self, "height", h))
        self.add_widget(self.lbl)
        self.height = self.lbl.height


@register_tab(order=40)
class MessagesTab(BaseTab):
    title = "Сообщения"
    FILTERS = [("Все", None), ("Важные", (WARNING, CRITICAL)),
               ("Критичные", (CRITICAL,))]

    def __init__(self, data, **kw):
        self._filter = 0
        self._show_log = False
        super(MessagesTab, self).__init__(data, **kw)

    def toolbar(self):
        return [(name, lambda btn, i=i: self._set_filter(i))
                for i, (name, _) in enumerate(self.FILTERS)] + \
               [("Журнал", self._toggle_log), ("Очистить", self._clear)]

    def _set_filter(self, i):
        self._filter, self._show_log = i, False
        self.refresh()

    def _toggle_log(self, *_):
        self._show_log = not self._show_log
        self.refresh()

    def _clear(self, *_):
        self.data.monitor.clear()
        self.refresh()

    def build(self):
        for i, b in enumerate(getattr(self, "toolbar_buttons", [])):
            b.font_size = sp(12)
            b.set_active(i == self._filter and not self._show_log
                         or (i == len(self.FILTERS) and self._show_log))
        if self._show_log:
            self.section("Системный журнал (logcat / dmesg)")
            self.note(read_system_log().replace("&", "&amp;").replace("[", "&bl;").replace("]", "&br;"),
                      color=THEME["text"])
            return
        allowed = self.FILTERS[self._filter][1]
        msgs = [m for m in reversed(self.data.monitor.messages)
                if allowed is None or m["level"] in allowed]
        counts = {lvl: sum(1 for m in self.data.monitor.messages if m["level"] == lvl)
                  for lvl in LEVEL_STYLE}
        self.section("Сводка")
        self.row("Критичных", counts[CRITICAL],
                 color=THEME["crit"] if counts[CRITICAL] else THEME["ok"])
        self.row("Предупреждений", counts[WARNING],
                 color=THEME["warn"] if counts[WARNING] else THEME["ok"])
        self.row("Информационных", counts[INFO])
        self.section("События (%d)" % len(msgs))
        if not msgs:
            self.note("Сообщений нет.")
        for i, m in enumerate(msgs[:150]):
            self.add(MessageItem(m, odd=bool(i % 2)))


# ===========================================================================
#   3. ПРИЛОЖЕНИЕ
# ===========================================================================

REFRESH_SECONDS = 3  # период автообновления активной вкладки


class AidaApp(App):
    title = "AIDA Py"

    def build(self):
        Window.clearcolor = THEME["bg"]
        self.data = DeviceData()
        self.data.update()

        root = BoxLayout(orientation="vertical")
        paint(root, THEME["bg"])
        root.add_widget(self._header())
        root.add_widget(self._tab_bar())

        self.sm = ScreenManager(transition=NoTransition())
        self.tabs = {}
        for cls in get_tabs():
            screen = Screen(name=cls.__name__)
            tab = cls(self.data)
            screen.add_widget(tab)
            self.sm.add_widget(screen)
            self.tabs[cls.__name__] = tab
        root.add_widget(self.sm)
        root.add_widget(self._status_bar())

        self.switch_tab(get_tabs()[0].__name__)
        Clock.schedule_interval(self.tick, REFRESH_SECONDS)
        return root

    # --- элементы интерфейса ---
    def _header(self):
        bar = BoxLayout(size_hint_y=None, height=dp(56), padding=(dp(12), dp(6)),
                        spacing=dp(8))
        paint(bar, THEME["header"])
        logo = Label(text="[b][color=#e53935]AIDA[/color] Py[/b]", markup=True,
                     font_size=sp(22), size_hint_x=None, width=dp(110))
        model = self.data.system.get("model") or "Устройство"
        sub = Label(text="%s\n[color=#9aa5b1]%s[/color]" % (model, self.data.system.get("os", "")),
                    markup=True, font_size=sp(13), halign="left", valign="middle")
        sub.bind(size=lambda w, s: setattr(w, "text_size", s))
        refresh = AidaButton(text="Обновить", size_hint_x=None, width=dp(96),
                                   height=dp(44), active=True)
        refresh.bind(on_release=lambda *_: self.tick(0))
        for w in (logo, sub, refresh):
            bar.add_widget(w)
        return bar

    def _tab_bar(self):
        scroll = ScrollView(size_hint_y=None, height=dp(50), do_scroll_y=False,
                            bar_width=0)
        row = BoxLayout(size_hint_x=None, spacing=dp(4), padding=(dp(4), dp(4)))
        row.bind(minimum_width=row.setter("width"))
        paint(scroll, THEME["header"])
        self.tab_buttons = {}
        tabs = get_tabs()
        for cls in tabs:
            btn = AidaButton(text=cls.title, size_hint_x=None, height=dp(42),
                                   width=max(dp(84), (Window.width - dp(8)) / len(tabs) - dp(4)))
            btn.bind(on_release=lambda b, n=cls.__name__: self.switch_tab(n))
            row.add_widget(btn)
            self.tab_buttons[cls.__name__] = btn
        scroll.add_widget(row)
        return scroll

    def _status_bar(self):
        self.status = Label(size_hint_y=None, height=dp(26), font_size=sp(12),
                            color=THEME["muted"])
        paint(self.status, THEME["header"])
        return self.status

    # --- логика ---
    def switch_tab(self, name):
        for n, b in self.tab_buttons.items():
            b.set_active(n == name)
        self.sm.current = name
        self.tabs[name].refresh()
        self._update_status()

    def tick(self, dt):
        self.data.update()
        tab = self.tabs[self.sm.current]
        # dt == 0 — ручное обновление: обновляем любую вкладку
        if tab.auto_refresh or dt == 0:
            y = tab.scroll.scroll_y
            tab.refresh()
            tab.scroll.scroll_y = y
        self._update_status()

    def _update_status(self):
        warn = sum(1 for m in self.data.monitor.messages
                   if m["level"] != INFO)
        self.status.text = "Обновлено: %s   •   Предупреждений: %d   •   авто %d с" % (
            fmt_time(self.data.updated or 0), warn, REFRESH_SECONDS)

    def on_pause(self):  # Android: разрешаем сворачивание
        return True

    def on_stop(self):
        self.data.history.save()
        self.data.monitor.save()


if __name__ == "__main__":
    AidaApp().run()
