extends Control
## Игровой стол. Рисуется по view из Session; карты — узлы с ключом «номер карты»,
## поэтому при каждом обновлении они плавно переезжают на новые места.

var main: Control

var view := {}
var cards := {}  # card -> CardView (рука и стол)
var selected := -1
var _game_id := -1
var _msg_total := 0

var card_layer: Control
var badges := {}  # seat -> Badge
var deck_back: CardView
var deck_back2: CardView
var trump_view: CardView
var deck_label: Label
var trump_suit_label: Label
var discard_stack: Array = []
var discard_label: Label
var status_label: Label
var info_label: Label
var btn_take: Button
var btn_pass: Button
var btn_transfer: Button
var overlay: Control
var confirm: Control
var _font_bold: Font


class Badge extends Control:
	var pname := ""
	var count := 0
	var status := ""
	var active := false
	var is_bot := false
	var out := false
	var loser := false
	var font: Font
	var bold: Font

	func _init() -> void:
		size = Vector2(190, 118)
		mouse_filter = Control.MOUSE_FILTER_IGNORE

	func _draw() -> void:
		# мини-веер рубашек
		var k := mini(count, 10)
		var cw := 30.0
		var ch := 44.0
		var cx := size.x / 2
		for i in k:
			var ang := (i - (k - 1) / 2.0) * 0.12
			draw_set_transform(Vector2(cx, 52), ang, Vector2.ONE)
			var r := Rect2(Vector2(-cw / 2, -ch), Vector2(cw, ch))
			var sb := StyleBoxFlat.new()
			sb.bg_color = Color("9d1b2f")
			sb.border_color = Color("f1f1f1")
			sb.set_border_width_all(2)
			sb.set_corner_radius_all(4)
			draw_style_box(sb, r)
		draw_set_transform(Vector2.ZERO, 0, Vector2.ONE)
		var plate := Rect2(Vector2(0, 50), Vector2(size.x, 66))
		var psb := StyleBoxFlat.new()
		psb.bg_color = Color(0, 0, 0, 0.55) if not active else Color("e9c46a").darkened(0.15)
		if loser:
			psb.bg_color = Color("9d1b2f")
		psb.set_corner_radius_all(12)
		draw_style_box(psb, plate)
		var tc := Color.WHITE if not active else Color("1b1b1f")
		var nm := pname + ("  ·  " + str(count) if count > 0 else "")
		draw_string(bold, Vector2(0, 78), nm, HORIZONTAL_ALIGNMENT_CENTER, size.x, 20, tc)
		draw_string(font, Vector2(0, 104), status, HORIZONTAL_ALIGNMENT_CENTER, size.x, 18, tc.lerp(Color.GRAY, 0.25))
		if out:
			draw_rect(Rect2(Vector2.ZERO, size), Color(0, 0, 0, 0.0))


func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_font_bold = load("res://fonts/DejaVuSans-Bold.ttf")
	# Колода и отбой
	trump_view = CardView.new(-1, true)
	trump_view.rotation = PI / 2
	add_child(trump_view)
	deck_back2 = CardView.new(-1, false)
	add_child(deck_back2)
	deck_back = CardView.new(-1, false)
	add_child(deck_back)
	deck_label = _label("", 22, true)
	add_child(deck_label)
	trump_suit_label = _label("", 64, true)
	add_child(trump_suit_label)
	for i in 3:
		var d := CardView.new(-1, false)
		d.rotation = [0.3, -0.25, 0.1][i]
		discard_stack.append(d)
		add_child(d)
	discard_label = _label("", 20)
	add_child(discard_label)
	card_layer = Control.new()
	card_layer.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	card_layer.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(card_layer)
	status_label = _label("", 26, true)
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.7))
	status_label.add_theme_constant_override("outline_size", 6)
	add_child(status_label)
	info_label = _label("", 20)
	info_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	info_label.modulate = Color(1, 1, 1, 0.8)
	add_child(info_label)
	var menu_btn := Button.new()
	menu_btn.text = "☰ Меню"
	menu_btn.position = Vector2(16, 14)
	menu_btn.pressed.connect(ask_leave)
	add_child(menu_btn)
	btn_take = _action_button("Беру", Color("c1121f"), func(): _act({"type": "take"}))
	btn_pass = _action_button("Бито", Color("2a9d8f"), func(): _act({"type": "pass"}))
	btn_transfer = _action_button("Перевести", Color("7b2cbf"), func():
		if selected >= 0:
			_act({"type": "transfer", "card": selected}))
	resized.connect(_relayout)
	Session.view_changed.connect(_on_view)
	if not Session.view.is_empty():
		_on_view.call_deferred(Session.view)
	else:
		status_label.text = "Ждём начала игры…"
	_relayout.call_deferred()


