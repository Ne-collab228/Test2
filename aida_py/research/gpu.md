# Исследование: GPU на Honor X6c (Mali-G52 MC2)

Дата: 2026-11-21

Пометки достоверности: [S] подтверждено источником, [K] знание из памяти (в вебе не подтверждено, проверять на устройстве).

## 1. MTK-узлы GPU (mt6768/mt6769) [K]
Прямых публичных описаний узлов поиск не нашёл. Ниже типичная картина downstream-ядер MTK.

- `/proc/gpufreq/` (v1, ядра 4.14/4.19): `gpufreq_opp_dump` (строки `[00] freq = 823000, volt = 87500, ...`, freq в **кГц**, volt в 10 мкВ), `gpufreq_opp_freq`, `gpufreq_var_dump` (текущая freq/volt), `gpufreq_power_dump`, `gpufreq_state`.
- `/proc/gpufreqv2/` (5.10/6.6 GKI): `gpu_working_opp_table`, `stack_signed_opp_table`, `gpufreq_status`, `gpu_signed_opp_table`. Частоты в кГц.
- `/sys/kernel/ged/hal/`: `current_freqency` (опечатка в самом драйвере; кГц), `gpu_utilization` (проценты, формат вида `act idle ...`), `total_gpu_freq_level_count`, `custom_upbound_gpu_freq` (индекс OPP, не МГц), `custom_boost_gpu_freq`, `gpu_memory_usage`.
- `/sys/module/ged/parameters/*`: параметры (gpu_dvfs_enable, ged_log_buf и т.п.), полезной частоты там нет.
- `/sys/class/misc/mali0/device/*` (в доках MediaTek Genio упомянут `gpuinfo`; `clock` есть только если ядро собрано с поддержкой), `/sys/class/devfreq/*mali*`: на MTK-телефонах обычно отсутствует: devfreq у MTK не используется, DVFS делает gpufreq/GED.
- Только корень или adb: `/sys/kernel/debug/ged/...` (debugfs).
- Подтверждено [S]: MTK sepolicy (wembley) метит `proc_ged` и `debugfs_ged`; `/proc/gpufreq*` в найденных политиках не встретился.

## 2. Доступность для untrusted_app (Android 13-15)
- [S] В референсной MTK-политике `non_plat/app.te` для `appdomain` разрешено: `proc_ged:file rw` и ioctl (для gralloc_extra), `gpu_device:dir search`, `proc_perfmgr:file {getattr open read ioctl}` (для HWUI/FPSGO). Правил на sysfs/gpufreq для приложений нет.
- [K] Вывод: `/sys/kernel/ged/hal/*` (метка sysfs_ged), `/proc/gpufreq*` (proc_gpufreq) для сторонних приложений чаще всего EACCES/ENOENT, но зависит от вендорской политики Honor. Проверять только попыткой open().
- [S] Тенденция ужесточения: Google ввёл `gpu_harden`, фильтрацию GPU-syscall в Android 16.
- DevCheck/CPU-Z на MTK без root [K]: частоту GPU обычно не показывают или показывают "N/A"; имя/вендор/версию GL берут из GLES (GL_RENDERER).

## 3. GL / Vulkan / драйвер
- Kivy: `glGetString(GL_EXTENSIONS)` после создания окна, split по пробелу, len = число расширений. GL_VERSION у Mali: `OpenGL ES 3.2 v1.r32p1-01eac0.<hash>`: `v<arch>.r<N>p<M>` это версия блобa драйвера (r32p1). GL_RENDERER: `Mali-G52 MC2` (иногда `Mali-G52`); GL_VENDOR: `ARM`. Число ядер из имени "MC2" (регулярка `MC(\d+)`).
- `ActivityManager.getDeviceConfigurationInfo().reqGlEsVersion`: кодирование `(major<<16)|minor`, `0x30002` = 3.2. `getGlEsVersion()` возвращает строку "3.2".
- PackageManager `getSystemAvailableFeatures()` (`FeatureInfo.name`, `.version`):
  - `android.hardware.vulkan.version`: [S] 0x401000 = Vulkan 1.1, 0x403000 = Vulkan 1.3. Кодирование VK_MAKE_VERSION: `major<<22 | minor<<12 | patch` [K, согласуется с [S]-значениями]. `hasSystemFeature(name, int)` проверяет "не ниже".
  - `android.hardware.vulkan.level` (0/1), `android.hardware.vulkan.compute` (уровень), `android.hardware.opengles.aep` (наличие). [S] описание: version = высший apiVersion, level/compute = уровень опциональных возможностей.
- Системные свойства (через `android.os.SystemProperties` по рефлексии или `getprop`): `ro.hardware.egl` (mali), `ro.hardware.vulkan` (mt6768/mali), `ro.gfx.driver.0` (обычно пусто), `ro.board.platform`, `ro.hardware`. Чтение ro.* доступно приложениям, если не закрыто политикой.

