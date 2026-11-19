class_name DurakBot
## ИИ для «Дурака». Видит только то, что видел бы честный игрок:
## свою руку, стол, отбой, козырь, число карт у соперников и карты,
## которые соперники при всех забрали со стола (g.known).

enum Level { EASY = 0, MEDIUM = 1, HARD = 2 }
const LEVEL_NAMES := ["Лёгкий", "Средний", "Сложный"]

## Сложный бот на ключевых решениях «мысленно доигрывает» партию:
## раскладывает невидимые ему карты случайно и играет за всех средней стратегией.
static var mc_deck_limit := 12
static var mc_rollouts := 16
static var mc_max_steps := 400


static func decide(g: DurakGame, p: int, level: int, rng: RandomNumberGenerator) -> Dictionary:
	if g.game_over or not g.is_active(p):
		return {}
	if level == Level.HARD and g.deck.size() <= mc_deck_limit and mc_rollouts > 0:
		var mc := _monte_carlo(g, p, rng)
		if not mc.is_empty():
			return mc
	var legal := g.legal_for(p)
	if p == g.defender:
		if g.taking or g.undefended_count() == 0:
			return {}
		return _defend(g, p, level, legal, rng)
	if g.table.is_empty():
		if p == g.attacker and not g.hands[p].is_empty():
			return _lead(g, p, level, rng)
		return {}
	var c := _choose_throw(g, p, level, legal, rng)
	if c >= 0:
		return {"type": "attack", "card": c}
	if legal["pass"]:
		return {"type": "pass"}
	return {}


# ---------------------------------------------------------------- атака

static func _lead(g: DurakGame, p: int, level: int, rng: RandomNumberGenerator) -> Dictionary:
	var hand: Array = g.hands[p]
	var ts := g.trump_suit
	if level == Level.EASY:
		var non_trump := hand.filter(func(c): return Cards.suit(c) != ts)
		var pool: Array = non_trump if (not non_trump.is_empty() and rng.randf() < 0.8) else hand
		return {"type": "attack", "card": pool[rng.randi_range(0, pool.size() - 1)]}

	var best := -1
	var best_score := 1e9
	var counts := {}
	for c in hand:
		counts[Cards.rank(c)] = counts.get(Cards.rank(c), 0) + 1
	var endgame := g.deck.is_empty()
	for c in hand:
		var score := float(Cards.value(c, ts))
		var same: int = counts[Cards.rank(c)]
		if Cards.suit(c) != ts and same > 1:
			score -= 2.5 * (same - 1)  # выгодно ходить парами — быстрее сбрасываем карты
		if level == Level.HARD:
			var pb := beat_probability(g, p, c, g.defender)
			if endgame:
				score += pb * 14.0  # в эндшпиле ищем карту, которую не побьют
				if pb == 0.0 and hand.size() > 1:
					score -= 6.0
			else:
				score += pb * 2.0
		if score < best_score:
			best_score = score
			best = c
	return {"type": "attack", "card": best}


static func _choose_throw(g: DurakGame, p: int, level: int, legal: Dictionary, rng: RandomNumberGenerator) -> int:
	var cands: Array = legal["attack"]
	if cands.is_empty():
		return -1
	var ts := g.trump_suit
	cands = cands.duplicate()
	cands.sort_custom(func(a, b): return Cards.value(a, ts) < Cards.value(b, ts))
	var deck := g.deck.size()

	if not (g.taking or g.all_beaten()):
		# Защитник ещё думает. Атакующий может «доложить» карты того же ранга к первому ходу.
		if p != g.attacker or level == Level.EASY:
			return -1
		for pair in g.table:
			if pair[1] != -1:
				return -1
		var lead_rank := Cards.rank(g.table[0][0])
		for c in cands:
			if Cards.rank(c) == lead_rank and Cards.suit(c) != ts and (Cards.rank(c) <= 10 or deck == 0):
				return c
		return -1

	match level:
		Level.EASY:
			if rng.randf() < 0.45:
				var nt := cands.filter(func(c): return Cards.suit(c) != ts)
				if not nt.is_empty():
					return nt[rng.randi_range(0, nt.size() - 1)]
			return -1
		Level.MEDIUM:
			for c in cands:
				var trump := Cards.suit(c) == ts
				var r := Cards.rank(c)
				if g.taking:
					if not trump and r <= (14 if deck <= 6 else 11):
						return c
					if trump and deck == 0 and r <= 10:
						return c
				else:
					if not trump and r <= (14 if deck == 0 else 10):
						return c
					if trump and deck == 0 and r <= 9:
						return c
			return -1
		_:
			return _hard_throw(g, p, cands, deck)


