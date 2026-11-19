extends Node
## Управляет партией: локальная игра с ботами, хост сетевой игры или клиент.
## Хост (и локальная игра) держит настоящий DurakGame; клиенты видят только view.

signal view_changed(view: Dictionary)
signal lobby_changed(players: Array)
signal game_started
signal left(reason: String)
signal notice(text: String)

enum Role { NONE, LOCAL, HOST, CLIENT }
enum State { IDLE, LOBBY, GAME }

var role := Role.NONE
var state := State.IDLE
var settings := {"name": "Игрок", "mode": 0, "deck": 36, "bots": 1, "level": 1}
var game: DurakGame
var seats: Array = []  # [{"name", "kind": "me"/"remote"/"bot", "id", "level"}]
var my_seat := 0
var view := {}
var lobby: Array = []  # хост: [{"id", "name"}]; id 0 — сам хост
var lobby_names: Array = []  # клиент: имена в лобби
var game_id := 0

var _rng := RandomNumberGenerator.new()
var _bot_timer := 0.0
var _bot_task := -1
var _bot_task_seq := -1
var _bot_result := {}
var _last_round_seen := 0

const SAVE_PATH := "user://settings.cfg"


func _ready() -> void:
	_rng.randomize()
	_load_settings()
	Net.peer_joined.connect(_on_peer_joined)
	Net.peer_left.connect(_on_peer_left)
	Net.message.connect(_on_message)
	Net.connected.connect(_on_connected)
	Net.disconnected.connect(func(): _leave("Соединение с хостом потеряно"))
	Net.connection_failed.connect(func(r): _leave(r))


static func max_players(deck: int) -> int:
	return 4 if deck == 24 else 6


# ================================================================ настройки

func _load_settings() -> void:
	var cf := ConfigFile.new()
	if cf.load(SAVE_PATH) == OK:
		for k in settings:
			settings[k] = cf.get_value("s", k, settings[k])


func save_settings() -> void:
	var cf := ConfigFile.new()
	for k in settings:
		cf.set_value("s", k, settings[k])
	cf.save(SAVE_PATH)


# ================================================================ запуск

func start_local() -> void:
	reset()
	role = Role.LOCAL
	seats = [{"name": settings["name"], "kind": "me", "id": 0, "level": 0}]
	var bots: int = clampi(settings["bots"], 1, max_players(settings["deck"]) - 1)
	for i in bots:
		seats.append({"name": _bot_name(i), "kind": "bot", "id": -1, "level": settings["level"]})
	my_seat = 0
	_new_game()


func host(transport: int) -> String:
	reset()
	var err := ""
	if transport == Net.Transport.LAN:
		err = Net.host_lan(_beacon_info())
	else:
		err = Net.host_bt("Дурак: " + str(settings["name"]))
	if err != "":
		return err
	role = Role.HOST
	state = State.LOBBY
	lobby = [{"id": 0, "name": settings["name"]}]
	_broadcast_lobby()
	return ""


func join_lan(ip: String) -> void:
	reset()
	role = Role.CLIENT
	Net.join_lan(ip)


func join_bt(address: String) -> void:
	reset()
	role = Role.CLIENT
	Net.join_bt(address)


func start_hosted_game() -> void:
	if role != Role.HOST:
		return
	var maxp := max_players(settings["deck"])
	seats = []
	for p in lobby:
		if seats.size() >= maxp:
			break
		seats.append({"name": p["name"], "kind": "me" if p["id"] == 0 else "remote", "id": p["id"], "level": 0})
	var bots: int = clampi(settings["bots"], 0, maxp - seats.size())
	if seats.size() + bots < 2:
		bots = 1
	for i in bots:
		seats.append({"name": _bot_name(i), "kind": "bot", "id": -1, "level": settings["level"]})
	my_seat = 0
	for i in seats.size():
		if seats[i]["kind"] == "remote":
			Net.send(seats[i]["id"], {"t": "start", "seat": i})
	_new_game()


func restart() -> void:
	if role == Role.LOCAL or role == Role.HOST:
		_new_game()


func reset() -> void:
	if role == Role.HOST:
		for id in Net.peer_ids():
			Net.send(id, {"t": "closed"})
	Net.stop()
	Net.stop_discovery()
	role = Role.NONE
	state = State.IDLE
	game = null
	seats = []
	view = {}
	lobby = []
	lobby_names = []
	_bot_result = {}
	_bot_task_seq = -1


func _new_game() -> void:
	var names := []
	for s in seats:
		names.append(s["name"])
	game = DurakGame.new()
	game.setup(names, settings["mode"], settings["deck"])
	game_id += 1
	state = State.GAME
	_last_round_seen = game.round_no
	_bot_timer = 1.2
	game_started.emit()
	_publish()


func _bot_name(i: int) -> String:
	var pool := ["Вася", "Маша", "Петя", "Оля", "Гоша", "Света", "Коля"]
	return pool[i % pool.size()]


func _beacon_info() -> Dictionary:
	return {
		"name": settings["name"], "mode": settings["mode"], "deck": settings["deck"],
		"players": lobby.size(), "max": max_players(settings["deck"]), "proto": Net.PROTOCOL,
		"open": state == State.LOBBY,
	}


# ================================================================ ходы

## Действие текущего игрока (из интерфейса).
func act(a: Dictionary) -> void:
	if state != State.GAME:
		return
	if role == Role.CLIENT:
		Net.send_host({"t": "act", "a": a})
	elif game:
		_apply(my_seat, a)


func _apply(seat: int, a: Dictionary) -> void:
	if game == null or game.game_over:
		return
	if game.apply_action(seat, a):
		_bot_timer = maxf(_bot_timer, _rng.randf_range(0.45, 0.8))
		_publish()


