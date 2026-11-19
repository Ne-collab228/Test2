class_name DurakGame
extends RefCounted
## Полная логика игры «Дурак». Работает только на хосте (или локально).
## Клиенты получают «вид» (get_view) — то, что игроку разрешено знать.

enum Mode { PODKIDNOY = 0, PEREVODNOY = 1, PEREVODNOY_PODKIDNOY = 2 }
const MODE_NAMES := ["Подкидной", "Переводной", "Переводной-подкидной"]
const HAND_SIZE := 6

var mode := Mode.PODKIDNOY
var deck_size := 36
var n := 2
var names: Array = []
var deck: Array[int] = []  # deck[0] — козырь (лежит снизу), берём с конца
var trump_card := -1
var trump_suit := 0
var hands: Array = []  # Array[Array[int]]
var table: Array = []  # [[атака, защита(-1)], ...]
var discard: Array[int] = []
var known: Array = []  # для каждого игрока: карты, которые все видели у него в руке
var attacker := 0  # главный атакующий
var defender := 1
var taking := false
var passed: Dictionary = {}
var finished: Array[int] = []  # порядок выхода из игры
var game_over := false
var loser := -1
var first_round := true
var round_no := 0
var messages: Array = []
var msg_total := 0
var seq := 0
var rng := RandomNumberGenerator.new()
var last_action := {}
var last_round := {}  # что произошло в последнем завершённом раунде (для анимации)
var silent := false  # в симуляциях не пишем сообщения


func setup(p_names: Array, p_mode: int, p_deck_size: int, seed_value: int = -1) -> void:
	names = p_names.duplicate()
	n = names.size()
	mode = p_mode
	deck_size = p_deck_size
	if seed_value >= 0:
		rng.seed = seed_value
	else:
		rng.randomize()
	deck = Cards.build_deck(deck_size)
	# Тасовка Фишера–Йетса на собственном генераторе (для воспроизводимости тестов)
	for i in range(deck.size() - 1, 0, -1):
		var j := rng.randi_range(0, i)
		var t := deck[i]
		deck[i] = deck[j]
		deck[j] = t
	hands.clear()
	known.clear()
	for p in n:
		hands.append([])
		known.append([])
	for i in HAND_SIZE:
		for p in n:
			if not deck.is_empty():
				hands[p].append(deck.pop_back())
	if not deck.is_empty():
		trump_card = deck[0]
	else:
		# Колода роздана целиком — козырем становится последняя розданная карта
		var lp: int = (n - 1)
		trump_card = hands[lp].back()
		known[lp].append(trump_card)
	trump_suit = Cards.suit(trump_card)
	table.clear()
	discard.clear()
	finished.clear()
	passed.clear()
	taking = false
	game_over = false
	loser = -1
	first_round = true
	round_no = 1
	messages.clear()
	msg_total = 0
	# Первым ходит игрок с младшим козырем
	var best := -1
	var best_rank := 99
	for p in n:
		for c in hands[p]:
			if Cards.suit(c) == trump_suit and Cards.rank(c) < best_rank:
				best_rank = Cards.rank(c)
				best = p
	if best < 0:
		best = rng.randi_range(0, n - 1)
		_msg("Первым ходит %s" % names[best])
	else:
		_msg("%s ходит первым (младший козырь %s)" % [names[best], Cards.to_text(Cards.make(trump_suit, best_rank))])
	attacker = best
	defender = next_active(attacker)
	seq += 1


# ---------------------------------------------------------------- состояние

func is_active(p: int) -> bool:
	return not (deck.is_empty() and hands[p].is_empty())


func next_active(p: int) -> int:
	for i in range(1, n + 1):
		var q := (p + i) % n
		if q != p and is_active(q):
			return q
	return -1


func throwers() -> Array:
	var res := []
	if mode == Mode.PEREVODNOY:
		if attacker != defender and is_active(attacker):
			res.append(attacker)
		return res
	for i in n:
		var q := (attacker + i) % n
		if q != defender and is_active(q):
			res.append(q)
	return res


func undefended_count() -> int:
	var k := 0
	for pair in table:
		if pair[1] == -1:
			k += 1
	return k


func all_beaten() -> bool:
	return not table.is_empty() and undefended_count() == 0


func table_limit() -> int:
	return 5 if first_round else 6


func can_add_attack() -> bool:
	return table.size() < table_limit() and undefended_count() < hands[defender].size()


