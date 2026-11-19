extends Control
## Корневой узел: тема, переключение экранов, всплывающие сообщения.

const GameScreen := preload("res://scripts/ui/game_screen.gd")

const C_BG := Color("0b3d24")
const C_ACCENT := Color("e9c46a")
const C_BTN := Color("1f6f4a")

var screen: Control
var screen_name := ""
var _toast_box: VBoxContainer
var _font_bold: Font


func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_font_bold = load("res://fonts/DejaVuSans-Bold.ttf")
	theme = make_theme()
	var bg := TextureRect.new()
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	bg.stretch_mode = TextureRect.STRETCH_SCALE
	bg.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	var grad := Gradient.new()
	grad.set_color(0, Color("1a7a4c"))
	grad.set_color(1, Color("083821"))
	var gt := GradientTexture2D.new()
	gt.gradient = grad
	gt.fill = GradientTexture2D.FILL_RADIAL
	gt.fill_from = Vector2(0.5, 0.45)
	gt.fill_to = Vector2(1.1, 1.1)
	bg.texture = gt
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	var layer := CanvasLayer.new()
	layer.layer = 10
	add_child(layer)
	_toast_box = VBoxContainer.new()
	_toast_box.set_anchors_preset(Control.PRESET_CENTER_TOP)
	_toast_box.position = Vector2(-300, 70)
	_toast_box.size = Vector2(600, 0)
	_toast_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(_toast_box)
	get_viewport().size_changed.connect(_on_resize)
	_on_resize()
	Session.game_started.connect(func(): if screen_name != "game": show_game())
	Session.left.connect(func(reason): show_menu(); toast(reason, 4.0))
	Session.notice.connect(func(t): toast(t))
	show_menu()
	if OS.get_cmdline_user_args().has("--shots"):
		var shots: Node = load("res://tests/shots.gd").new()
		shots.main = self
		add_child(shots)
		shots.run()
	if OS.get_cmdline_user_args().has("--net-test"):
		var nt: Node = load("res://tests/net_test.gd").new()
		add_child(nt)
		nt.run()


func _on_resize() -> void:
	var w := get_viewport_rect().size.x
	_toast_box.position = Vector2(w / 2 - 300, 70)


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST:
		_go_back()


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventKey and event.pressed and event.keycode == KEY_ESCAPE:
		_go_back()


func _go_back() -> void:
	match screen_name:
		"menu":
			get_tree().quit()
		"game":
			screen.ask_leave()
		_:
			Session.reset()
			show_menu()


# ================================================================ тема

func make_theme() -> Theme:
	var t := Theme.new()
	t.default_font = load("res://fonts/DejaVuSans.ttf")
	t.default_font_size = 24
	var mk := func(col: Color, border := Color(0, 0, 0, 0)) -> StyleBoxFlat:
		var sb := StyleBoxFlat.new()
		sb.bg_color = col
		sb.set_corner_radius_all(14)
		sb.content_margin_left = 20
		sb.content_margin_right = 20
		sb.content_margin_top = 12
		sb.content_margin_bottom = 12
		sb.anti_aliasing = true
		if border.a > 0:
			sb.border_color = border
			sb.set_border_width_all(2)
		sb.shadow_color = Color(0, 0, 0, 0.25)
		sb.shadow_size = 3
		sb.shadow_offset = Vector2(0, 2)
		return sb
	t.set_stylebox("normal", "Button", mk.call(C_BTN, Color(1, 1, 1, 0.15)))
	t.set_stylebox("hover", "Button", mk.call(C_BTN.lightened(0.12), Color(1, 1, 1, 0.3)))
	t.set_stylebox("pressed", "Button", mk.call(C_ACCENT, Color(1, 1, 1, 0.5)))
	t.set_stylebox("hover_pressed", "Button", mk.call(C_ACCENT.lightened(0.1), Color(1, 1, 1, 0.5)))
	t.set_stylebox("disabled", "Button", mk.call(Color(0.25, 0.3, 0.27, 0.6)))
	t.set_stylebox("focus", "Button", StyleBoxEmpty.new())
	t.set_color("font_color", "Button", Color.WHITE)
	t.set_color("font_hover_color", "Button", Color.WHITE)
	t.set_color("font_pressed_color", "Button", Color("1b1b1f"))
	t.set_color("font_hover_pressed_color", "Button", Color("1b1b1f"))
	t.set_color("font_focus_color", "Button", Color.WHITE)
	t.set_color("font_disabled_color", "Button", Color(1, 1, 1, 0.35))
	var le: StyleBoxFlat = mk.call(Color(0, 0, 0, 0.35), Color(1, 1, 1, 0.25))
	t.set_stylebox("normal", "LineEdit", le)
	t.set_stylebox("focus", "LineEdit", mk.call(Color(0, 0, 0, 0.45), C_ACCENT))
	t.set_color("font_color", "LineEdit", Color.WHITE)
	var psb: StyleBoxFlat = mk.call(Color(0, 0, 0, 0.32), Color(1, 1, 1, 0.08))
	psb.content_margin_left = 24
	psb.content_margin_right = 24
	psb.content_margin_top = 20
	psb.content_margin_bottom = 20
	psb.set_corner_radius_all(20)
	t.set_stylebox("panel", "PanelContainer", psb)
	t.set_color("font_color", "Label", Color("f1f5ef"))
	t.set_constant("separation", "VBoxContainer", 12)
	t.set_constant("separation", "HBoxContainer", 10)
	t.set_constant("scroll_vertical_deadzone", "ScrollContainer", 20)
	return t


