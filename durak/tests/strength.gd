extends SceneTree
## Сила сложного бота против среднего. godot --headless -s res://tests/strength.gd -- <games> <limit> <rollouts>

func _init() -> void:
	var args := OS.get_cmdline_user_args()
	var games := int(args[0]) if args.size() > 0 else 100
	DurakBot.mc_deck_limit = int(args[1]) if args.size() > 1 else 12
	DurakBot.mc_rollouts = int(args[2]) if args.size() > 2 else 16
	var rng := RandomNumberGenerator.new()
	rng.seed = 777
	for mode in 3:
		var lose := [0, 0]
		var t0 := Time.get_ticks_msec()
		for i in games:
			var levels := [2, 1] if i % 2 == 0 else [1, 2]
			var g := DurakGame.new()
			g.setup(["A", "B"], mode, 36, i * 13 + mode)
			while not g.game_over:
				var acted := false
				for q in [g.defender] + g.throwers():
					var a := DurakBot.decide(g, q, levels[q], rng)
					if not a.is_empty() and g.apply_action(q, a):
						acted = true
						break
				if not acted:
					push_error("deadlock")
					break
			if g.loser >= 0:
				lose[0 if levels[g.loser] == 2 else 1] += 1
		print("%s: сложный дурак %d, средний дурак %d, %.0f мс/партию" % [DurakGame.MODE_NAMES[mode], lose[0], lose[1], float(Time.get_ticks_msec() - t0) / games])
	quit()
