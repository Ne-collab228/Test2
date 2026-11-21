# Исследование: данные батареи на Honor X6c (MTK Helio G81 Ultra, Android 15 / MagicOS 9)

Дата: 2026-11-21. Цель — понять, как обычное приложение (Pydroid 3 + pyjnius) может
правильно получить параметры батареи.

## 1. Единицы BatteryManager и отклонения производителей

По спецификации (developer.android.com, AOSP android15 `BatteryManager.java`):

| Свойство | id | Единица |
|---|---|---|
| `BATTERY_PROPERTY_CHARGE_COUNTER` | 1 | µA·ч |
| `CURRENT_NOW` | 2 | µA, «+» = заряд, «−» = разряд |
| `CURRENT_AVERAGE` | 3 | µA |
| `CAPACITY` | 4 | % |
| `ENERGY_COUNTER` | 5 | нВт·ч (только `getLongProperty`) |
| `STATUS` | 6 | API 26 |

Неподдерживаемое свойство: `Integer.MIN_VALUE` (targetSdk ≥ 28) или 0, `Long.MIN_VALUE` для long.
healthd (`BatteryMonitor.cpp`) передаёт значения из sysfs без пересчёта, поэтому единицы
зависят от драйвера производителя. `ENERGY_COUNTER` обычно не реализован.