func _publish() -> void:
	if game == null:
		return
	if game.round_no != _last_round_seen:
		_last_round_seen = game.round_no
		_bot_timer = 1.4  # пауза, чтобы все увидели итог раунда
	for i in seats.size():
		var s: Dictionary = seats[i]
		if s["kind"] == "me":
			view = _make_view(i)
			view_changed.emit(view)
		elif s["kind"] == "remote":
			Net.send(s["id"], {"t": "view", "v": _make_view(i)})


func _make_view(seat: int) -> Dictionary:
	var v := game.get_view(seat)
	v["game_id"] = game_id
	var kinds := []
	for s in seats:
		kinds.append(s["kind"])
	v["kinds"] = kinds
	v["can_restart"] = role != Role.CLIENT and seat == my_seat
	return v


func _process(delta: float) -> void:
	if role == Role.HOST and state == State.LOBBY:
		Net.beacon_info = _beacon_info()
	if game == null or role == Role.CLIENT or game.game_over:
		return
	if _bot_task >= 0:
		if not WorkerThreadPool.is_task_completed(_bot_task):
			return
		WorkerThreadPool.wait_for_task_completion(_bot_task)
		_bot_task = -1
		var res := _bot_result
		_bot_result = {}
		if _bot_task_seq == game.seq and not res.is_empty():
			if game.apply_action(res["seat"], res["a"]):
				_bot_timer = _rng.randf_range(0.55, 1.0)
				_publish()
				return
		_bot_task_seq = -1
	if _bot_timer > 0.0:
		_bot_timer -= delta
		return
	if _bot_task_seq == game.seq:
		return  # ботам в этой позиции нечего делать — ждём человека
	var order := [game.defender] + game.throwers()
	var bot_seats := []
	var levels := []
	for s in order:
		if seats[s]["kind"] == "bot":
			bot_seats.append(s)
			levels.append(seats[s]["level"])
	_bot_task_seq = game.seq
	if bot_seats.is_empty():
		return
	var snapshot := game.clone()
	var seed_value := _rng.randi()
	_bot_task = WorkerThreadPool.add_task(func(): _think(snapshot, bot_seats, levels, seed_value))


func _think(g: DurakGame, bot_seats: Array, levels: Array, seed_value: int) -> void:
	var r := RandomNumberGenerator.new()
	r.seed = seed_value
	for i in bot_seats.size():
		var a := DurakBot.decide(g, bot_seats[i], levels[i], r)
		if not a.is_empty():
			_bot_result = {"seat": bot_seats[i], "a": a}
			return


# ================================================================ сеть

func _on_connected() -> void:
	if role == Role.CLIENT:
		Net.send_host({"t": "hello", "name": settings["name"], "proto": Net.PROTOCOL})


func _on_peer_joined(_id: int) -> void:
	pass  # ждём hello


func _on_peer_left(id: int) -> void:
	if role != Role.HOST:
		return
	if state == State.LOBBY:
		for i in lobby.size():
			if lobby[i]["id"] == id:
				notice.emit("%s отключился" % lobby[i]["name"])
				lobby.remove_at(i)
				break
		_broadcast_lobby()
	elif state == State.GAME:
		for s in seats:
			if s["kind"] == "remote" and s["id"] == id:
				s["kind"] = "bot"
				s["level"] = DurakBot.Level.MEDIUM
				notice.emit("Игрок отключился — его заменил бот")
				_bot_task_seq = -1
				_publish()


func _on_message(id: int, d: Dictionary) -> void:
	var t: String = str(d.get("t", ""))
	if role == Role.HOST:
		match t:
			"hello":
				if state != State.LOBBY or lobby.size() >= max_players(settings["deck"]):
					Net.send(id, {"t": "full"})
					return
				if int(d.get("proto", 0)) != Net.PROTOCOL:
					Net.send(id, {"t": "error", "text": "Разные версии игры"})
					return
				var pname := str(d.get("name", "Игрок")).substr(0, 20).strip_edges()
				if pname == "":
					pname = "Игрок"
				lobby.append({"id": id, "name": pname})
				notice.emit("%s подключился" % pname)
				_broadcast_lobby()
			"act":
				if state != State.GAME or not (d.get("a") is Dictionary):
					return
				for i in seats.size():
					if seats[i]["kind"] == "remote" and seats[i]["id"] == id:
						_apply(i, d["a"])
						return
	elif role == Role.CLIENT:
		match t:
			"lobby":
				state = State.LOBBY
				lobby_names = d.get("players", [])
				settings_from_host = d.get("settings", {})
				lobby_changed.emit(lobby_names)
			"start":
				my_seat = int(d.get("seat", 0))
				state = State.GAME
				game_started.emit()
			"view":
				state = State.GAME
				view = d.get("v", {})
				view_changed.emit(view)
			"full":
				_leave("Игра уже началась или нет мест")
			"closed":
				_leave("Хост закрыл игру")
			"error":
				_leave(str(d.get("text", "Ошибка")))


var settings_from_host := {}


func _broadcast_lobby() -> void:
	var names := []
	for p in lobby:
		names.append(p["name"])
	var msg := {"t": "lobby", "players": names, "settings": {
		"mode": settings["mode"], "deck": settings["deck"], "bots": settings["bots"], "level": settings["level"]}}
	for p in lobby:
		if p["id"] != 0:
			Net.send(p["id"], msg)
	lobby_changed.emit(names)


func _leave(reason: String) -> void:
	var was := role
	reset()
	if was != Role.NONE:
		left.emit(reason)
