# Исследование: процессор (SoC) на Honor X6c — Helio G81 Ultra

Дата: 2026-11-21. Цель — правильно определить название чипа, частоты, загрузку и
температуры из обычного приложения (Pydroid 3 + pyjnius) на Android 13–15.

## 0. Модель телефона

- Honor X6c = **NIC-LX1 / NIC-LX2 / NIC-LX3** (не JDY — JDY-LX* это Honor X6b на Helio G85).
- Geekbench NIC-LX2: motherboard (`ro.product.board`) = **`NICL`**, процессор = **`MT6769V/CB`**,
  6×1.70 ГГц + 2×2.00 ГГц, Android 15.
  (browser.geekbench.com/v6/cpu/12924746, /v5/cpu/23435025 — данные из сниппетов поиска)
- JDY-LX1 (X6b) в Geekbench: `MT6769V/CZ` (G85).

## 1. getprop на G81 / G81 Ultra и родственных

- Неизвестный телефон на G81 Ultra (Android 16, без root): `ro.board.platform=mt6768`,
  `ro.soc.model=MT6769` (github.com/Kamrulhasan12345/LITTLETai).
- Redmi 14C / POCO C75 (`lake`): платформа MT6768/MT6769; Xiaomi в FAQ пишет «MT6769V».
- Redmi 10 2022 (G88), полный дамп: `ro.soc.model=MT6769H`, `ro.board.platform=mt6768`,
  `ro.hardware=mt6768`, `ro.vendor.mediatek.platform=MT6768`, `ro.product.board=selene`
  (github.com/getActivity/AndroidSystemPropertyCollect).
- **`ro.soc.model` ненадёжен**: Redmi 13C (G85, `gale`) — просто `MT6768`; Redmi 9 (G80) — `MT6769T`.
- `ro.vendor.soc.model.external_name` / `part_name` ставит MTK `chipinfo.sh` из
  `/proc/device-tree/model-external-name` / `model-part-name`, иначе копия `ro.soc.model`.
- Honor/MagicOS: `ro.config.cpu_info_display` — маркетинговое имя чипа (часто по-китайски),
  `msc.*`, `ro.product.board` (у X6c — «NICL»), `ro.build.version.magic`.
  Значение `cpu_info_display` на X6c не найдено — читать первым.
- `Build.SOC_MODEL` = `ro.soc.model`, `Build.HARDWARE` = `ro.hardware` («mt6768»).
- В `/proc/cpuinfo` на arm64 с GKI 5.10+/6.x **строки `Hardware` нет**.

## 2. Номера MediaTek → маркетинговые имена

| Номер | Имя | A75/A55 ГГц, GPU МГц | Уверенность |
|---|---|---|---|
| MT6768 | P65 (и «платформа» всего семейства) | 2.0/1.7, 820 | высокая |
| MT6769V/CB | G70; **у G81/G81 Ultra та же маркировка** (Honor NIC-LX2) | 2.0/1.7, 820 | высокая |
| MT6769J/L/S | G81 / G81 Ultra | 2.0/1.7, 820 | средняя |
| MT6769T, V/CT, V/CU | G80 | 2.0/1.8, 950 | высокая |
| MT6769Z, V/CZ | G85 | 2.0/1.8, 1000 | высокая |
| MT6769H | G88 | 2.0/1.8, ~1000 | высокая |
| MT6769G/K | G91 (/Ultra) | 2.0/1.8, 1000 | средняя |
| MT6769I | G92 | — | низкая |
| MT6765 | P35; G/V/CB — G35; V/XAA,XBA — G36; H — G37 | | высокая |
| MT6762 | P22; G — G25; V — A25 | | высокая |
| MT6761 | A22 (V/WAB,WBB), A20 (V/WE) | | высокая |
| MT6785 | G90 / G90T (V/CC) / G95 (V/CD) | | высокая |
| MT6781 | G96 | | высокая |
| MT6789 | G99; H/I/J — G100 | | высокая |
| MT6833 | D700/6020; P — 810/6080 | | высокая |
| MT6835 | 6100+; T — 6300 | | высокая |
| MT6853 | D720; T — 800U | | высокая |
| MT6855 | D930/7020; V/ATZA — 7025 | | высокая |
| MT6877 | D900/920/1080/7050 | | высокая |
| MT6878 | 7300/7400 | | высокая |
| MT6886 | 7200 | | высокая |
| MT6895/6896/6897/6899 | 8100/8200/8300/8400 | | высокая |
| MT6983/6985/6989/6991/6993 | Dimensity 9000/9200/9300/9400/9500 | | высокая |

Измерения Redmi 14C (`lake`, G81 Ultra): `cpuinfo_max_freq` малые = 1700000, большие = 2000000;
GPU OPP в `/proc/gpufreqv2` рабочая до 823 МГц (github.com/rianprei/poco-c75-lake-kernel).

**Вывод:** G81 Ultra — тот же кристалл, что G70, с теми же частотами. Соответствие
`mt6769 → G85` в наброске неверно.

## 3. Как определяют DevCheck / CPU-Z / AIDA64

Исходники закрыты. Косвенно: своя база по `SOC_MODEL` / `ro.soc.model` / `ro.board.platform`
/ `ro.hardware` + частоты кластеров + GL_RENDERER. Geekbench показывает полный part name.
pytorch/cpuinfo сопоставляет только номер MTxxxx (src/arm/linux/chipset.c).

