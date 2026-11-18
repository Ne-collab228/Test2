# -*- coding: utf-8 -*-
"""
views.py — вкладки интерфейса в стиле AIDA64 (Kivy).

Как добавить новую вкладку
--------------------------
1. Создайте класс-наследник BaseTab и пометьте его декоратором @register_tab:

    @register_tab(order=50)
    class NetworkTab(BaseTab):
        title = "Сеть"

        def build(self):
            self.section("Сеть")
            self.row("Имя хоста", self.data.system.get("hostname"))

2. Всё. main.py сам создаст кнопку и экран для каждой зарегистрированной вкладки.
   Данные берутся из self.data (utils.DeviceData): cpu, battery, system,
   history, monitor и словарь extra для ваших собственных значений.
"""

from kivy.graphics import Color, Line, Rectangle
from kivy.metrics import dp, sp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from kivy.utils import get_color_from_hex as hexc

import time

import utils

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
                         "%s – %s" % (utils.fmt_freq_khz(cl.get("min")) or "?",
                                      utils.fmt_freq_khz(cl.get("max")) or "?")])
        self.table(["Тип", "Ядра", "Частота"], rows, [0.42, 0.2, 0.38])

        self.section("Частоты ядер")
        usage = c.get("usage_per_core") or []
        for core in c.get("cores", []):
            if not core["online"]:
                self.row("Ядро %d" % core["id"], "Отключено", color=THEME["muted"])
                continue
            cur, mx = core.get("cur"), core.get("max")
            pct = 100.0 * cur / mx if cur and mx else 0
            text = utils.fmt_freq_khz(cur) or NA
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
        return [("Замер сейчас", self._sample), ("Очистить историю", self._clear)]

    def _sample(self, *_):
        self.data.history.add(utils.get_battery_info(), force=True)
        self.refresh()

    def _clear(self, *_):
        self.data.history.clear()
        self.data.monitor.add(utils.INFO, "Батарея", "История батареи очищена")
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
        if b.get("current") is not None:
            self.row("Ток", "%.0f мА" % b["current"])
        if b.get("power") is not None:
            self.row("Мощность", "%.2f Вт" % abs(b["power"]))
        if b.get("charge_counter"):
            self.row("Текущий заряд", "%.0f мА·ч" % b["charge_counter"])
        if b.get("capacity_design"):
            self.row("Проектная ёмкость", "%.0f мА·ч" % b["capacity_design"])
        if b.get("capacity_full"):
            self.row("Фактическая ёмкость", "%.0f мА·ч" % b["capacity_full"])
        if b.get("wear") is not None:
            self.row("Износ", "%.1f %%" % b["wear"], color=level_color(b["wear"], warn=20, crit=40))
        if b.get("cycles") is not None:
            self.row("Циклы зарядки", b["cycles"])
        self.row("Источник данных", b.get("source"))

        self.section("Время работы")
        self.row("С момента включения", utils.fmt_duration(self.data.system.get("uptime")))
        kind, secs = h.estimate(b)
        if kind == "discharge":
            self.row("Осталось работы (оценка)", utils.fmt_duration(secs), color=THEME["ok"])
        elif kind == "charge":
            self.row("До полной зарядки (оценка)", utils.fmt_duration(secs), color=THEME["info"])
        else:
            self.row("Оценка времени", "Сбор данных…", color=THEME["muted"])
        dr, cr = h.discharge_rate(), h.charge_rate()
        self.row("Скорость разряда", "%.1f %%/ч" % dr if dr else None)
        self.row("Скорость зарядки", "%.1f %%/ч" % cr if cr else None)
        if b["status"] not in ("charging", "full"):
            last_charge = next((s for s in h.all_sessions() if s.get("end")), None)
            if last_charge:
                self.row("С последней зарядки", utils.fmt_duration(time.time() - last_charge["end"]))

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
                      "приложение открыто." % (len(pts), utils.fmt_time(pts[0][0], True),
                                               utils.fmt_time(pts[-1][0], True)))

        self.section("История зарядок")
        rows = []
        for s in h.all_sessions()[:20]:
            end = s.get("end")
            rows.append([utils.fmt_time(s["start"], True),
                         "%.0f → %s" % (s["from"], "%.0f %%" % s["to"] if end else "…"),
                         utils.fmt_duration((end or time.time()) - s["start"])])
        if rows:
            self.table(["Начало", "Заряд", "Длительность"], rows, [0.33, 0.32, 0.35])
        else:
            self.note("Зарядок пока не зафиксировано. Подключите зарядное устройство, "
                      "пока приложение открыто.")


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
        self.row("Время работы", utils.fmt_duration(s.get("uptime")))
        self.row("Часовой пояс", s.get("timezone"))
        self.row("Дата и время", s.get("time"))
        self.row("Python", s.get("python"))

        self.section("Оперативная память")
        m = s.get("memory") or {}
        if m.get("total"):
            pct = 100.0 * m["used"] / m["total"] if m.get("used") is not None else 0
            self.bar("Использовано", "%s / %s" % (utils.fmt_bytes(m["used"]),
                                                  utils.fmt_bytes(m["total"])), pct,
                     color=level_color(pct, warn=75, crit=90))
            self.row("Всего", utils.fmt_bytes(m["total"]))
            self.row("Доступно", utils.fmt_bytes(m.get("available")))
            if m.get("cached"):
                self.row("Кэш", utils.fmt_bytes(m["cached"]))
            if m.get("swap_total"):
                self.row("Swap / zRAM", "%s (свободно %s)" % (
                    utils.fmt_bytes(m["swap_total"]), utils.fmt_bytes(m.get("swap_free"))))
        else:
            self.row("ОЗУ", None)

        self.section("Хранилище")
        for st in s.get("storage", []):
            pct = 100.0 * st["used"] / st["total"]
            self.bar(st["label"], "своб. %s из %s" % (utils.fmt_bytes(st["free"]),
                                                        utils.fmt_bytes(st["total"])),
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

LEVEL_STYLE = {utils.INFO: ("ИНФО", "info"), utils.WARNING: ("ВНИМАНИЕ", "warn"),
               utils.CRITICAL: ("КРИТИЧНО", "crit")}


class MessageItem(BoxLayout):
    def __init__(self, msg, odd=False, **kw):
        super(MessageItem, self).__init__(size_hint_y=None, **kw)
        paint(self, THEME["row2"] if odd else THEME["row1"])
        name, key = LEVEL_STYLE.get(msg["level"], (msg["level"], "info"))
        stripe = Widget(size_hint_x=None, width=dp(5))
        paint(stripe, THEME[key])
        self.add_widget(stripe)
        text = ("[color=%s][b]%s[/b][/color]  [color=#9aa5b1]%s · %s[/color]\n%s"
                % (_hex(THEME[key]), name, utils.fmt_time(msg["t"], True), msg["src"],
                   msg["text"]))
        self.lbl = WrapLabel(text=text, padding=(dp(10), dp(4)), min_height=dp(52))
        self.lbl.bind(height=lambda w, h: setattr(self, "height", h))
        self.add_widget(self.lbl)
        self.height = self.lbl.height


@register_tab(order=40)
class MessagesTab(BaseTab):
    title = "Сообщения"
    FILTERS = [("Все", None), ("Важные", (utils.WARNING, utils.CRITICAL)),
               ("Критичные", (utils.CRITICAL,))]

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
            self.note(utils.read_system_log().replace("&", "&amp;").replace("[", "&bl;").replace("]", "&br;"),
                      color=THEME["text"])
            return
        allowed = self.FILTERS[self._filter][1]
        msgs = [m for m in reversed(self.data.monitor.messages)
                if allowed is None or m["level"] in allowed]
        counts = {lvl: sum(1 for m in self.data.monitor.messages if m["level"] == lvl)
                  for lvl in LEVEL_STYLE}
        self.section("Сводка")
        self.row("Критичных", counts[utils.CRITICAL],
                 color=THEME["crit"] if counts[utils.CRITICAL] else THEME["ok"])
        self.row("Предупреждений", counts[utils.WARNING],
                 color=THEME["warn"] if counts[utils.WARNING] else THEME["ok"])
        self.row("Информационных", counts[utils.INFO])
        self.section("События (%d)" % len(msgs))
        if not msgs:
            self.note("Сообщений нет.")
        for i, m in enumerate(msgs[:150]):
            self.add(MessageItem(m, odd=bool(i % 2)))
