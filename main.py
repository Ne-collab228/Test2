# -*- coding: utf-8 -*-
"""
AIDA Py — информация об устройстве в стиле AIDA64 для Pydroid 3.

Запуск в Pydroid 3: положите main.py, views.py и utils.py в одну папку,
откройте main.py и нажмите ▶ (Kivy входит в Pydroid 3).
На ПК: pip install kivy  →  python main.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp, sp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.screenmanager import NoTransition, Screen, ScreenManager
from kivy.uix.scrollview import ScrollView

import utils
import views

REFRESH_SECONDS = 3  # период автообновления активной вкладки


class AidaApp(App):
    title = "AIDA Py"

    def build(self):
        Window.clearcolor = views.THEME["bg"]
        self.data = utils.DeviceData()
        self.data.update()

        root = BoxLayout(orientation="vertical")
        views.paint(root, views.THEME["bg"])
        root.add_widget(self._header())
        root.add_widget(self._tab_bar())

        self.sm = ScreenManager(transition=NoTransition())
        self.tabs = {}
        for cls in views.get_tabs():
            screen = Screen(name=cls.__name__)
            tab = cls(self.data)
            screen.add_widget(tab)
            self.sm.add_widget(screen)
            self.tabs[cls.__name__] = tab
        root.add_widget(self.sm)
        root.add_widget(self._status_bar())

        self.switch_tab(views.get_tabs()[0].__name__)
        Clock.schedule_interval(self.tick, REFRESH_SECONDS)
        return root

    # --- элементы интерфейса ---
    def _header(self):
        bar = BoxLayout(size_hint_y=None, height=dp(56), padding=(dp(12), dp(6)),
                        spacing=dp(8))
        views.paint(bar, views.THEME["header"])
        logo = Label(text="[b][color=#e53935]AIDA[/color] Py[/b]", markup=True,
                     font_size=sp(22), size_hint_x=None, width=dp(110))
        model = self.data.system.get("model") or "Устройство"
        sub = Label(text="%s\n[color=#9aa5b1]%s[/color]" % (model, self.data.system.get("os", "")),
                    markup=True, font_size=sp(13), halign="left", valign="middle")
        sub.bind(size=lambda w, s: setattr(w, "text_size", s))
        refresh = views.AidaButton(text="Обновить", size_hint_x=None, width=dp(96),
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
        views.paint(scroll, views.THEME["header"])
        self.tab_buttons = {}
        tabs = views.get_tabs()
        for cls in tabs:
            btn = views.AidaButton(text=cls.title, size_hint_x=None, height=dp(42),
                                   width=max(dp(84), (Window.width - dp(8)) / len(tabs) - dp(4)))
            btn.bind(on_release=lambda b, n=cls.__name__: self.switch_tab(n))
            row.add_widget(btn)
            self.tab_buttons[cls.__name__] = btn
        scroll.add_widget(row)
        return scroll

    def _status_bar(self):
        self.status = Label(size_hint_y=None, height=dp(26), font_size=sp(12),
                            color=views.THEME["muted"])
        views.paint(self.status, views.THEME["header"])
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
                   if m["level"] != utils.INFO)
        self.status.text = "Обновлено: %s   •   Предупреждений: %d   •   авто %d с" % (
            utils.fmt_time(self.data.updated or 0), warn, REFRESH_SECONDS)

    def on_pause(self):  # Android: разрешаем сворачивание
        return True

    def on_stop(self):
        self.data.history.save()
        self.data.monitor.save()


if __name__ == "__main__":
    AidaApp().run()