## 4. Mali-G52 MC2 [K + частично S]
- Bifrost (2-е поколение), 2 шейдерных ядра (MC2), по 3 исполнительных движка на ядро (итого 6 EE, 8-wide warp, 16 FP32 FMA/ядро/такт ~ 32 FLOPS/такт/ядро... расчёт Arm: до 3.6 ГФЛОПС... оценка при 820 МГц около 52 ГФЛОПС FP32 [K, приблизительно]; 8-bit dot-product вдвое быстрее).
- Макс. частота [S, notebookcheck/MediaTek]: Helio G81 820 МГц (по спецификации MediaTek); G81 Ultra по notebookcheck до 950 МГц (в спорах с Redmi 14C на практике наблюдают ~823 МГц, это уже известно); G80 950; G85 1000 МГц.
- API: OpenGL ES 3.2, Vulkan 1.1 (у MTK на Android может быть заявлен 1.1; 1.3 малоправдоподобно для G52), OpenCL 2.0 (Full Profile; на Android блоб недоступен приложениям без /vendor/lib/libOpenCL.so в списке публичных библиотек).

## 5. Температура и публичные API
- Зоны MTK [K]: `/sys/class/thermal/thermal_zone*/type`: `mtktsgpu` / `gpu1` / `gpu` (в новых GKI: `soc_max`, `gpu1`). Обычно недоступны (EACCES) для приложения; `/sys/devices/virtual/thermal/...` аналогично. `/sys/class/power_supply/battery/temp` читаем (не GPU).
- Публичного Android API частоты/загрузки GPU нет [S по отсутствию в результатах, K]. `HardwarePropertiesManager.getDeviceTemperatures(TEMPERATURE_GPU)` существует, но только для системных/VR-приложений. `PowerManager.getThermalHeadroom()` даёт общий запас, не GPU. `Debug.getMemoryInfo` к GPU отношения не имеет.

## Рекомендуемый алгоритм
Для каждой строки пробовать open() в try/except, при ошибке идти к следующему источнику.
1. Имя/вендор/версия GL/расширения: Kivy `glGetString` (RENDERER, VENDOR, VERSION, EXTENSIONS) -> ядра из `MC(\d+)`, драйвер из `v1.rXXpY` в VERSION, GLES версия из `OpenGL ES (\d+\.\d+)`.
2. GLES через `ActivityManager.getDeviceConfigurationInfo().reqGlEsVersion` (>>16 и &0xFFFF).
3. Vulkan: `PackageManager.getSystemAvailableFeatures()` -> `android.hardware.vulkan.version` (>>22, (>>12)&0x3FF), `.level`, `.compute`, `android.hardware.opengles.aep`; плюс свойства `ro.hardware.vulkan`, `ro.hardware.egl`.
4. Текущая частота: `/sys/kernel/ged/hal/current_freqency` (кГц) -> `/proc/gpufreqv2/gpufreqv2_var_dump` или `gpufreq_status` -> `/proc/gpufreq/gpufreq_var_dump` -> `/sys/class/misc/mali0/device/clock` -> `/sys/class/devfreq/*mali*/cur_freq` (Гц).
5. Таблица OPP/min/max: `/proc/gpufreqv2/gpu_working_opp_table` -> `/proc/gpufreq/gpufreq_opp_dump` -> `devfreq/*/available_frequencies`; min/max = крайние значения; `total_gpu_freq_level_count` для числа ступеней.
6. Загрузка: `/sys/kernel/ged/hal/gpu_utilization`, затем `/sys/class/devfreq/*mali*/load`. Иначе недоступно.
7. Температура: перебрать `/sys/class/thermal/thermal_zone*/type` по маске `gpu|mtktsgpu`, читать `temp` (делить на 1000, если >200).
8. Если недоступно: показывать статическую справку по SoC: "GPU: Mali-G52 MC2, 2 ядра, макс. 820 МГц (спец. G81; G81 Ultra до 950), GLES 3.2, Vulkan 1.1" с пометкой "частота/загрузка/температура: нет доступа без root (SELinux)". Не выдумывать значения; для загрузки/частоты можно оценивать FPS/frame-time косвенно (необязательно).

## Источники
- https://source.android.com/docs/core/graphics/implement-vulkan (0x401000 / 0x403000)
- https://developer.android.com/reference/android/content/pm/PackageManager (FEATURE_VULKAN_HARDWARE_*)
- https://android.googlesource.com/device/mediatek/wembley-sepolicy/+/refs/heads/android13-qpr2-s1-release/non_plat/app.te
- https://android.googlesource.com/device/mediatek/wembley-sepolicy/+/refs/heads/android13-qpr2-s1-release/non_plat/mtk_hal_gpu.te
- https://www.notebookcheck.net/Mediatek-Helio-G81-Ultra-Processor-Benchmarks-and-Specs.927302.0.html
- https://www.notebookcheck.net/Mediatek-Helio-G85-Processor-Benchmarks-and-Specs.494452.0.html
- https://www.mediatek.com/products/smartphones/mediatek-helio-g81
- https://www.mediatek.com/products/smartphones/mediatek-helio-g85
- https://genio.mediatek.com/doc/iot-yocto/latest/sw/yocto/app-dev/gpu/gpu-common.html (mali0/device/gpuinfo)
- https://security.googleblog.com/2025/12/further-hardening-android-gpus.html
- https://seyud.github.io/Mediatek_Mali_GPU_Governor/en/guide/introduction.html (упоминание GPUFreq v1/v2)