func table_ranks() -> Dictionary:
	var r := {}
	for pair in table:
		r[Cards.rank(pair[0])] = true
		if pair[1] != -1:
			r[Cards.rank(pair[1])] = true
	return r


func table_cards() -> Array:
	var res := []
	for pair in table:
		res.append(pair[0])
		if pair[1] != -1:
			res.append(pair[1])
	return res


# ---------------------------------------------------------------- проверки ходов

func can_attack(p: int, c: int) -> bool:
	if game_over or p == defender or not hands[p].has(c):
		return false
	if table.is_empty():
		return p == attacker and not taking
	if not throwers().has(p) or not can_add_attack():
		return false
	return table_ranks().has(Cards.rank(c))


func can_defend(p: int, idx: int, c: int) -> bool:
	if game_over or p != defender or taking:
		return false
	if idx < 0 or idx >= table.size() or table[idx][1] != -1:
		return false
	return hands[p].has(c) and Cards.beats(table[idx][0], c, trump_suit)


func transfer_target() -> int:
	return next_active(defender)


func can_transfer(p: int, c: int) -> bool:
	if game_over or mode == Mode.PODKIDNOY or p != defender or taking:
		return false
	if table.is_empty() or not hands[p].has(c):
		return false
	for pair in table:
		if pair[1] != -1 or Cards.rank(pair[0]) != Cards.rank(c):
			return false
	var nd := transfer_target()
	if nd < 0 or nd == defender:
		return false
	if table.size() + 1 > table_limit():
		return false
	return hands[nd].size() >= table.size() + 1


func can_take(p: int) -> bool:
	return not game_over and p == defender and not taking and undefended_count() > 0


func can_pass(p: int) -> bool:
	if game_over or p == defender or table.is_empty() or passed.has(p):
		return false
	if not throwers().has(p):
		return false
	return taking or all_beaten()


func can_throw_any(p: int) -> bool:
	if hands[p].is_empty() or not can_add_attack():
		return false
	var ranks := table_ranks()
	for c in hands[p]:
		if ranks.has(Cards.rank(c)):
			return true
	return false


func legal_for(p: int) -> Dictionary:
	var att := []
	var def := {}
	var tr := []
	if p >= 0 and p < n:
		for c in hands[p]:
			if can_attack(p, c):
				att.append(c)
			if can_transfer(p, c):
				tr.append(c)
			if p == defender:
				var idxs := []
				for i in table.size():
					if can_defend(p, i, c):
						idxs.append(i)
				if not idxs.is_empty():
					def[c] = idxs
	return {
		"attack": att, "defend": def, "transfer": tr,
		"take": can_take(p), "pass": can_pass(p),
	}


# ---------------------------------------------------------------- действия

## Универсальная точка входа: {"type": "attack"/"defend"/"transfer"/"take"/"pass", "card", "idx"}
func apply_action(p: int, a: Dictionary) -> bool:
	var t: String = str(a.get("type", ""))
	var c: int = int(a.get("card", -1))
	var idx: int = int(a.get("idx", -1))
	var ok := false
	match t:
		"attack":
			ok = attack(p, c)
		"defend":
			ok = defend(p, idx, c)
		"transfer":
			ok = transfer(p, c)
		"take":
			ok = take(p)
		"pass":
			ok = pass_turn(p)
	if ok and not silent:
		last_action = {"seat": p, "type": t, "card": c, "idx": idx, "seq": seq}
	return ok


func attack(p: int, c: int) -> bool:
	if not can_attack(p, c):
		return false
	_remove_from_hand(p, c)
	table.append([c, -1])
	passed.clear()
	_after_change()
	return true


func defend(p: int, idx: int, c: int) -> bool:
	if not can_defend(p, idx, c):
		return false
	_remove_from_hand(p, c)
	table[idx][1] = c
	passed.clear()
	_after_change()
	return true


func transfer(p: int, c: int) -> bool:
	if not can_transfer(p, c):
		return false
	var nd := transfer_target()
	_remove_from_hand(p, c)
	table.append([c, -1])
	attacker = p
	defender = nd
	passed.clear()
	_msg("%s переводит на %s" % [names[p], names[nd]])
	_after_change()
	return true


func take(p: int) -> bool:
	if not can_take(p):
		return false
	taking = true
	passed.clear()
	_msg("%s берёт" % names[p])
	_after_change()
	return true