static func _hard_throw(g: DurakGame, p: int, cands: Array, deck: int) -> int:
	var ts := g.trump_suit
	var my_size: int = g.hands[p].size()
	for c in cands:
		var trump := Cards.suit(c) == ts
		var r := Cards.rank(c)
		if deck == 0:
			# В конце главное — избавиться от карт. Сильные козыри берущему не дарим,
			# если только это не помогает выйти самому.
			if g.taking and trump and r >= 12 and my_size > 2:
				continue
			return c
		if g.taking:
			if not trump and (r <= 12 or deck <= 4):
				return c
			if trump and r <= 8 and deck <= 3:
				return c
		else:
			var pb := beat_probability(g, p, c, g.defender)
			if not trump and r <= 10:
				return c
			if not trump and r <= 13 and (pb < 0.35 or deck <= 3):
				return c
			if trump and deck <= 2 and pb < 0.3:
				return c
	return -1


# ---------------------------------------------------------------- защита

static func _defend(g: DurakGame, p: int, level: int, legal: Dictionary, rng: RandomNumberGenerator) -> Dictionary:
	var ts := g.trump_suit
	var deck := g.deck.size()
	var transfers: Array = legal["transfer"]
	var plan := beat_all_plan(g, p)

	if level == Level.EASY:
		if not transfers.is_empty() and rng.randf() < 0.35:
			return {"type": "transfer", "card": transfers[0]}
		var def: Dictionary = legal["defend"]
		if def.is_empty() or rng.randf() < 0.08:
			return {"type": "take"}
		var keys := def.keys()
		var c: int = keys[rng.randi_range(0, keys.size() - 1)]
		var idxs: Array = def[c]
		return {"type": "defend", "card": c, "idx": idxs[0]}

	# Перевод
	if not transfers.is_empty():
		var tc: int = transfers[0]
		for c in transfers:
			if Cards.value(c, ts) < Cards.value(tc, ts):
				tc = c
		var trump := Cards.suit(tc) == ts
		var plan_cost := _plan_cost(plan, ts)
		var do_transfer := false
		if plan.is_empty():
			do_transfer = true
		elif not trump:
			do_transfer = true
		elif level == Level.HARD:
			do_transfer = Cards.rank(tc) <= 10 or plan_cost > Cards.value(tc, ts) + 6
		if do_transfer:
			return {"type": "transfer", "card": tc}

	if plan.is_empty():
		return {"type": "take"}

	# Иногда выгоднее взять мелочь, чем тратить старшие козыри в начале игры
	var high_trumps := 0
	for step in plan:
		if Cards.suit(step[1]) == ts and Cards.rank(step[1]) >= 12:
			high_trumps += 1
	var attack_value := 0
	for pair in g.table:
		attack_value += Cards.rank(pair[0])
	if high_trumps > 0 and deck >= 10 and g.table.size() <= 2 and attack_value <= 16:
		if level == Level.MEDIUM or deck >= 14 or high_trumps > 1:
			return {"type": "take"}
	return {"type": "defend", "idx": plan[0][0], "card": plan[0][1]}


## Жадный план: чем отбить каждую неотбитую карту. Пустой массив — отбиться нельзя.
static func beat_all_plan(g: DurakGame, p: int) -> Array:
	var ts := g.trump_suit
	var open := []
	for i in g.table.size():
		if g.table[i][1] == -1:
			open.append(i)
	var hand: Array = g.hands[p].duplicate()
	# Сначала карты с наименьшим числом вариантов
	open.sort_custom(func(a, b):
		var ca := _options(g.table[a][0], hand, ts).size()
		var cb := _options(g.table[b][0], hand, ts).size()
		if ca != cb:
			return ca < cb
		return Cards.value(g.table[a][0], ts) > Cards.value(g.table[b][0], ts))
	var plan := []
	for i in open:
		var opts := _options(g.table[i][0], hand, ts)
		if opts.is_empty():
			return []
		var best: int = opts[0]
		for c in opts:
			if Cards.value(c, ts) < Cards.value(best, ts):
				best = c
		hand.erase(best)
		plan.append([i, best])
	return plan


static func _options(a: int, hand: Array, ts: int) -> Array:
	return hand.filter(func(c): return Cards.beats(a, c, ts))


static func _plan_cost(plan: Array, ts: int) -> int:
	if plan.is_empty():
		return 99
	var s := 0
	for step in plan:
		s += Cards.value(step[1], ts)
	return s


# ---------------------------------------------------------------- подсчёт карт

## Карты, местоположение которых игрок p не знает (в чужих руках или в колоде).
static func unseen_cards(g: DurakGame, p: int) -> Array:
	var seen := {}
	for c in g.hands[p]:
		seen[c] = true
	for c in g.discard:
		seen[c] = true
	for c in g.table_cards():
		seen[c] = true
	for q in g.n:
		if q != p:
			for c in g.known[q]:
				seen[c] = true
	if not g.deck.is_empty():
		seen[g.trump_card] = true  # козырь лежит открытым под колодой
	var res := []
	for c in Cards.build_deck(g.deck_size):
		if not seen.has(c):
			res.append(c)
	return res