func _label(text: String, fsize: int, bold := false) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", fsize)
	if bold:
		l.add_theme_font_override("font", _font_bold)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l


func _action_button(text: String, col: Color, cb: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(200, 76)
	b.add_theme_font_size_override("font_size", 28)
	b.add_theme_font_override("font", _font_bold)
	for st in ["normal", "hover", "pressed", "hover_pressed"]:
		var sb := StyleBoxFlat.new()
		sb.bg_color = col if st == "normal" else col.lightened(0.15)
		sb.set_corner_radius_all(16)
		sb.border_color = Color(1, 1, 1, 0.5)
		sb.set_border_width_all(2)
		sb.shadow_color = Color(0, 0, 0, 0.35)
		sb.shadow_size = 5
		b.add_theme_stylebox_override(st, sb)
	b.add_theme_color_override("font_pressed_color", Color.WHITE)
	b.add_theme_color_override("font_hover_pressed_color", Color.WHITE)
	b.pressed.connect(cb)
	b.visible = false
	add_child(b)
	return b


# ================================================================ обновление

func _on_view(v: Dictionary) -> void:
	if v.is_empty():
		return
	var new_game: bool = int(v.get("game_id", 0)) != _game_id
	if new_game:
		_game_id = int(v.get("game_id", 0))
		for c in cards:
			cards[c].queue_free()
		cards.clear()
		for s in badges:
			badges[s].queue_free()
		badges.clear()
		selected = -1
		_msg_total = 0
		if overlay:
			overlay.queue_free()
			overlay = null
	var prev := view
	view = v
	if not (v["hand"] as Array).has(selected):
		selected = -1
	_ensure_badges()
	_relayout()
	_show_messages(prev)
	if v["game_over"] and overlay == null:
		get_tree().create_timer(1.2).timeout.connect(_show_game_over)


func _ensure_badges() -> void:
	var n: int = view["n"]
	var me: int = view["me"]
	for i in range(1, n):
		var s := (me + i) % n
		if not badges.has(s):
			var b := Badge.new()
			b.font = get_theme_default_font()
			b.bold = _font_bold
			badges[s] = b
			add_child(b)
			move_child(b, card_layer.get_index())


func _relayout() -> void:
	if view.is_empty():
		return
	if OS.is_debug_build() and OS.get_cmdline_user_args().has("--debug-layout"):
		print("relayout size=", size, " anchors=", anchor_right, " ", anchor_bottom, " parent=", get_parent().size)
	var W := size.x
	var H := size.y
	var n: int = view["n"]
	var me: int = view["me"]
	var cw := CardView.W
	var chh := CardView.H

	# --- соперники
	var opp := []
	for i in range(1, n):
		opp.append((me + i) % n)
	var left_x := 230.0
	var right_x := W - 230.0
	for k in opp.size():
		var s: int = opp[k]
		var b: Badge = badges[s]
		var t := (k + 1.0) / (opp.size() + 1.0)
		var x := lerpf(left_x, right_x, t)
		var y := 8.0 + (absf(t - 0.5) * 2.0) * 40.0 * (1 if opp.size() > 2 else 0)
		b.position = Vector2(x - b.size.x / 2, y)
		_update_badge(b, s)

	# --- колода
	var deck_pos := Vector2(28, H * 0.40 - chh / 2)
	var deck_count: int = view["deck_count"]
	trump_view.card = view["trump_card"]
	trump_view.queue_redraw()
	trump_view.position = deck_pos + Vector2(chh * 0.45, 22)
	trump_view.visible = deck_count > 0
	deck_back.position = deck_pos
	deck_back2.position = deck_pos + Vector2(-3, 3)
	deck_back.visible = deck_count > 1
	deck_back2.visible = deck_count > 6
	deck_label.text = str(deck_count) if deck_count > 0 else ""
	deck_label.position = deck_pos + Vector2(cw / 2 - 14, chh + 4)
	trump_suit_label.text = Cards.SUIT_SYMBOLS[int(view["trump_suit"])]
	trump_suit_label.add_theme_color_override("font_color", CardView.RED.lightened(0.2) if int(view["trump_suit"]) >= 2 else Color(1, 1, 1, 0.85))
	trump_suit_label.position = deck_pos + Vector2(18, chh / 2 - 44)
	trump_suit_label.visible = deck_count == 0
	trump_suit_label.modulate = Color(1, 1, 1, 0.75)

	# --- отбой
	var dc: int = view["discard_count"]
	var disc_pos := Vector2(W - cw - 34, H * 0.40 - chh / 2)
	for i in discard_stack.size():
		discard_stack[i].position = disc_pos + Vector2(i * 3, -i * 2)
		discard_stack[i].visible = dc > i * 6
	discard_label.text = "Бито: %d" % dc if dc > 0 else ""
	discard_label.position = disc_pos + Vector2(6, chh + 8)

	# --- кнопки
	var legal: Dictionary = view["legal"]
	btn_take.visible = legal["take"]
	btn_pass.visible = legal["pass"]
	btn_pass.text = "Пас" if view["taking"] else "Бито"
	btn_transfer.visible = selected >= 0 and (legal["transfer"] as Array).has(selected)
	var by := H - 96.0
	for b in [btn_pass, btn_take, btn_transfer]:
		if b.visible:
			b.position = Vector2(W - b.custom_minimum_size.x - 20, by)
			by -= 90.0

	# --- карты
	_layout_cards(W, H)

	# --- статус
	status_label.text = _status_text()
	status_label.size = Vector2(W - 520, 40)
	status_label.position = Vector2(260, H - chh - 70)
	info_label.text = "%s · козырь %s" % [DurakGame.MODE_NAMES[int(view["mode"])], Cards.SUIT_SYMBOLS[int(view["trump_suit"])]]
	info_label.size = Vector2(400, 30)
	info_label.position = Vector2(W - 420, H * 0.40 + chh / 2 + 44)


func _update_badge(b: Badge, s: int) -> void:
	var names: Array = view["names"]
	var kinds: Array = view.get("kinds", [])
	b.pname = str(names[s])
	b.is_bot = kinds.size() > s and kinds[s] == "bot"
	b.count = int(view["counts"][s])
	b.out = (view["finished"] as Array).has(s)
	b.loser = view["game_over"] and int(view["loser"]) == s
	b.status = _seat_status(s)
	b.active = _is_waiting_for(s)
	b.modulate = Color(1, 1, 1, 0.55) if b.out else Color.WHITE
	b.queue_redraw()


func _seat_status(s: int) -> String:
	if view["game_over"]:
		if int(view["loser"]) == s:
			return "ДУРАК"
		var fin: Array = view["finished"]
		return "%d место" % (fin.find(s) + 1) if fin.has(s) else ""
	if (view["finished"] as Array).has(s):
		return "вышел"
	if s == int(view["defender"]):
		return "берёт" if view["taking"] else "отбивается"
	if (view["passed"] as Array).has(s):
		return "пас" if view["taking"] else "бито"
	if s == int(view["attacker"]):
		return "ходит"
	return ""


func _is_waiting_for(s: int) -> bool:
	if view["game_over"]:
		return false
	var table: Array = view["table"]
	if table.is_empty():
		return s == int(view["attacker"])
	var undefended := 0
	for pair in table:
		if int(pair[1]) == -1:
			undefended += 1
	if s == int(view["defender"]):
		return undefended > 0 and not view["taking"]
	if undefended > 0 and not view["taking"]:
		return false
	return (view["throwers"] as Array).has(s) and not (view["passed"] as Array).has(s)


func _layout_cards(W: float, H: float) -> void:
	var cw := CardView.W
	var chh := CardView.H
	var me: int = view["me"]
	var legal: Dictionary = view["legal"]
	var att: Array = legal["attack"]
	var def: Dictionary = legal["defend"]
	var tr: Array = legal["transfer"]
	var my_turn := not att.is_empty() or not def.is_empty() or not tr.is_empty()
	var targets := {}  # card -> [pos, rot, z]
	var la: Dictionary = view.get("last_action", {})

	# Стол
	var table: Array = view["table"]
	var k := table.size()
	var area_l := 190.0
	var area_r := W - 190.0
	var area_w := area_r - area_l
	var slot := cw + 46.0
	var per_row := k
	if k * slot > area_w:
		per_row = int(ceil(k / 2.0))
	var rows := int(ceil(float(k) / maxi(per_row, 1)))
	var top := 150.0
	var bottom := H - chh - 90.0
	var row_h := chh + 36.0
	var start_y := top + maxf(0.0, (bottom - top - rows * row_h) / 2.0)
	for i in k:
		var row := i / maxi(per_row, 1)
		var col := i % maxi(per_row, 1)
		var in_row := mini(per_row, k - row * per_row)
		var x := area_l + (area_w - in_row * slot) / 2.0 + col * slot + 10
		var y := start_y + row * row_h
		var a: int = table[i][0]
		var d: int = table[i][1]
		targets[a] = [Vector2(x, y), -0.06 + (i % 3) * 0.04, 10 + i * 2]
		if d != -1:
			targets[d] = [Vector2(x + 26, y + 30), 0.12 - (i % 2) * 0.05, 11 + i * 2]

	# Рука
	var hand: Array = view["hand"]
	var hn := hand.size()
	var hl := 150.0
	var hr := W - 250.0
	var avail := hr - hl
	var step := cw + 6.0
	if hn > 1:
		step = minf(step, (avail - cw) / (hn - 1))
	var total := cw + step * maxi(hn - 1, 0)
	var hx := hl + (avail - total) / 2.0
	for i in hn:
		var c: int = hand[i]
		var lift := 0.0
		var playable: bool = att.has(c) or def.has(c) or tr.has(c)
		if c == selected:
			lift = 40.0
		elif playable:
			lift = 14.0
		var t := (i - (hn - 1) / 2.0) / maxf(hn, 1)
		targets[c] = [Vector2(hx + i * step, H - chh - 12 - lift + absf(t) * 18), t * 0.18, 100 + i]

	# Удаляем/улетают исчезнувшие карты
	var lr: Dictionary = view.get("last_round", {})
	for c in cards.keys():
		if targets.has(c):
			continue
		var node: CardView = cards[c]
		cards.erase(c)
		var dest := Vector2(W - cw - 34, H * 0.40 - chh / 2)
		var taker := int(lr.get("taken_by", -1))
		if taker >= 0 and taker != me and badges.has(taker):
			dest = badges[taker].position + Vector2(80, 10)
		node.set_interactive(false)
		node.z_index = 300
		var tw := node.create_tween().set_parallel(true)
		tw.tween_property(node, "position", dest, 0.4).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_IN)
		tw.tween_property(node, "modulate:a", 0.0, 0.4).set_delay(0.15)
		tw.tween_property(node, "rotation", randf_range(-0.6, 0.6), 0.4)
		tw.chain().tween_callback(node.queue_free)

	# Создаём/двигаем
	var deck_pos := Vector2(28, H * 0.40 - chh / 2)
	for c in targets:
		var tg: Array = targets[c]
		var node: CardView
		if cards.has(c):
			node = cards[c]
		else:
			node = CardView.new(c, true)
			node.pressed.connect(_on_card_pressed)
			card_layer.add_child(node)
			cards[c] = node
			# откуда прилетает
			var from: Vector2 = deck_pos
			if hand.has(c):
				from = deck_pos
			elif int(la.get("card", -2)) == c and badges.has(int(la.get("seat", -1))):
				from = badges[int(la["seat"])].position + Vector2(80, 10)
			elif badges.size() > 0:
				from = Vector2(W / 2, -CardView.H)
			node.position = from
			node.rotation = 0.4
		node.is_trump = Cards.suit(c) == int(view["trump_suit"]) and hand.has(c)
		node.z_index = tg[2]
		var in_hand := hand.has(c)
		node.selected = in_hand and c == selected
		node.playable = in_hand and (att.has(c) or def.has(c) or tr.has(c))
		node.dimmed = in_hand and my_turn and not node.playable
		var is_target := false
		if not in_hand and selected >= 0 and def.has(selected):
			for i in table.size():
				if int(table[i][0]) == c and (def[selected] as Array).has(i):
					is_target = true
		node.target_hint = is_target
		node.set_interactive(in_hand or (not in_hand and _is_undefended_attack(c)))
		if node.position.distance_to(tg[0]) > 0.5 or absf(node.rotation - tg[1]) > 0.01:
			var tw := node.create_tween().set_parallel(true)
			tw.tween_property(node, "position", tg[0], 0.28).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
			tw.tween_property(node, "rotation", tg[1], 0.28)


