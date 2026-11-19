extends Node
## Снимки экрана для проверки интерфейса: godot -- --shots <папка>

var main: Control
var out := "/tmp"


func run() -> void:
	var args := OS.get_cmdline_user_args()
	out = args[args.find("--shots") + 1] if args.find("--shots") + 1 < args.size() else "/tmp"
	await _wait(0.5)
	await _shot("01_menu")
	main.show_bot_setup()
	await _shot("02_bots")
	main.show_lan()
	await _shot("03_lan")
	main.show_rules()
	await _shot("04_rules")
	Session.settings["bots"] = 3
	Session.settings["mode"] = 2
	Session.settings["level"] = 2
	Session.start_local()
	await _wait(3.0)
	await _shot("05_game_start")
	# играем за человека простым ботом, пока не наберём интересную позицию
	var shots := 0
	for i in 400:
		var g: DurakGame = Session.game
		if g == null or g.game_over:
			break
		var a := DurakBot.decide(g, 0, 1, RandomNumberGenerator.new())
		if not a.is_empty():
			Session.act(a)
		await _wait(0.35)
		if g.table.size() >= 3 and shots < 3:
			shots += 1
			await _shot("06_game_table_%d" % shots)
	await _wait(2.5)
	await _shot("07_game_over")
	get_tree().quit()


func _wait(t: float) -> void:
	await get_tree().create_timer(t).timeout


func _shot(name: String) -> void:
	await _wait(0.6)
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	img.save_png(out.path_join(name + ".png"))
	print("shot ", name)