## Оценка вероятности того, что у игрока target есть карта, бьющая c.
static func beat_probability(g: DurakGame, p: int, c: int, target: int) -> float:
	if target < 0 or target == p:
		return 0.0
	var ts := g.trump_suit
	for k in g.known[target]:
		if Cards.beats(c, k, ts):
			return 1.0
	var slots: int = g.hands[target].size() - g.known[target].size()
	if slots <= 0:
		return 0.0
	var unseen := unseen_cards(g, p)
	if unseen.is_empty():
		return 0.0
	var beaters := 0
	for u in unseen:
		if Cards.beats(c, u, ts):
			beaters += 1
	if beaters == 0:
		return 0.0
	var opponents := 0
	for q in g.n:
		if q != p and g.is_active(q):
			opponents += 1
	if g.deck.is_empty() and opponents == 1:
		return 1.0  # все невидимые карты — у единственного соперника
	# Вероятность, что ни одна из slots случайных карт не бьёт c
	var u := float(unseen.size())
	var none := 1.0
	for i in mini(slots, unseen.size()):
		none *= maxf(0.0, (u - beaters - i) / (u - i))
	return 1.0 - none


# ---------------------------------------------------------------- Монте-Карло

static func _candidates(g: DurakGame, p: int) -> Array:
	var res := []
	var ts := g.trump_suit
	var hand: Array = g.hands[p]
	if p == g.defender:
		if g.taking or g.undefended_count() == 0:
			return res
		res.append({"type": "take"})
		var seen_tr := {}
		for c in hand:
			if g.can_transfer(p, c) and not seen_tr.has(Cards.suit(c) == ts):
				seen_tr[Cards.suit(c) == ts] = true
				res.append({"type": "transfer", "card": c})
		for i in g.table.size():
			if g.table[i][1] != -1:
				continue
			# по одному самому дешёвому варианту каждой масти
			var best_by_suit := {}
			for c in hand:
				if g.can_defend(p, i, c):
					var s := Cards.suit(c)
					if not best_by_suit.has(s) or Cards.rank(c) < Cards.rank(best_by_suit[s]):
						best_by_suit[s] = c
			for s in best_by_suit:
				res.append({"type": "defend", "idx": i, "card": best_by_suit[s]})
		return res
	if g.table.is_empty():
		if p != g.attacker:
			return res
		var by_rank := {}
		for c in hand:
			var r := Cards.rank(c)
			if not by_rank.has(r) or Cards.value(c, ts) < Cards.value(by_rank[r], ts):
				by_rank[r] = c
		for r in by_rank:
			res.append({"type": "attack", "card": by_rank[r]})
		return res
	if not (g.taking or g.all_beaten()):
		return res
	if not g.can_pass(p):
		return res
	res.append({"type": "pass"})
	var ranks := {}
	for c in hand:
		if g.can_attack(p, c):
			var key := Cards.rank(c) * 2 + (1 if Cards.suit(c) == ts else 0)
			if not ranks.has(key):
				ranks[key] = true
				res.append({"type": "attack", "card": c})
	return res


static func _monte_carlo(g: DurakGame, p: int, rng: RandomNumberGenerator) -> Dictionary:
	var cands := _candidates(g, p)
	if cands.size() <= 1:
		return {}
	var scores := []
	scores.resize(cands.size())
	scores.fill(0.0)
	for k in mc_rollouts:
		var det := g.clone()
		_determinize(det, p, rng)
		var seed_value := rng.randi()
		for i in cands.size():
			var sim := det.clone()
			if not sim.apply_action(p, cands[i]):
				scores[i] += 99.0
				continue
			var r := RandomNumberGenerator.new()
			r.seed = seed_value
			scores[i] += _playout(sim, p, r)
	var best := 0
	for i in cands.size():
		if scores[i] < scores[best]:
			best = i
	return cands[best]


static func _playout(g: DurakGame, p: int, rng: RandomNumberGenerator) -> float:
	var steps := 0
	while not g.game_over and steps < mc_max_steps:
		steps += 1
		var acted := false
		var order := [g.defender] + g.throwers()
		for q in order:
			var a := decide(g, q, Level.MEDIUM, rng)
			if a.is_empty():
				continue
			if g.apply_action(q, a):
				acted = true
				break
		if not acted:
			break
	if not g.game_over:
		# Не доиграли — оцениваем по числу карт на руках
		return clampf(g.hands[p].size() / 12.0, 0.0, 1.0)
	if g.loser == p:
		return 1.0
	if g.loser < 0:
		return 0.4
	return 0.0


## Случайно раскладывает невидимые игроку p карты по чужим рукам и колоде.
static func _determinize(g: DurakGame, p: int, rng: RandomNumberGenerator) -> void:
	var pool := unseen_cards(g, p)
	for i in range(pool.size() - 1, 0, -1):
		var j := rng.randi_range(0, i)
		var t = pool[i]
		pool[i] = pool[j]
		pool[j] = t
	var pos := 0
	for q in g.n:
		if q == p:
			continue
		var slots: int = g.hands[q].size() - g.known[q].size()
		var h: Array = g.known[q].duplicate()
		for i in slots:
			h.append(pool[pos])
			pos += 1
		g.hands[q] = h
	if not g.deck.is_empty():
		var d: Array[int] = [g.trump_card]
		for i in g.deck.size() - 1:
			d.append(pool[pos])
			pos += 1
		g.deck = d