func pass_turn(p: int) -> bool:
	if not can_pass(p):
		return false
	passed[p] = true
	_after_change()
	return true


func _remove_from_hand(p: int, c: int) -> void:
	hands[p].erase(c)
	known[p].erase(c)


func _after_change() -> void:
	seq += 1
	_check_round_end()


func _check_round_end() -> void:
	if game_over or table.is_empty():
		return
	if not (taking or all_beaten()):
		# Защитник без карт (и подкинуть больше нельзя) — такого не бывает:
		# лимит не позволит положить больше карт, чем у него на руках.
		return
	for q in throwers():
		if not passed.has(q) and can_throw_any(q):
			return
	_end_round()


func _end_round() -> void:
	var took := taking
	var cards := table_cards()
	last_round = {"cards": cards.duplicate(), "taken_by": defender if took else -1, "round": round_no}
	if took:
		for c in cards:
			hands[defender].append(c)
			known[defender].append(c)
	else:
		discard.append_array(cards)
		_msg("Бито")
	table.clear()
	taking = false
	passed.clear()
	# Добор: сначала главный атакующий, затем остальные по кругу, защитник — последним
	var order := [attacker]
	for i in range(1, n):
		var q := (attacker + i) % n
		if q != defender and q != attacker:
			order.append(q)
	if defender != attacker:
		order.append(defender)
	for p in order:
		while hands[p].size() < HAND_SIZE and not deck.is_empty():
			var c: int = deck.pop_back()
			hands[p].append(c)
			if c == trump_card:
				known[p].append(c)
	# Кто вышел из игры
	for p in n:
		if not is_active(p) and not finished.has(p):
			finished.append(p)
			_msg("%s выходит из игры" % names[p])
	var remaining := []
	for p in n:
		if is_active(p):
			remaining.append(p)
	first_round = false
	round_no += 1
	if remaining.size() <= 1:
		game_over = true
		loser = remaining[0] if remaining.size() == 1 else -1
		if loser >= 0:
			_msg("%s — дурак!" % names[loser])
		else:
			_msg("Ничья!")
		seq += 1
		return
	var old_def := defender
	if took or not is_active(old_def):
		attacker = next_active(old_def)
	else:
		attacker = old_def
	defender = next_active(attacker)
	seq += 1


func _msg(t: String) -> void:
	if silent:
		return
	messages.append(t)
	msg_total += 1
	if messages.size() > 30:
		messages.pop_front()


## Глубокая копия состояния (для симуляций ИИ).
func clone() -> DurakGame:
	var g := DurakGame.new()
	g.mode = mode
	g.deck_size = deck_size
	g.n = n
	g.names = names
	g.deck = deck.duplicate()
	g.trump_card = trump_card
	g.trump_suit = trump_suit
	for h in hands:
		g.hands.append(h.duplicate())
	for k in known:
		g.known.append(k.duplicate())
	for pair in table:
		g.table.append([pair[0], pair[1]])
	g.discard = discard.duplicate()
	g.attacker = attacker
	g.defender = defender
	g.taking = taking
	g.passed = passed.duplicate()
	g.finished = finished.duplicate()
	g.game_over = game_over
	g.loser = loser
	g.first_round = first_round
	g.round_no = round_no
	g.silent = true
	return g


# ---------------------------------------------------------------- вид для игрока

func get_view(me: int) -> Dictionary:
	var counts := []
	for p in n:
		counts.append(hands[p].size())
	var my_hand := []
	if me >= 0 and me < n:
		my_hand = Cards.sort_hand(hands[me], trump_suit)
	var tbl := []
	for pair in table:
		tbl.append([pair[0], pair[1]])
	return {
		"mode": mode, "deck_size": deck_size, "n": n, "me": me, "names": names.duplicate(),
		"hand": my_hand, "counts": counts, "table": tbl,
		"trump_card": trump_card, "trump_suit": trump_suit,
		"deck_count": deck.size(), "discard_count": discard.size(),
		"attacker": attacker, "defender": defender, "taking": taking,
		"passed": passed.keys(), "throwers": throwers(), "finished": finished.duplicate(),
		"game_over": game_over, "loser": loser, "messages": messages.slice(-4), "msg_total": msg_total,
		"seq": seq, "last_round": last_round, "last_action": last_action, "round": round_no, "legal": legal_for(me), "transfer_to": transfer_target() if not game_over else -1,
	}
