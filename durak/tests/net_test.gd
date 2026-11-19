extends Node
## Сетевой тест: godot --headless -- --net-test host|client

var role := ""
var games_done := 0
var errors := 0


func run() -> void:
	var args := OS.get_cmdline_user_args()
	role = args[args.find("--net-test") + 1]
	Session.settings["name"] = "Хост" if role == "host" else "Клиент"
	Session.settings["bots"] = 1
	Session.settings["mode"] = 2
	Session.settings["level"] = 2
	Session.left.connect(func(r): print(role, ": left: ", r))
	if role == "host":
		var err := Session.host(Net.Transport.LAN)
		print("host: lobby opened ", err)
		while Session.lobby.size() < 2:
			await get_tree().create_timer(0.2).timeout
		print("host: lobby = ", Session.lobby)
		Session.start_hosted_game()
	else:
		Net.start_discovery()
		var t := 0.0
		while Net.hosts.is_empty() and t < 8.0:
			await get_tree().create_timer(0.2).timeout
			t += 0.2
		print("client: discovered hosts: ", Net.hosts.keys())
		var ip: String = Net.hosts.keys()[0] if not Net.hosts.is_empty() else "127.0.0.1"
		Session.join_lan(ip)
	Session.view_changed.connect(_on_view)
	await get_tree().create_timer(120.0).timeout
	print(role, ": TIMEOUT, games=", games_done)
	get_tree().quit(1)


var _acting := false


func _on_view(v: Dictionary) -> void:
	if v.is_empty():
		return
	var total := 0
	for c in v["counts"]:
		total += int(c)
	total += int(v["deck_count"]) + int(v["discard_count"])
	for pair in v["table"]:
		total += 1 if int(pair[1]) == -1 else 2
	if total != int(v["deck_size"]):
		errors += 1
		print(role, ": BAD COUNT ", total)
	if int(v["counts"][v["me"]]) != (v["hand"] as Array).size():
		errors += 1
		print(role, ": BAD HAND")
	if v["game_over"]:
		games_done += 1
		print(role, ": game over, loser=", v["loser"], " games=", games_done)
		if games_done >= 3:
			await get_tree().create_timer(1.0).timeout
			print(role, ": DONE errors=", errors)
			get_tree().quit(0 if errors == 0 else 1)
		elif role == "host":
			await get_tree().create_timer(0.5).timeout
			Session.restart()
		return
	if _acting:
		return
	_acting = true
	await get_tree().create_timer(0.05).timeout
	_acting = false
	var lg: Dictionary = Session.view["legal"]
	if (lg["attack"] as Array).size() > 0 and ((Session.view["table"] as Array).is_empty() or randf() < 0.5):
		Session.act({"type": "attack", "card": lg["attack"][0]})
	elif (lg["transfer"] as Array).size() > 0 and randf() < 0.5:
		Session.act({"type": "transfer", "card": lg["transfer"][0]})
	elif (lg["defend"] as Dictionary).size() > 0:
		var c = (lg["defend"] as Dictionary).keys()[0]
		Session.act({"type": "defend", "card": c, "idx": lg["defend"][c][0]})
	elif lg["pass"]:
		Session.act({"type": "pass"})
	elif lg["take"]:
		Session.act({"type": "take"})