func _is_undefended_attack(c: int) -> bool:
	for pair in view["table"]:
		if int(pair[0]) == c and int(pair[1]) == -1:
			return true
	return false


func _status_text() -> String:
	var me: int = view["me"]
	var names: Array = view["names"]
	if view["game_over"]:
		var l := int(view["loser"])
		if l < 0:
			return "Ничья!"
		return "Вы — дурак!" if l == me else "%s — дурак!" % names[l]
	if (view["finished"] as Array).has(me):
		return "Вы вышли из игры! Ждём остальных…"
	var legal: Dictionary = view["legal"]
	var table: Array = view["table"]
	var att: int = view["attacker"]
	var dfn: int = view["defender"]
	if me == dfn:
		if view["taking"]:
			return "Вы берёте. Ждём, что подкинут…"
		if not (legal["defend"] as Dictionary).is_empty() or not (legal["transfer"] as Array).is_empty():
			if selected >= 0 and (legal["defend"] as Dictionary).has(selected):
				return "Нажмите карту на столе, которую бьёте"
			if not (legal["transfer"] as Array).is_empty():
				return "Отбивайтесь, переводите или берите"
			return "Отбивайтесь или берите"
		if legal["take"]:
			return "Нечем отбиться — берите"
		return "Ждём подкидывания…"
	if table.is_empty() and me == att:
		return "Ваш ход"
	if legal["pass"] or not (legal["attack"] as Array).is_empty():
		if view["taking"]:
			return "%s берёт — подкиньте или «Пас»" % names[dfn]
		if (legal["attack"] as Array).is_empty():
			return "Всё отбито — жмите «Бито»"
		return "Подкидывайте или жмите «Бито»"
	if table.is_empty():
		return "Ходит %s" % names[att]
	if view["taking"]:
		return "%s берёт" % names[dfn]
	return "%s отбивается" % names[dfn]