# ================================================================ помощники

func _set_screen(node: Control, sname: String) -> void:
	if screen:
		screen.queue_free()
	screen = node
	screen_name = sname
	add_child(screen)
	move_child(screen, 1)


func _page(title_text: String, action: Button = null) -> Array:
	var root := MarginContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		root.add_theme_constant_override("margin_" + side, 28)
	var outer := VBoxContainer.new()
	root.add_child(outer)
	var head := HBoxContainer.new()
	outer.add_child(head)
	if title_text != "":
		var back := Button.new()
		back.text = "← Назад"
		back.pressed.connect(_go_back)
		head.add_child(back)
		var t := label(title_text, 38, C_ACCENT, true)
		t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		head.add_child(t)
		if action:
			head.add_child(action)
		else:
			var spacer := Control.new()
			spacer.custom_minimum_size = Vector2(back.get_minimum_size().x, 0)
			head.add_child(spacer)
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	outer.add_child(scroll)
	var body := VBoxContainer.new()
	body.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(body)
	return [root, body]


func label(text: String, fsize := 24, col := Color("f1f5ef"), bold := false) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", fsize)
	l.add_theme_color_override("font_color", col)
	if bold:
		l.add_theme_font_override("font", _font_bold)
	return l


func button(text: String, cb: Callable, min_w := 0.0) -> Button:
	var b := Button.new()
	b.text = text
	b.pressed.connect(cb)
	b.custom_minimum_size = Vector2(min_w, 64)
	return b


func panel(child: Control) -> PanelContainer:
	var p := PanelContainer.new()
	p.add_child(child)
	return p


func segmented(options: Array, current: int, on_pick: Callable) -> HFlowContainer:
	var box := HFlowContainer.new()
	box.add_theme_constant_override("h_separation", 8)
	box.add_theme_constant_override("v_separation", 8)
	var group := ButtonGroup.new()
	for i in options.size():
		var b := Button.new()
		b.text = str(options[i])
		b.toggle_mode = true
		b.button_group = group
		b.button_pressed = i == current
		b.custom_minimum_size = Vector2(70, 58)
		b.pressed.connect(func(): on_pick.call(i))
		box.add_child(b)
	return box


func toast(text: String, secs := 2.2) -> void:
	var p := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0, 0, 0, 0.72)
	sb.set_corner_radius_all(14)
	sb.content_margin_left = 18
	sb.content_margin_right = 18
	sb.content_margin_top = 8
	sb.content_margin_bottom = 8
	p.add_theme_stylebox_override("panel", sb)
	var l := label(text, 24)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	p.add_child(l)
	p.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_toast_box.add_child(p)
	while _toast_box.get_child_count() > 3:
		_toast_box.get_child(0).free()
	var tw := p.create_tween()
	tw.tween_interval(secs)
	tw.tween_property(p, "modulate:a", 0.0, 0.4)
	tw.tween_callback(p.queue_free)


# ================================================================ меню