**Honor / MagicOS — подтверждено.** Amply (d4rken-org/amply, `BatteryUnitCalibration.kt`,
issue #66, Honor Magic8 Pro): прошивка отдаёт `CURRENT_NOW` и `CHARGE_COUNTER` в **мА / мА·ч**
(в 1000 раз меньше спецификации). Пример: батарея 7100 мА·ч → `CHARGE_COUNTER` = 6978 при 100 %.
Amply определяет MagicOS через `hasSystemFeature("com.hihonor.software.features.honor")`
или `"com.hihonor.system.feature"`. Экстры `max_charging_*` у MagicOS = 0 или отсутствуют.

Стандартный драйвер MediaTek (`mtk_battery.c`): `current_now = ibat(0.1 мА) × 100` → µA, знак
стандартный. Honor мог это изменить, поэтому нужна эвристика.

Знак тока: у Samsung и Oplus встречается инвертированный (alibaba/MNN: Oplus — мА, «+» при разряде).

Эвристики из открытого кода:
- Scene/vtools `ElectricityUnit.kt`: |raw| > 20000 → µA, иначе мА; знак сверять со статусом.
- google-deepmind/android_env: 0 < |raw| < 10000 → мА (×1000); напряжение < 100 → вольты.
- Amply (Samsung): «учит» единицу по выборкам ~1 мин без зарядки и запоминает.

## 2. Паспортная ёмкость

- **PowerProfile**: `com.android.internal.os.PowerProfile(Context).getBatteryCapacity()` →
  мА·ч из `power_profile.xml` (`battery.capacity`). В `hiddenapi-flags.csv` Android 15 —
  `unsupported` (greylist): доступно при любом targetSdk, в т.ч. из pyjnius. 0 = нет данных.
- **API 34+**: `MANUFACTURING_DATE`(7), `FIRST_USAGE_DATE`(8), `CHARGING_POLICY`(9),
  `STATE_OF_HEALTH`(10), `SERIAL_NUMBER`(11), `PART_STATUS`(12) — `@SystemApi` с
  `BATTERY_STATS` (signature|privileged) → у обычного приложения **SecurityException**.
- **Циклы**: `"android.os.extra.CYCLE_COUNT"` (API 34, публичный) всегда кладётся в
  sticky‑intent; источник — `cycle_count` в sysfs. Заполняет ли Honor X6c — **не подтверждено**.
- **sysfs** `charge_full_design` / `charge_full` — приложению недоступны (п. 4).

## 3. Фактическая ёмкость и износ

- FCC ≈ CHARGE_COUNTER (мА·ч) × 100 / уровень; износ = 1 − FCC / паспортная.
- На стандартном MTK `charge_counter = ui_soc × q_max(профиль) / 100` — всегда константа
  профиля, износ не виден. На Honor, похоже, связан с «умной ёмкостью» (запас ~278 мА·ч).
- Уровень целочисленный (±0.5 %): считать только при уровне ≥ 80 %, брать медиану замеров.

## 4. Доступ к /sys/class/power_supply (Android 13–15)

AOSP sepolicy `domain.te`: `neverallow coredomain … sysfs_batteryinfo:file { open read }`,
исключение только для shell. На сертифицированной прошивке `battery/*`, `mtk-gauge` и т.п.
из Pydroid дают **EACCES**. Оставить как запасной вариант. aBattery читает их только через Shizuku.

## 5. Зарядка

- `EXTRA_PLUGGED`: 1 AC, 2 USB, 4 беспроводная, 8 док (API 33), 0 — от батареи.
- `"max_charging_current"` (µA) и `"max_charging_voltage"` (µV) — в Android 15 ещё есть в
  intent, но это лимиты зарядника (без `voltage_max` → 5 В), а не 35 Вт; у MagicOS обычно 0.
- `"android.os.extra.CHARGING_STATUS"` (API 34): 0 INVALID, 1 NORMAL, 2 TOO_COLD, 3 TOO_HOT,
  4 LONG_LIFE, 5 ADAPTIVE.
- `"battery_low"` (boolean), скрытый `"charge_counter"` в intent.
- `computeChargeTimeRemaining()` (API 28): мс или −1. `isCharging()` (API 23).
- Мощность: P[Вт] = |I[µA]| × V[мВ] / 1e9.

## 6. Температура и напряжение

`EXTRA_TEMPERATURE` — десятые °C, `EXTRA_VOLTAGE` — мВ. Особенностей Honor не найдено.
Проверки: V < 100 → вольты; V > 100000 → µВ; T вне (−1000; 1000) — мусор.

## Алгоритм (pyjnius)

1. Sticky‑intent `ACTION_BATTERY_CHANGED`: level, scale, status, plugged, voltage, temperature,
   health, technology, battery_low, CYCLE_COUNT, CHARGING_STATUS, max_charging_*, charge_counter.
2. `BatteryManager.getIntProperty(1,2,3,4,6)`, `getLongProperty(5)`; MIN_VALUE → нет данных.
3. MagicOS: `hasSystemFeature("com.hihonor.system.feature" | "com.hihonor.software.features.honor")`
   или `Build.MANUFACTURER == HONOR`.
4. CHARGE_COUNTER: если `cc*100/level < 500000` → это мА·ч.
5. Ток: малые значения при мА·ч‑счётчике/MagicOS → мА; знак сверять со статусом и кэшировать.
6. Паспортная ёмкость: PowerProfile → `getAveragePower("battery.capacity")` → ручной ввод.
7. FCC/износ — при уровне ≥ 80 %, медиана; если cc ≈ паспорт × уровень — «оценка недостоверна».
8. SOH / даты (7, 8, 10) — в try, SecurityException → «недоступно».
9. sysfs — последним, в try.

**Проверить на устройстве:** CYCLE_COUNT, знак тока, значение `battery.capacity` — вывести
сырые значения на экран диагностики.

## Источники

- https://developer.android.com/reference/android/os/BatteryManager
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/os/BatteryManager.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/BatteryService.java
- https://android.googlesource.com/platform/system/core/+/refs/heads/android15-release/healthd/BatteryMonitor.cpp
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/com/android/internal/os/PowerProfile.java
- https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
- https://dl.google.com/developers/android/vic/non-sdk/hiddenapi-flags.csv
- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/android15-release/private/domain.te
- https://github.com/d4rken-org/amply/blob/main/app/src/main/java/eu/darken/amply/battery/core/BatteryUnitCalibration.kt
- https://github.com/d4rken-org/amply/issues/66
- https://github.com/helloklf/vtools/blob/master/app/src/main/java/com/omarea/utils/ElectricityUnit.kt
- https://github.com/google-deepmind/android_env/blob/main/android_env/apps/java/com/google/androidenv/core/BatteryPowerCollector.kt
- https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/benchmark/BatteryEnergyMonitor.kt
- https://github.com/NothingOSS/android_kernel_5.15_nothing_mt6886/blob/HEAD/drivers/power/supply/mtk_battery.c
- https://blog.linshen.me/posts/abattery-faq/
- https://consumer.huawei.com/sg/support/content/en-gb00698716