func _show_messages(_prev: Dictionary) -> void:
	var msgs: Array = view.get("messages", [])
	var total := int(view.get("msg_total", 0))
	var fresh := mini(total - _msg_total, msgs.size())
	_msg_total = total
	if main == null:
		return
	for i in range(msgs.size() - fresh, msgs.size()):
		if str(msgs[i]) != "Бито":
			main.toast(str(msgs[i]))


# ================================================================ ввод

func _on_card_pressed(c: int) -> void:
	if view.is_empty() or view["game_over"]:
		return
	var legal: Dictionary = view["legal"]
	var att: Array = legal["attack"]
	var def: Dictionary = legal["defend"]
	var tr: Array = legal["transfer"]
	if (view["hand"] as Array).has(c):
		if att.has(c):
			_act({"type": "attack", "card": c})
			return
		var can_def := def.has(c)
		var can_tr := tr.has(c)
		if can_def and not can_tr and (def[c] as Array).size() == 1:
			_act({"type": "defend", "card": c, "idx": def[c][0]})
			return
		if can_tr and not can_def:
			_act({"type": "transfer", "card": c})
			return
		if can_def or can_tr:
			selected = -1 if selected == c else c
			_relayout()
			return
		_shake(cards.get(c))
		return
	# карта на столе
	if selected >= 0 and def.has(selected):
		var table: Array = view["table"]
		for i in table.size():
			if int(table[i][0]) == c and (def[selected] as Array).has(i):
				_act({"type": "defend", "card": selected, "idx": i})
				return
	elif selected < 0:
		# можно сначала выбрать карту на столе: бьём её самой дешёвой подходящей картой
		var table: Array = view["table"]
		for i in table.size():
			if int(table[i][0]) == c:
				var best := -1
				var ts := int(view["trump_suit"])
				for hc in def:
					if (def[hc] as Array).has(i) and (best < 0 or Cards.value(hc, ts) < Cards.value(best, ts)):
						best = hc
				if best >= 0:
					selected = best
					_relayout()
				return


