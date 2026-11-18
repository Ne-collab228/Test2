# -*- coding: utf-8 -*-
"""
utils.py — логика получения данных об устройстве (без интерфейса).

Источники данных (в порядке приоритета):
  1. Android API через pyjnius (есть в Pydroid 3 вместе с Kivy);
  2. файлы ядра Linux: /proc/*, /sys/*;
  3. команды getprop / logcat;
  4. модули platform, os и (если установлен) psutil — для запуска на ПК.

Любая функция возвращает данные даже без прав доступа: недоступные
значения равны None, а интерфейс показывает «Нет доступа».
"""

import glob
import json
import os
import platform
import re
import subprocess
import time
from datetime import datetime

try:
    import psutil  # необязательно; на Android обычно не нужен
except Exception:  # pragma: no cover
    psutil = None


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


def _empty_battery():
    return {"available": False, "source": None, "level": None, "status": "unknown",
            "plugged": None, "health": None, "temp": None, "voltage": None,
            "current": None, "technology": None, "capacity_full": None,
            "capacity_design": None, "cycles": None, "charge_counter": None}


def _battery_android(b):
    ctx = android_context()
    if ctx is None:
        return False
    try:
        Intent = jclass("android.content.Intent")
        IntentFilter = jclass("android.content.IntentFilter")
        BM = jclass("android.os.BatteryManager")
        intent = ctx.registerReceiver(None, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if intent is None:
            return False
        level = intent.getIntExtra(BM.EXTRA_LEVEL, -1)
        scale = intent.getIntExtra(BM.EXTRA_SCALE, 100) or 100
        b["level"] = 100.0 * level / scale if level >= 0 else None
        b["status"] = STATUS_API.get(intent.getIntExtra(BM.EXTRA_STATUS, 1), "unknown")
        b["health"] = HEALTH_API.get(intent.getIntExtra(BM.EXTRA_HEALTH, 1))
        b["plugged"] = PLUGGED_API.get(intent.getIntExtra(BM.EXTRA_PLUGGED, 0))
        t = intent.getIntExtra(BM.EXTRA_TEMPERATURE, -10000)
        b["temp"] = t / 10.0 if t > -10000 else None
        v = intent.getIntExtra(BM.EXTRA_VOLTAGE, -1)
        b["voltage"] = (v / 1000.0 if v > 100 else float(v)) if v > 0 else None
        b["technology"] = intent.getStringExtra(BM.EXTRA_TECHNOLOGY)
        try:
            cycles = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)  # Android 14+
            b["cycles"] = cycles if cycles >= 0 else None
        except Exception:
            pass
        try:
            bm = ctx.getSystemService(jclass("android.content.Context").BATTERY_SERVICE)
            cur = bm.getIntProperty(BM.BATTERY_PROPERTY_CURRENT_NOW)
            if cur not in (0, -2147483648, 2147483647):
                b["current"] = cur / 1000.0 if abs(cur) > 20000 else float(cur)
            cc = bm.getIntProperty(BM.BATTERY_PROPERTY_CHARGE_COUNTER)
            if cc > 0:
                b["charge_counter"] = cc / 1000.0
        except Exception:
            pass
        b["available"] = b["level"] is not None
        b["source"] = "Android API"
        return b["available"]
    except Exception:
        return False


def _battery_sysfs(b):
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

    def micro(name, div=1000.0):
        v = read_int(base + "/" + name)
        return v / div if v is not None else None

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
        if cur is not None:
            b["current"] = cur / 1000.0 if abs(cur) > 20000 else float(cur)
    b["technology"] = b["technology"] or read_file(base + "/technology")
    b["capacity_full"] = micro("charge_full")
    b["capacity_design"] = micro("charge_full_design")
    if b["cycles"] is None:
        b["cycles"] = read_int(base + "/cycle_count")
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
    _battery_sysfs(b)  # дополняет недостающие поля (ёмкость, циклы)
    if not b["available"]:
        _battery_psutil(b)
    if b["plugged"] is None and b["status"] in ("charging", "full"):
        b["plugged"] = "Подключено"
    if b["capacity_full"] and b["capacity_design"]:
        b["wear"] = max(0.0, 100.0 * (1 - b["capacity_full"] / b["capacity_design"]))
    else:
        b["wear"] = None
    if b["voltage"] and b["current"] is not None:
        b["power"] = b["voltage"] * b["current"] / 1000.0
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


if __name__ == "__main__":  # быстрый тест без интерфейса
    data = DeviceData()
    data.update()
    time.sleep(0.5)
    data.update()
    print(json.dumps({"cpu": {k: v for k, v in data.cpu.items() if k not in ("cores", "zones")},
                      "battery": data.battery,
                      "system": {k: v for k, v in data.system.items() if k != "storage"}},
                     ensure_ascii=False, indent=1, default=str))