func show_menu() -> void:
	Net.stop_discovery()
	var root := CenterContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var col := VBoxContainer.new()
	col.custom_minimum_size = Vector2(520, 0)
	col.add_theme_constant_override("separation", 14)
	root.add_child(col)
	var title := label("ДУРАК", 92, C_ACCENT, true)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	col.add_child(title)
	var sub := label("♠ ♥ ♣ ♦  карточная игра", 24, Color(1, 1, 1, 0.7))
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	col.add_child(sub)
	var name_row := HBoxContainer.new()
	name_row.add_child(label("Имя:", 26))
	var name_edit := LineEdit.new()
	name_edit.text = str(Session.settings["name"])
	name_edit.max_length = 16
	name_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	name_edit.text_changed.connect(func(t):
		Session.settings["name"] = t.strip_edges() if t.strip_edges() != "" else "Игрок"
		Session.save_settings())
	name_row.add_child(name_edit)
	col.add_child(name_row)
	col.add_child(button("Играть с ботами", show_bot_setup))
	col.add_child(button("Игра по Wi-Fi", show_lan))
	col.add_child(button("Игра по Bluetooth", show_bt))
	col.add_child(button("Правила", show_rules))
	_set_screen(root, "menu")


func _settings_panel(with_zero_bots: bool) -> VBoxContainer:
	var s: Dictionary = Session.settings
	var box := VBoxContainer.new()
	box.add_child(label("Режим", 22, Color(1, 1, 1, 0.75)))
	box.add_child(segmented(DurakGame.MODE_NAMES, s["mode"], func(i):
		s["mode"] = i
		Session.save_settings()))
	box.add_child(label("Карт в колоде", 22, Color(1, 1, 1, 0.75)))
	var decks := [24, 36, 52]
	var bots_holder := VBoxContainer.new()
	var rebuild_bots := func():
		for ch in bots_holder.get_children():
			ch.queue_free()
		var maxb: int = Session.max_players(s["deck"]) - 1
		var minb := 0 if with_zero_bots else 1
		s["bots"] = clampi(s["bots"], minb, maxb)
		var opts := []
		for i in range(minb, maxb + 1):
			opts.append(i)
		bots_holder.add_child(segmented(opts, s["bots"] - minb, func(i):
			s["bots"] = i + minb
			Session.save_settings()))
	box.add_child(segmented(["24", "36", "52"], decks.find(s["deck"]), func(i):
		s["deck"] = decks[i]
		Session.save_settings()
		rebuild_bots.call()))
	box.add_child(label("Ботов" + (" (добавятся к людям)" if with_zero_bots else ""), 22, Color(1, 1, 1, 0.75)))
	box.add_child(bots_holder)
	rebuild_bots.call()
	box.add_child(label("Сложность ботов", 22, Color(1, 1, 1, 0.75)))
	box.add_child(segmented(DurakBot.LEVEL_NAMES, s["level"], func(i):
		s["level"] = i
		Session.save_settings()))
	return box


func show_bot_setup() -> void:
	var start := button("Начать ▶", func(): Session.start_local())
	start.add_theme_font_override("font", _font_bold)
	var pg := _page("Игра с ботами", start)
	var body: VBoxContainer = pg[1]
	var center := CenterContainer.new()
	center.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var col := VBoxContainer.new()
	col.custom_minimum_size = Vector2(min(760, get_viewport_rect().size.x - 80), 0)
	col.add_child(panel(_settings_panel(false)))
	col.add_child(button("Начать игру", func(): Session.start_local(), 0))
	center.add_child(col)
	body.add_child(center)
	_set_screen(pg[0], "bots")


func _two_columns(body: VBoxContainer) -> Array:
	var wide := get_viewport_rect().size.x > 1100
	var row: Container = HBoxContainer.new() if wide else VBoxContainer.new()
	row.add_theme_constant_override("separation", 20)
	body.add_child(row)
	var a := VBoxContainer.new()
	var b := VBoxContainer.new()
	a.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(a)
	row.add_child(b)
	return [a, b]