func _act(a: Dictionary) -> void:
	selected = -1
	Session.act(a)


func _shake(node) -> void:
	if node == null:
		return
	var x: float = node.position.x
	var tw: Tween = node.create_tween()
	for d in [8.0, -8.0, 5.0, 0.0]:
		tw.tween_property(node, "position:x", x + d, 0.05)


# ================================================================ диалоги

func _modal(title: String, text: String, buttons: Array) -> Control:
	var shade := ColorRect.new()
	shade.color = Color(0, 0, 0, 0.55)
	shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	shade.z_index = 500
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	shade.add_child(center)
	var p := PanelContainer.new()
	center.add_child(p)
	var col := VBoxContainer.new()
	col.custom_minimum_size = Vector2(460, 0)
	col.add_theme_constant_override("separation", 16)
	p.add_child(col)
	var t := _label(title, 40, true)
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	t.add_theme_color_override("font_color", Color("e9c46a"))
	col.add_child(t)
	if text != "":
		var l := _label(text, 22)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		col.add_child(l)
	for b in buttons:
		var btn := Button.new()
		btn.text = b[0]
		btn.custom_minimum_size = Vector2(0, 64)
		btn.pressed.connect(b[1])
		col.add_child(btn)
	add_child(shade)
	return shade


func ask_leave() -> void:
	if confirm:
		return
	var text := "Партия будет прервана."
	if Session.role == Session.Role.HOST:
		text = "Игра закончится для всех игроков."
	elif Session.role == Session.Role.CLIENT:
		text = "Вместо вас доиграет бот."
	confirm = _modal("Выйти в меню?", text, [
		["Выйти", func():
			Session.reset()
			main.show_menu()],
		["Продолжить", func():
			confirm.queue_free()
			confirm = null],
	])


func _show_game_over() -> void:
	if overlay or view.is_empty() or not view["game_over"]:
		return
	var me: int = view["me"]
	var l := int(view["loser"])
	var title := "Ничья!"
	if l == me:
		title = "Вы — дурак!"
	elif l >= 0:
		title = "Победа!"
	var fin: Array = view["finished"]
	var lines := []
	for i in fin.size():
		lines.append("%d. %s" % [i + 1, view["names"][fin[i]]])
	if l >= 0:
		lines.append("Дурак: %s" % view["names"][l])
	var btns := []
	if view.get("can_restart", false):
		btns.append(["Ещё раз", func(): Session.restart()])
	else:
		lines.append("\nЖдём, пока хост начнёт новую партию…")
	btns.append(["В меню", func():
		Session.reset()
		main.show_menu()])
	overlay = _modal(title, "\n".join(lines), btns)
