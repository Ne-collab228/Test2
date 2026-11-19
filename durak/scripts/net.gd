extends Node
## Транспорт для сетевой игры: Wi-Fi (TCP + поиск по UDP-широковещанию) и Bluetooth
## (через Android-плагин DurakBluetooth). Сообщения — словари, сериализуются var_to_bytes.

signal peer_joined(id: int)
signal peer_left(id: int)
signal message(id: int, data: Dictionary)  # на хосте id — клиент; на клиенте id = 0
signal connected
signal connection_failed(reason: String)
signal disconnected
signal hosts_changed
signal bt_devices_changed
signal bt_error(text: String)

const PORT := 47474
const DISCOVERY_PORT := 47475
const MAGIC := "DURAK-LAN-1"
const PROTOCOL := 1

enum Transport { NONE, LAN, BT }

var transport := Transport.NONE
var is_host := false
var beacon_info := {}

# --- Wi-Fi: хост
var _server: TCPServer
var _clients := {}  # id -> {"peer": StreamPeerTCP, "buf": PackedByteArray}
var _next_id := 1
var _beacon: PacketPeerUDP
var _beacon_timer := 0.0
# --- Wi-Fi: клиент
var _conn: StreamPeerTCP
var _conn_buf := PackedByteArray()
var _connect_deadline := 0.0
var _was_connected := false
# --- Wi-Fi: поиск
var _listener: PacketPeerUDP
var hosts := {}  # ip -> {"info": Dictionary, "time": float}
var _time := 0.0
# --- Bluetooth
var bt: Object = null
var bt_devices := {}  # address -> name
var _bt_peers := {}  # id -> true


func _ready() -> void:
	if Engine.has_singleton("DurakBluetooth"):
		bt = Engine.get_singleton("DurakBluetooth")
		bt.connect("bt_peer_connected", _on_bt_peer_connected)
		bt.connect("bt_peer_disconnected", _on_bt_peer_disconnected)
		bt.connect("bt_message", _on_bt_message)
		bt.connect("bt_device_found", _on_bt_device_found)
		bt.connect("bt_error", func(t): bt_error.emit(t))
		bt.connect("bt_connected_to_host", _on_bt_connected_to_host)
		bt.connect("bt_connect_failed", func(t): _fail(t))


func bt_supported() -> bool:
	return bt != null and bt.isAvailable()


# ================================================================ общий API

func stop() -> void:
	if transport == Transport.LAN:
		for id in _clients:
			_clients[id]["peer"].disconnect_from_host()
		_clients.clear()
		if _server:
			_server.stop()
		_server = null
		if _beacon:
			_beacon.close()
		_beacon = null
		if _conn:
			_conn.disconnect_from_host()
		_conn = null
	elif transport == Transport.BT and bt:
		bt.closeAll()
	_bt_peers.clear()
	transport = Transport.NONE
	is_host = false
	_was_connected = false


func send(id: int, data: Dictionary) -> void:
	var bytes := var_to_bytes(data)
	if transport == Transport.LAN:
		if is_host:
			if _clients.has(id):
				_put_frame(_clients[id]["peer"], bytes)
		elif _conn:
			_put_frame(_conn, bytes)
	elif transport == Transport.BT and bt:
		bt.send(id, Marshalls.raw_to_base64(bytes))


func send_host(data: Dictionary) -> void:
	send(0, data)


func peer_ids() -> Array:
	if transport == Transport.LAN:
		return _clients.keys()
	return _bt_peers.keys()


func kick(id: int) -> void:
	if transport == Transport.LAN and _clients.has(id):
		_clients[id]["peer"].disconnect_from_host()
		_clients.erase(id)
	elif transport == Transport.BT and bt:
		bt.disconnectPeer(id)
		_bt_peers.erase(id)


# ================================================================ Wi-Fi

