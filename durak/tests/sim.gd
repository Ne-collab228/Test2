extends SceneTree
## Прогон партий бот против бота: проверка правил и силы ботов.
## godot --headless --path . -s res://tests/sim.gd

var errors := 0


func _init() -> void:
	DurakBot.mc_rollouts = 4  # быстрее; полная сила проверяется в strength.gd
	var rng := RandomNumberGenerator.new()
	rng.seed = 12345
	var total := 0
	# 1) Инварианты во всех режимах/колодах/составах
	for mode in 3:
		for deck in [24, 36, 52]:
			var max_p := 4 if deck == 24 else 6
			for players in range(2, max_p + 1):
				for i in 8:
					var levels := []
					for p in players:
						levels.append(rng.randi_range(0, 2))
					_play(mode, deck, levels, rng, total)
					total += 1
	print("Партий сыграно: ", total, ", ошибок: ", errors)
	if not OS.get_cmdline_user_args().has("--full"):
		quit(1 if errors > 0 else 0)
		return

	# 2) Сила ботов: сложный против лёгкого/среднего
	for pair in [[2, 0], [2, 1], [1, 0]]:
		for mode in 3:
			var lose := [0, 0]
			var draws := 0
			for i in 400:
				var levels := [pair[0], pair[1]] if i % 2 == 0 else [pair[1], pair[0]]
				var res := _play(mode, 36, levels, rng, i)
				if res < 0:
					draws += 1
				else:
					lose[0 if levels[res] == pair[0] else 1] += 1
			print("%s vs %s, %s: дурак %s раз / %s раз, ничьих %d" % [
				DurakBot.LEVEL_NAMES[pair[0]], DurakBot.LEVEL_NAMES[pair[1]],
				DurakGame.MODE_NAMES[mode], lose[0], lose[1], draws])
	# 3) Мультиплеер: один сложный против 3 средних
	var hard_losses := 0
	for i in 400:
		var levels := [1, 1, 1, 1]
		var hp := i % 4
		levels[hp] = 2
		if _play(2, 36, levels, rng, i) == hp:
			hard_losses += 1
	print("4 игрока (1 сложный, 3 средних): сложный дурак в %.1f%% (честно 25%%)" % (hard_losses / 4.0))
	quit(1 if errors > 0 else 0)


func _play(mode: int, deck: int, levels: Array, rng: RandomNumberGenerator, seed_value: int) -> int:
	var g := DurakGame.new()
	var names := []
	for i in levels.size():
		names.append("Бот%d" % i)
	g.setup(names, mode, deck, seed_value * 7919 + mode * 31 + deck)
	var steps := 0
	while not g.game_over:
		steps += 1
		if steps > 5000:
			_err("слишком долгая партия", g)
			return -1
		var acted := false
		var order := [g.defender] + g.throwers()
		for p in order:
			var a := DurakBot.decide(g, p, levels[p], rng)
			if a.is_empty():
				continue
			if not g.apply_action(p, a):
				_err("недопустимый ход бота %d: %s" % [p, a], g)
				return -1
			acted = true
			break
		if not acted:
			_err("тупик: никто не ходит", g)
			return -1
		_check(g)
	return g.loser


func _check(g: DurakGame) -> void:
	var cnt := g.deck.size() + g.discard.size() + g.table_cards().size()
	var all := {}
	for h in g.hands:
		cnt += h.size()
		for c in h:
			if all.has(c):
				_err("дубликат карты", g)
			all[c] = true
	if cnt != g.deck_size:
		_err("карты потерялись: %d" % cnt, g)
	if g.table.size() > 6:
		_err("на столе больше 6 карт", g)
	for p in g.n:
		for k in g.known[p]:
			if not g.hands[p].has(k):
				_err("known не в руке", g)


func _err(t: String, g: DurakGame) -> void:
	errors += 1
	if errors < 10:
		printerr("ОШИБКА: ", t, " | режим ", g.mode, " колода ", g.deck_size, " игроков ", g.n,
			" стол ", g.table, " атакующий ", g.attacker, " защитник ", g.defender, " берёт ", g.taking,
			" руки ", g.hands)