func show_lan() -> void:
	var pg := _page("Игра по Wi-Fi")
	var body: VBoxContainer = pg[1]
	var hint := label("Все телефоны должны быть подключены к одной Wi-Fi сети (или к точке доступа, которую раздаёт один из телефонов).", 20, Color(1, 1, 1, 0.75))
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.add_child(hint)
	var cols := _two_columns(body)
	var left: VBoxContainer = cols[0]
	var right: VBoxContainer = cols[1]
	left.add_child(label("Создать игру", 28, C_ACCENT, true))
	left.add_child(panel(_settings_panel(true)))
	left.add_child(button("Создать игру", func():
		var err := Session.host(Net.Transport.LAN)
		if err != "":
			toast(err, 4.0)
		else:
			show_lobby()))
	right.add_child(label("Присоединиться", 28, C_ACCENT, true))
	var list := VBoxContainer.new()
	var refresh := func():
		for ch in list.get_children():
			ch.queue_free()
		if Net.hosts.is_empty():
			list.add_child(label("Ищем игры в сети…", 22, Color(1, 1, 1, 0.6)))
		for ip in Net.hosts:
			var info: Dictionary = Net.hosts[ip]["info"]
			var mode_i := int(info.get("mode", 0))
			var txt := "%s — %s, %s карт (%d/%d)" % [info.get("name", "?"), DurakGame.MODE_NAMES[clampi(mode_i, 0, 2)], info.get("deck", 36), info.get("players", 1), info.get("max", 6)]
			var b := button(txt, func(): _join_lan(ip))
			b.disabled = not info.get("open", true)
			list.add_child(b)
	right.add_child(panel(list))
	refresh.call()
	Net.hosts_changed.connect(refresh)
	list.tree_exiting.connect(func(): Net.hosts_changed.disconnect(refresh))
	var ip_row := HBoxContainer.new()
	var ip_edit := LineEdit.new()
	ip_edit.placeholder_text = "IP хоста, например 192.168.1.5"
	ip_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	ip_edit.virtual_keyboard_type = LineEdit.KEYBOARD_TYPE_NUMBER_DECIMAL
	ip_row.add_child(ip_edit)
	ip_row.add_child(button("Войти", func():
		if ip_edit.text.strip_edges().is_valid_ip_address():
			_join_lan(ip_edit.text.strip_edges())
		else:
			toast("Введите правильный IP-адрес")))
	right.add_child(ip_row)
	var my_ips := Net.local_ips()
	right.add_child(label("Ваш IP: " + (", ".join(my_ips) if not my_ips.is_empty() else "нет сети"), 20, Color(1, 1, 1, 0.6)))
	_set_screen(pg[0], "lan")
	Net.start_discovery()


func _join_lan(ip: String) -> void:
	Net.stop_discovery()
	Session.join_lan(ip)
	show_lobby()


func show_bt() -> void:
	var pg := _page("Игра по Bluetooth")
	var body: VBoxContainer = pg[1]
	if not Net.bt_supported():
		var l := label("Bluetooth-игра работает только в Android-версии на телефоне с Bluetooth.", 24)
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		body.add_child(l)
		_set_screen(pg[0], "bt")
		return
	var hint := label("Один телефон создаёт игру, остальные находят его в списке и подключаются. Если телефон не виден — сопрягите устройства в настройках Bluetooth.", 20, Color(1, 1, 1, 0.75))
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.add_child(hint)
	var cols := _two_columns(body)
	var left: VBoxContainer = cols[0]
	var right: VBoxContainer = cols[1]
	left.add_child(label("Создать игру", 28, C_ACCENT, true))
	left.add_child(panel(_settings_panel(true)))
	left.add_child(button("Создать игру", func():
		var err := Session.host(Net.Transport.BT)
		if err != "":
			toast(err, 4.0)
		else:
			show_lobby()))
	right.add_child(label("Присоединиться", 28, C_ACCENT, true))
	var list := VBoxContainer.new()
	var refresh := func():
		for ch in list.get_children():
			ch.queue_free()
		if Net.bt_devices.is_empty():
			list.add_child(label("Нажмите «Искать»", 22, Color(1, 1, 1, 0.6)))
		for addr in Net.bt_devices:
			list.add_child(button(str(Net.bt_devices[addr]), func():
				Session.join_bt(addr)
				show_lobby()))
	right.add_child(panel(list))
	refresh.call()
	Net.bt_devices_changed.connect(refresh)
	list.tree_exiting.connect(func(): Net.bt_devices_changed.disconnect(refresh))
	right.add_child(button("Искать устройства", func():
		var err := Net.bt_prepare()
		if err != "":
			toast(err, 3.0)
			return
		Net.bt_scan()
		toast("Поиск устройств…")))
	_set_screen(pg[0], "bt")