func host_lan(info: Dictionary) -> String:
	stop()
	_server = TCPServer.new()
	var err := _server.listen(PORT)
	if err != OK:
		_server = null
		return "Не удалось открыть порт %d (ошибка %d)" % [PORT, err]
	_beacon = PacketPeerUDP.new()
	_beacon.set_broadcast_enabled(true)
	beacon_info = info
	transport = Transport.LAN
	is_host = true
	if bt:
		bt.acquireMulticastLock()
	return ""


func join_lan(ip: String) -> void:
	stop()
	_conn = StreamPeerTCP.new()
	_conn_buf = PackedByteArray()
	var err := _conn.connect_to_host(ip, PORT)
	if err != OK:
		_conn = null
		_fail("Не удалось подключиться к %s" % ip)
		return
	transport = Transport.LAN
	is_host = false
	_was_connected = false
	_connect_deadline = _time + 6.0


func start_discovery() -> void:
	stop_discovery()
	hosts.clear()
	_listener = PacketPeerUDP.new()
	_listener.set_broadcast_enabled(true)
	var err := _listener.bind(DISCOVERY_PORT)
	if err != OK:
		push_warning("UDP bind failed: %d" % err)
	if bt:
		bt.acquireMulticastLock()


func stop_discovery() -> void:
	if _listener:
		_listener.close()
	_listener = null


func local_ips() -> Array:
	var res := []
	for ip in IP.get_local_addresses():
		if ip.count(".") == 3 and not ip.begins_with("127.") and not ip.begins_with("169.254."):
			res.append(ip)
	return res


func _broadcast_targets() -> Array:
	var res := ["255.255.255.255"]
	for ip in local_ips():
		var parts: PackedStringArray = ip.split(".")
		res.append("%s.%s.%s.255" % [parts[0], parts[1], parts[2]])
	return res


func _process(delta: float) -> void:
	_time += delta
	if transport == Transport.LAN:
		if is_host:
			_poll_lan_host(delta)
		else:
			_poll_lan_client()
	if _listener:
		var changed := false
		while _listener.get_available_packet_count() > 0:
			var pkt := _listener.get_packet()
			var ip := _listener.get_packet_ip()
			var info = bytes_to_var(pkt) if pkt.size() < 4096 else null
			if info is Dictionary and info.get("magic", "") == MAGIC:
				if not hosts.has(ip) or hosts[ip]["info"] != info:
					changed = true
				hosts[ip] = {"info": info, "time": _time}
		for ip in hosts.keys():
			if _time - hosts[ip]["time"] > 4.0:
				hosts.erase(ip)
				changed = true
		if changed:
			hosts_changed.emit()


func _poll_lan_host(delta: float) -> void:
	if _server == null:
		return
	while _server.is_connection_available():
		var peer := _server.take_connection()
		peer.set_no_delay(true)
		var id := _next_id
		_next_id += 1
		_clients[id] = {"peer": peer, "buf": PackedByteArray()}
		peer_joined.emit(id)
	for id in _clients.keys():
		var c: Dictionary = _clients[id]
		var peer: StreamPeerTCP = c["peer"]
		peer.poll()
		if peer.get_status() != StreamPeerTCP.STATUS_CONNECTED:
			_clients.erase(id)
			peer_left.emit(id)
			continue
		c["buf"] = _read_frames(peer, c["buf"], id)
	_beacon_timer -= delta
	if _beacon_timer <= 0.0 and _beacon:
		_beacon_timer = 1.0
		var info := beacon_info.duplicate()
		info["magic"] = MAGIC
		var pkt := var_to_bytes(info)
		for target in _broadcast_targets():
			_beacon.set_dest_address(target, DISCOVERY_PORT)
			_beacon.put_packet(pkt)