Различение при голом «MT6768/MT6769»:
- малые ядра 1.7 ГГц → P65 / G70 / G81; 1.8 ГГц → G80 / G85 / G88 / G91;
- GPU 820 / 950 / 1000 МГц → G70/G81, G80, G85/G88/G91;
- `ro.product.first_api_level` ≥ 34 + 1.7 ГГц → G81 / G81 Ultra; ≤ 30 → G70 / P65.
- Брать `cpuinfo_max_freq` (а не `scaling_max_freq`, его урезает power HAL).

## 4. Доступ обычного приложения (Android 13–15, SELinux AOSP)

Читается: `/sys/devices/system/cpu/**` (`scaling_cur_freq`, `cpuinfo_max/min_freq`,
`scaling_available_frequencies`, `policy*/`, `stats/time_in_state`, `cpuidle/state*/time`,
`online`, `possible`), `/proc/cpuinfo`, `/proc/meminfo`, `/proc/self/stat`, `/sys/class/gpu`.

Запрещено neverallow для всех untrusted apps (private/app_neverallows.te): `proc_stat`,
`proc_loadavg`, `proc_uptime`, `proc_version`, `proc_vmstat`, метка по умолчанию `sysfs:file`.

Температура: на MTK `/sys/devices/virtual/thermal` → `sysfs_therm`, приложениям обычно EACCES
(зависит от прошивки). Типы зон MTK: `mtktscpu`, `soc_max`, `cpu-little`, `cpu-big`,
`mtktsAP`, `mtktsbattery`. API: `PowerManager.getCurrentThermalStatus()` (API 29, 0–6),
`getThermalHeadroom(s)` (API 30, не чаще 1 раза/с), `getThermalHeadroomThresholds()` (API 35).
`HardwarePropertiesManager` — SecurityException.

Загрузка без `/proc/stat`: Δ(сумма `cpuidle/state*/time`, мкс) → загрузка = 1 − Δidle/Δwall;
запасной — `time_in_state`; грубо — `scaling_cur_freq / cpuinfo_max_freq`.

GPU: Kivy уже создал GLES-контекст → `kivy.graphics.opengl.glGetString(GL_RENDERER)`
(ожидается `Mali-G52 MC2`, vendor `ARM`); `ro.hardware.egl` (`mali`).

## 5. Коды ядер ARM (`arch/arm64/include/asm/cputype.h`)

Implementer 0x41 = ARM. 0xd03 A53, 0xd04 A35, 0xd05 A55, 0xd07 A57, 0xd08 A72, 0xd09 A73,
**0xd0a A75**, 0xd0b A76, 0xd0c Neoverse N1, 0xd0d A77, 0xd0e A76AE, 0xd40 V1, 0xd41 A78,
0xd42 A78AE, 0xd44 X1, 0xd46 A510, 0xd47 A710, 0xd48 X2, 0xd49 N2, 0xd4b A78C, 0xd4c X1C,
0xd4d A715, 0xd4e X3, 0xd4f V2, 0xd80 A520, 0xd81 A720, 0xd82 X4, 0xd84 V3, 0xd85 X925,
0xd87 A725, 0xd8a C1-Nano, 0xd8b C1-Pro, 0xd8c C1-Ultra, 0xd8e N3, 0xd90 C1-Premium.
На X6c ожидается 6 × 0xd05 + 2 × 0xd0a.

## Алгоритм

1. Свойства: `ro.config.cpu_info_display`, `ro.vendor.soc.model.external_name`, `…part_name`,
   `ro.soc.model`, `ro.soc.manufacturer`, `ro.vendor.mediatek.platform`, `ro.board.platform`,
   `ro.hardware`, `ro.product.board`, `ro.product.model`, `ro.product.first_api_level`.
2. `cpu_info_display` содержит Helio/Dimensity/Snapdragon → показать его.
3. Словарь «модель устройства → SoC» (NIC-LX*/NICL → G81 Ultra, lake → G81 Ultra, JDY-LX* → G85).
4. Нормализовать номер, искать по полному суффиксу, затем по префиксу.
5. Голый MT6768/6769 → различать по частотам; не выдавать уверенное имя без признаков.
6. Кластеры — по `related_cpus` + part из `/proc/cpuinfo`.
7. Загрузка — cpuidle → time_in_state → частота.
8. Температура — thermal_zone (EACCES-safe) → headroom/status → батарея.
9. GPU — glGetString из Kivy, затем `ro.hardware.egl`.

## Источники

- https://m.gsmarena.com/honor_x6c-13938.php
- https://deviceatlas.com/device-data/devices/honor/jdy-lx3/80731536
- https://browser.geekbench.com/v6/cpu/12924746
- https://github.com/Kamrulhasan12345/LITTLETai
- https://www.mi.com/uk/support/faq/details/KA-426105
- https://github.com/getActivity/AndroidSystemPropertyCollect
- https://github.com/Saikrishna1504/hardware_mediatek/blob/HEAD/chipinfo/chipinfo.sh
- https://en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips
- https://github.com/rianprei/poco-c75-lake-kernel/blob/HEAD/docs/research/ANTIGRAVITY3_recovery_sweep.md
- https://github.com/pytorch/cpuinfo/blob/main/src/arm/linux/chipset.c
- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
- https://github.com/LineageOS/android_device_mediatek_sepolicy
- https://developer.android.com/reference/android/os/HardwarePropertiesManager
- https://developer.android.com/reference/android/os/PowerManager