func show_lobby() -> void:
	var pg := _page("Лобби")
	var body: VBoxContainer = pg[1]
	var is_host := Session.role == Session.Role.HOST
	var info := label("", 22, Color(1, 1, 1, 0.8))
	info.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.add_child(info)
	var list := VBoxContainer.new()
	body.add_child(panel(list))
	var settings_l := label("", 22, Color(1, 1, 1, 0.7))
	settings_l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.add_child(settings_l)
	var refresh := func(_names = null):
		for ch in list.get_children():
			ch.queue_free()
		var names: Array = []
		var s: Dictionary = Session.settings
		if is_host:
			for p in Session.lobby:
				names.append(p["name"])
			if Net.transport == Net.Transport.LAN:
				var ips := Net.local_ips()
				info.text = "Игра создана. Остальные игроки найдут её в разделе «Игра по Wi-Fi».\nВаш IP: %s" % (", ".join(ips) if not ips.is_empty() else "нет сети")
			else:
				info.text = "Игра создана. Телефон виден по Bluetooth — остальные могут подключаться."
		else:
			names = Session.lobby_names
			s = Session.settings_from_host
			if names.is_empty():
				info.text = "Подключение…"
			else:
				info.text = "Вы в лобби. Ждём, пока хост начнёт игру."
		for i in names.size():
			list.add_child(label("%d. %s%s" % [i + 1, names[i], "  (хост)" if i == 0 else ""], 26))
		if not s.is_empty():
			settings_l.text = "%s · %s карт · ботов: %s · %s" % [
				DurakGame.MODE_NAMES[int(s.get("mode", 0))], s.get("deck", 36), s.get("bots", 0),
				DurakBot.LEVEL_NAMES[int(s.get("level", 1))]]
	Session.lobby_changed.connect(refresh)
	list.tree_exiting.connect(func(): Session.lobby_changed.disconnect(refresh))
	refresh.call()
	if is_host:
		body.add_child(button("Начать игру", func():
			if Session.lobby.size() + int(Session.settings["bots"]) < 2:
				toast("Добавлен 1 бот: нужно минимум 2 игрока")
			Session.start_hosted_game()))
	_set_screen(pg[0], "lobby")


func show_game() -> void:
	var g := GameScreen.new()
	g.main = self
	_set_screen(g, "game")


func show_rules() -> void:
	var pg := _page("Правила")
	var body: VBoxContainer = pg[1]
	var rt := RichTextLabel.new()
	rt.bbcode_enabled = true
	rt.fit_content = true
	rt.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	rt.add_theme_font_size_override("normal_font_size", 22)
	rt.add_theme_font_size_override("bold_font_size", 24)
	rt.add_theme_font_override("bold_font", _font_bold)
	rt.text = """[b]Цель[/b] — избавиться от всех карт. Кто остался с картами последним — дурак.

[b]Раздача.[/b] Каждому по 6 карт, нижняя карта колоды открывается и задаёт козырь. Первым ходит игрок с младшим козырем.

[b]Ход.[/b] Атакующий кладёт карту (или несколько карт одного достоинства). Защитник бьёт каждую карту старшей картой той же масти или козырем. Можно подкидывать карты того же достоинства, что уже лежат на столе. На столе не больше 6 карт (в первом раунде — 5) и не больше, чем карт у защитника.

[b]Бито / Беру.[/b] Если всё отбито и подкидывать нечего — карты уходят в отбой, защитник ходит следующим. Если защитник не может или не хочет отбиваться — он забирает все карты со стола и пропускает ход.

[b]Добор.[/b] После раунда все добирают до 6 карт: сначала атакующий, потом остальные, защитник — последним.

[b]Режимы[/b]
• [b]Подкидной[/b] — подкидывать могут все игроки, кроме защитника. Переводить нельзя.
• [b]Переводной[/b] — пока ни одна карта не отбита, защитник может перевести атаку на следующего игрока, положив карту того же достоинства. Подкидывает только тот, кто сделал последний ход (атаковал или перевёл).
• [b]Переводной-подкидной[/b] — можно и переводить, и подкидывать всем игрокам.

[b]Управление.[/b] Нажмите на карту, чтобы сходить или подкинуть. При защите: если карту можно положить только на одну карту стола — она ляжет сама; иначе выберите карту в руке, а потом карту на столе. Для перевода выберите карту и нажмите «Перевести».

[b]Сложность ботов[/b]
• Лёгкий — играет почти наугад.
• Средний — разумная стратегия: ходит с младших карт, бережёт козыри.
• Сложный — запоминает вышедшие и забранные карты, а в конце партии просчитывает сотни вариантов розыгрыша.

[b]Сетевая игра.[/b] Wi-Fi: все в одной сети (подойдёт и точка доступа с телефона). Bluetooth: хост создаёт игру, остальные подключаются к нему. Если игрок отключится, вместо него доиграет бот."""
	body.add_child(rt)
	_set_screen(pg[0], "rules")