func _poll_lan_client() -> void:
	if _conn == null:
		return
	_conn.poll()
	var st := _conn.get_status()
	if st == StreamPeerTCP.STATUS_CONNECTING:
		if _time > _connect_deadline:
			_fail("Хост не отвечает")
		return
	if st == StreamPeerTCP.STATUS_CONNECTED:
		if not _was_connected:
			_was_connected = true
			_conn.set_no_delay(true)
			connected.emit()
		_conn_buf = _read_frames(_conn, _conn_buf, 0)
		return
	# Ошибка или разрыв
	if _was_connected:
		stop()
		disconnected.emit()
	else:
		_fail("Не удалось подключиться")


func _fail(reason: String) -> void:
	stop()
	connection_failed.emit(reason)


func _put_frame(peer: StreamPeerTCP, bytes: PackedByteArray) -> void:
	var f := PackedByteArray()
	f.resize(4)
	f.encode_u32(0, bytes.size())
	f.append_array(bytes)
	peer.put_data(f)


func _read_frames(peer: StreamPeerTCP, buf: PackedByteArray, id: int) -> PackedByteArray:
	var avail := peer.get_available_bytes()
	if avail > 0:
		var res := peer.get_data(avail)
		if res[0] == OK:
			buf.append_array(res[1])
	while buf.size() >= 4:
		var size := buf.decode_u32(0)
		if size > 1 << 20:
			buf = PackedByteArray()  # мусор — сбрасываем
			break
		if buf.size() < 4 + size:
			break
		var payload := buf.slice(4, 4 + size)
		buf = buf.slice(4 + size)
		var data = bytes_to_var(payload)
		if data is Dictionary:
			message.emit(id, data)
	return buf


# ================================================================ Bluetooth

func host_bt(server_name: String) -> String:
	stop()
	if not bt_supported():
		return "Bluetooth недоступен на этом устройстве"
	if not bt.hasPermissions():
		bt.requestPermissions()
		return "Разрешите доступ к Bluetooth и нажмите ещё раз"
	if not bt.isEnabled():
		bt.requestEnable()
		return "Включите Bluetooth и нажмите ещё раз"
	if not bt.startServer(server_name):
		return "Не удалось запустить Bluetooth-сервер"
	transport = Transport.BT
	is_host = true
	bt.makeDiscoverable()
	return ""


func bt_prepare() -> String:
	if not bt_supported():
		return "Bluetooth недоступен на этом устройстве"
	if not bt.hasPermissions():
		bt.requestPermissions()
		return "Разрешите доступ к Bluetooth"
	if not bt.isEnabled():
		bt.requestEnable()
		return "Включите Bluetooth"
	return ""


func bt_scan() -> void:
	bt_devices.clear()
	if not bt_supported():
		return
	var paired = JSON.parse_string(bt.getPairedDevices())
	if paired is Array:
		for d in paired:
			bt_devices[d["address"]] = "%s (сопряжено)" % d["name"]
	bt_devices_changed.emit()
	bt.startDiscovery()


func join_bt(address: String) -> void:
	stop()
	if not bt_supported():
		_fail("Bluetooth недоступен")
		return
	bt.cancelDiscovery()
	transport = Transport.BT
	is_host = false
	bt.connectTo(address)


func _on_bt_peer_connected(id: int, _name: String) -> void:
	_bt_peers[id] = true
	peer_joined.emit(id)


func _on_bt_peer_disconnected(id: int) -> void:
	if is_host:
		_bt_peers.erase(id)
		peer_left.emit(id)
	elif transport == Transport.BT:
		stop()
		disconnected.emit()


func _on_bt_message(id: int, b64: String) -> void:
	var data = bytes_to_var(Marshalls.base64_to_raw(b64))
	if data is Dictionary:
		message.emit(id, data)


func _on_bt_device_found(address: String, dev_name: String) -> void:
	if not bt_devices.has(address):
		bt_devices[address] = dev_name if dev_name != "" else address
		bt_devices_changed.emit()


func _on_bt_connected_to_host() -> void:
	_was_connected = true
	connected.emit()
