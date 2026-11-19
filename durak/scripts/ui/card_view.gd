class_name CardView
extends Control
## Одна карта: рисуется векторно, без картинок.

signal pressed(card: int)

const W := 96.0
const H := 136.0
const RED := Color("d62839")
const BLACK := Color("1b1b1f")

var card := -1
var face_up := true
var selected := false:
	set(v):
		selected = v
		queue_redraw()
var playable := false:
	set(v):
		playable = v
		queue_redraw()
var dimmed := false:
	set(v):
		dimmed = v
		queue_redraw()
var target_hint := false:
	set(v):
		target_hint = v
		queue_redraw()
var is_trump := false

var _font: Font


func _init(p_card: int = -1, p_face_up: bool = true) -> void:
	card = p_card
	face_up = p_face_up
	size = Vector2(W, H)
	custom_minimum_size = Vector2(W, H)
	pivot_offset = Vector2(W, H) / 2.0
	mouse_filter = Control.MOUSE_FILTER_IGNORE


func _ready() -> void:
	_font = get_theme_default_font()
	var bold = load("res://fonts/DejaVuSans-Bold.ttf")
	if bold:
		_font = bold


func set_interactive(on: bool) -> void:
	mouse_filter = Control.MOUSE_FILTER_STOP if on else Control.MOUSE_FILTER_IGNORE


func _gui_input(event: InputEvent) -> void:
	if event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_LEFT:
		pressed.emit(card)
		accept_event()


func _draw() -> void:
	var r := Rect2(Vector2.ZERO, size)
	# тень
	_rounded(Rect2(r.position + Vector2(2, 3), r.size), Color(0, 0, 0, 0.28), 9)
	if not face_up:
		_draw_back(r)
		return
	var border := Color(0.15, 0.15, 0.15, 0.6)
	if selected:
		border = Color("ffd60a")
	elif target_hint:
		border = Color("4cc9f0")
	elif playable:
		border = Color("80ed99")
	_rounded(r, Color("fbfaf5"), 9)
	_rounded_outline(r, border, 9, 4.0 if (selected or target_hint or playable) else 1.5)
	var col := RED if Cards.is_red(card) else BLACK
	var rank_t := Cards.rank_text(card)
	var suit_t: String = Cards.SUIT_SYMBOLS[Cards.suit(card)]
	# углы
	draw_string(_font, Vector2(7, 27), rank_t, HORIZONTAL_ALIGNMENT_LEFT, -1, 24, col)
	draw_string(_font, Vector2(8, 50), suit_t, HORIZONTAL_ALIGNMENT_LEFT, -1, 22, col)
	draw_set_transform(size, PI, Vector2.ONE)
	draw_string(_font, Vector2(7, 27), rank_t, HORIZONTAL_ALIGNMENT_LEFT, -1, 24, col)
	draw_string(_font, Vector2(8, 50), suit_t, HORIZONTAL_ALIGNMENT_LEFT, -1, 22, col)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	# центр
	var big := 58 if Cards.rank(card) <= 10 else 50
	draw_string(_font, Vector2(0, size.y / 2 + big * 0.36), suit_t, HORIZONTAL_ALIGNMENT_CENTER, size.x, big, col)
	if Cards.rank(card) > 10:
		draw_string(_font, Vector2(0, size.y / 2 - 22), rank_t, HORIZONTAL_ALIGNMENT_CENTER, size.x, 22, col.lerp(Color.WHITE, 0.15))
	if is_trump:
		draw_circle(Vector2(size.x - 12, 12), 5, Color("ffb703"))
	if dimmed:
		_rounded(r, Color(0, 0, 0, 0.32), 9)


func _draw_back(r: Rect2) -> void:
	_rounded(r, Color("f1f1f1"), 9)
	var inner := r.grow(-5)
	_rounded(inner, Color("9d1b2f"), 6)
	# узор ромбами
	var step := 14.0
	var c := Color(1, 1, 1, 0.16)
	var x := inner.position.x - inner.size.y
	while x < inner.end.x:
		var a := Vector2(x, inner.position.y)
		var b := Vector2(x + inner.size.y, inner.end.y)
		draw_line(_clip(a, b, inner)[0], _clip(a, b, inner)[1], c, 1.5)
		var a2 := Vector2(x + inner.size.y, inner.position.y)
		var b2 := Vector2(x, inner.end.y)
		draw_line(_clip(a2, b2, inner)[0], _clip(a2, b2, inner)[1], c, 1.5)
		x += step
	_rounded_outline(inner.grow(-4), Color(1, 1, 1, 0.5), 5, 1.5)


func _clip(a: Vector2, b: Vector2, rect: Rect2) -> Array:
	# Простое отсечение отрезка по прямоугольнику (параметрически)
	var t0 := 0.0
	var t1 := 1.0
	var d := b - a
	for i in 4:
		var p: float
		var q: float
		match i:
			0:
				p = -d.x; q = a.x - rect.position.x
			1:
				p = d.x; q = rect.end.x - a.x
			2:
				p = -d.y; q = a.y - rect.position.y
			_:
				p = d.y; q = rect.end.y - a.y
		if absf(p) < 0.0001:
			if q < 0:
				return [a, a]
		else:
			var t := q / p
			if p < 0:
				t0 = maxf(t0, t)
			else:
				t1 = minf(t1, t)
	if t0 > t1:
		return [a, a]
	return [a + d * t0, a + d * t1]


func _rounded(r: Rect2, col: Color, radius: float) -> void:
	var sb := StyleBoxFlat.new()
	sb.bg_color = col
	sb.set_corner_radius_all(int(radius))
	sb.anti_aliasing = true
	draw_style_box(sb, r)


func _rounded_outline(r: Rect2, col: Color, radius: float, width: float) -> void:
	var sb := StyleBoxFlat.new()
	sb.draw_center = false
	sb.border_color = col
	sb.set_border_width_all(int(ceil(width)))
	sb.set_corner_radius_all(int(radius))
	sb.anti_aliasing = true
	draw_style_box(sb, r)
