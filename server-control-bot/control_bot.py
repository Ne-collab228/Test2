"""Telegram-бот «пульт» для сервера (headless CPU-контейнер).
Мониторинг, команды, скриншоты. Кнопки снизу + автоподсказки команд.
БЕЗОПАСНОСТЬ: команды только от авторизованного пользователя (/auth <секрет>)."""
import os, json, time, html, shlex, subprocess, secrets, urllib.request, urllib.parse, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT_BROWSER = "/opt/pw-browsers/chromium"

def _read(name, default=""):
    try:
        return open(os.path.join(HERE, name)).read().strip()
    except Exception:
        return default

def _write(name, val):
    with open(os.path.join(HERE, name), "w") as f:
        f.write(str(val))

TOKEN = os.environ.get("CTL_TOKEN", "").strip() or _read("control_bot_token.txt")
API = f"https://api.telegram.org/bot{TOKEN}"

SECRET = _read("control_secret.txt")
if not SECRET:
    SECRET = secrets.token_hex(4)
    _write("control_secret.txt", SECRET)

def owner():
    o = _read("control_owner.txt")
    return int(o) if o else None

# ---------- клавиатура и подсказки ----------
KB = json.dumps({
    "keyboard": [
        ["📊 Статус", "ℹ️ Инфо"],
        ["🧾 Процессы", "🤖 Сервисы"],
        ["🧠 Память", "🗄 Диск"],
        ["🌐 Сеть", "⚡ Диск-тест"],
        ["📸 Скриншот", "💻 Команда"],
        ["⏱ Аптайм", "❓ Помощь"],
    ],
    "resize_keyboard": True, "is_persistent": True,
}, ensure_ascii=False)

BTN = {
    "📊 Статус": "/stats", "ℹ️ Инфо": "/info", "🧾 Процессы": "/ps",
    "🤖 Сервисы": "/bots", "🧠 Память": "/free", "🗄 Диск": "/df",
    "🌐 Сеть": "/net", "⚡ Диск-тест": "/speed", "⏱ Аптайм": "/uptime",
    "❓ Помощь": "/help",
}
HINT = {
    "📸 Скриншот": "Введи: /shot <адрес>\nНапример: /shot ya.ru",
    "💻 Команда": "Введи: /sh <команда>\nНапример: /sh ls -la",
}

COMMANDS = [
    ("stats", "Загрузка: CPU, память, диск"),
    ("info", "Железо и ОС сервера"),
    ("ps", "Топ процессов по нагрузке"),
    ("bots", "Что сейчас запущено на сервере"),
    ("free", "Память подробно"),
    ("df", "Диск подробно"),
    ("net", "Проверка интернета и задержек"),
    ("speed", "Тест скорости диска"),
    ("uptime", "Аптайм и нагрузка"),
    ("temp", "Температура процессора"),
    ("sh", "Выполнить команду: /sh <cmd>"),
    ("tail", "Последние строки файла: /tail <path>"),
    ("shot", "Скриншот сайта: /shot <url>"),
    ("help", "Список всех команд"),
]

HELP = (
    "🎛 ПУЛЬТ СЕРВЕРА — что умею:\n\n"
    "📊 /stats — загрузка: процессор, память, диск, нагрузка\n"
    "ℹ️ /info — железо и ОС: модель CPU, ядра, RAM, ядро Linux\n"
    "🧾 /ps — какие процессы сильнее всего грузят сервер\n"
    "🤖 /bots — что запущено (чат-бот, пульт, обучение)\n"
    "🧠 /free — память: занято / свободно / всего\n"
    "🗄 /df — диск: занято / свободно / всего\n"
    "🌐 /net — проверка интернета и задержки до сайтов\n"
    "⚡ /speed — тест скорости диска (запись/чтение)\n"
    "⏱ /uptime — сколько работает сервер + нагрузка\n"
    "🌡 /temp — температура процессора\n"
    "💻 /sh <команда> — выполнить в терминале\n"
    "      напр. /sh ls -la   ·   /sh cat bot.log\n"
    "📸 /shot <url> — прислать скриншот сайта\n"
    "      напр. /shot ya.ru\n"
    "📄 /tail <файл> — последние строки файла/лога\n"
    "      напр. /tail bot.log\n\n"
    "Снизу есть кнопки, а при вводе «/» всплывают подсказки."
)

# ---------- Telegram API ----------
def api(method, **params):
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(f"{API}/{method}", data=data)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except Exception as e:
        print("[api err]", method, e, flush=True)
        return None

def send(chat_id, text, kb=False):
    text = text or "(пусто)"
    params = {"chat_id": chat_id, "text": text}
    if kb:
        params["reply_markup"] = KB
    api("sendMessage", **params)

def send_mono(chat_id, text):
    text = text or "(пусто)"
    for i in range(0, len(text), 3500):
        api("sendMessage", chat_id=chat_id,
            text=f"<pre>{html.escape(text[i:i+3500])}</pre>", parse_mode="HTML")

def send_photo(chat_id, path, caption=""):
    b = "----ctl" + secrets.token_hex(8)
    with open(path, "rb") as f:
        img = f.read()
    body = b""
    def field(n, v):
        return (f"--{b}\r\nContent-Disposition: form-data; name=\"{n}\"\r\n\r\n{v}\r\n").encode()
    body += field("chat_id", chat_id)
    if caption:
        body += field("caption", caption)
    body += (f"--{b}\r\nContent-Disposition: form-data; name=\"photo\"; "
             f"filename=\"shot.png\"\r\nContent-Type: image/png\r\n\r\n").encode()
    body += img + b"\r\n" + f"--{b}--\r\n".encode()
    req = urllib.request.Request(f"{API}/sendPhoto", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={b}"})
    try:
        urllib.request.urlopen(req, timeout=90)
    except Exception as e:
        send(chat_id, f"⚠️ не отправил фото: {e}")

# ---------- системное ----------
def sh(cmd, timeout=40):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        out = (r.stdout or "") + (r.stderr or "")
        return out.rstrip() or f"(код {r.returncode}, вывода нет)"
    except subprocess.TimeoutExpired:
        return f"таймаут {timeout}с"
    except Exception as e:
        return f"ошибка: {e}"

def _bar(pct, n=12):
    pct = max(0.0, min(100.0, pct))
    f = int(round(pct / 100 * n))
    return "█" * f + "░" * (n - f)

def _cpu_snap():
    v = list(map(int, open("/proc/stat").readline().split()[1:]))
    idle = v[3] + (v[4] if len(v) > 4 else 0)
    return idle, sum(v)

def _cpu_pct():
    i1, t1 = _cpu_snap(); time.sleep(0.4); i2, t2 = _cpu_snap()
    dt = t2 - t1
    return 0.0 if dt <= 0 else (1 - (i2 - i1) / dt) * 100

def _up():
    return float(open("/proc/uptime").read().split()[0])

def _fmt_up(sec):
    sec = int(sec); d, sec = divmod(sec, 86400); h, sec = divmod(sec, 3600); m = sec // 60
    out = []
    if d: out.append(f"{d} д")
    if h: out.append(f"{h} ч")
    out.append(f"{m} мин")
    return " ".join(out)

def _mem():
    mi = {}
    for line in open("/proc/meminfo"):
        k, _, rest = line.partition(":")
        mi[k] = int(rest.split()[0])
    tot = mi["MemTotal"] / 2**20
    avail = mi.get("MemAvailable", mi["MemFree"]) / 2**20
    return tot - avail, tot, avail

def _disk():
    r = sh("df -B1 --output=used,avail / | tail -1").split()
    used = int(r[0]) / 2**30; avail = int(r[1]) / 2**30
    return used, used + avail, avail

def _load():
    return open("/proc/loadavg").read().split()[:3]

def _running(name):
    r = subprocess.run("ps -eo args", shell=True, capture_output=True, text=True)
    return any(name in ln for ln in r.stdout.splitlines()[1:])

def stats():
    cores = os.cpu_count() or 4
    la = _load(); la1 = float(la[0])
    lvl = "низкая" if la1 < cores * 0.7 else ("средняя" if la1 < cores else "высокая")
    cpu = _cpu_pct()
    mu, mt, ma = _mem(); mpct = 100 * mu / mt
    du, dt, da = _disk(); dpct = 100 * du / dt if dt else 0
    return (
        f"🖥 СЕРВЕР   {cores} ядра · аптайм {_fmt_up(_up())}\n\n"
        f"⚙️ Процессор\n{_bar(cpu)}  {cpu:.0f}%\n\n"
        f"🧠 Память\n{_bar(mpct)}  {mpct:.0f}%\n"
        f"занято {mu:.1f} из {mt:.0f} ГБ · свободно {ma:.1f} ГБ\n\n"
        f"🗄 Диск\n{_bar(dpct)}  {dpct:.0f}%\n"
        f"занято {du:.0f} из {dt:.0f} ГБ · свободно {da:.0f} ГБ\n\n"
        f"📈 Нагрузка {la1:.2f} ({lvl})\n"
        f"за 1 / 5 / 15 мин: {la[0]} · {la[1]} · {la[2]}"
    )

def info():
    model = sh("grep -m1 'model name' /proc/cpuinfo | cut -d: -f2").strip() or "?"
    kernel = sh("uname -r")
    osn = sh(". /etc/os-release 2>/dev/null; echo $PRETTY_NAME")
    mu, mt, ma = _mem(); du, dt, da = _disk()
    return (
        f"ℹ️ О СЕРВЕРЕ\n\n"
        f"🧩 Процессор:\n{model}\n{os.cpu_count()} ядра (виртуальных)\n\n"
        f"🧠 Память: {mt:.0f} ГБ\n"
        f"🗄 Диск: {dt:.0f} ГБ (свободно {da:.0f})\n"
        f"🐧 ОС: {osn}\nЯдро: {kernel}\n"
        f"🎮 Видеокарта: нет\n"
        f"⏱ Аптайм: {_fmt_up(_up())}"
    )

def free_view():
    mu, mt, ma = _mem(); pct = 100 * mu / mt
    return (f"🧠 ПАМЯТЬ\n{_bar(pct)}  {pct:.0f}%\n\n"
            f"Занято:   {mu:.1f} ГБ\nСвободно: {ma:.1f} ГБ\nВсего:    {mt:.0f} ГБ")

def df_view():
    du, dt, da = _disk(); pct = 100 * du / dt if dt else 0
    return (f"🗄 ДИСК\n{_bar(pct)}  {pct:.0f}%\n\n"
            f"Занято:   {du:.0f} ГБ\nСвободно: {da:.0f} ГБ\nВсего:    {dt:.0f} ГБ")

def uptime_view():
    la = _load()
    return (f"⏱ Аптайм: {_fmt_up(_up())}\n"
            f"📈 Нагрузка (1/5/15 мин): {la[0]} · {la[1]} · {la[2]}")

def ps_top():
    raw = sh("ps -eo %cpu,%mem,comm --sort=-%cpu --no-headers | head -8")
    rows = [f"{'ПРОЦЕСС':<16}{'CPU':>6}{'ОЗУ':>6}"]
    for ln in raw.splitlines():
        p = ln.split(None, 2)
        if len(p) == 3:
            cpu, mem, name = p
            rows.append(f"{name[:15]:<16}{float(cpu):>5.1f}%{float(mem):>5.1f}%")
    return "\n".join(rows)

def gemma_line():
    if not _running("telegram_bot.py"):
        return "⚪️ остановлен"
    state = _read("gemma_state.txt")
    if state == "loaded":
        return "🟢 модель загружена"
    return "🟡 запущен, модель выгружена"

def bots_status():
    def mark(x): return "🟢 работает" if x else "⚪️ остановлен"
    return (
        "🤖 СЕРВИСЫ НА СЕРВЕРЕ\n\n"
        f"Чат-бот (Gemma):  {gemma_line()}\n"
        f"Пульт (я):        {mark(_running('control_bot.py'))}\n"
        f"Обучение модели:  {mark(_running('train.py'))}"
    )

def net():
    sites = [("Google", "https://www.google.com/generate_204"),
             ("Telegram API", "https://api.telegram.org"),
             ("HuggingFace", "https://huggingface.co")]
    out = ["🌐 ИНТЕРНЕТ\n"]
    for name, url in sites:
        t0 = time.time()
        try:
            urllib.request.urlopen(url, timeout=10)
            out.append(f"✅ {name} — {(time.time()-t0)*1000:.0f} мс")
        except urllib.error.HTTPError:
            out.append(f"✅ {name} — {(time.time()-t0)*1000:.0f} мс")
        except Exception:
            out.append(f"❌ {name} — недоступен")
    return "\n".join(out)

def temp():
    zones = sh("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | head -1")
    if zones.strip().isdigit():
        return f"🌡 Температура CPU: {int(zones)/1000:.1f}°C"
    return ("🌡 Температура CPU недоступна.\n"
            "Это виртуальная машина — хост не пробрасывает датчик температуры внутрь. Норма для облака.")

def speed():
    w = sh("dd if=/dev/zero of=/tmp/_spd.bin bs=1M count=256 oflag=direct 2>&1 | tail -1")
    r = sh("dd if=/tmp/_spd.bin of=/dev/null bs=1M iflag=direct 2>&1 | tail -1")
    sh("rm -f /tmp/_spd.bin")
    def mbps(s):
        for tok in s.replace(",", " ").split():
            pass
        return s.split(",")[-1].strip() if "," in s else s
    return f"⚡ СКОРОСТЬ ДИСКА (256 МБ)\n\nЗапись: {mbps(w)}\nЧтение: {mbps(r)}"

def tail_file(arg):
    arg = arg.strip()
    if not arg:
        return "Напиши путь: /tail <файл>"
    return sh(f"tail -n 25 {shlex.quote(arg)}")

def screenshot(url, out):
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        br = p.chromium.launch(executable_path=SHOT_BROWSER, args=["--no-sandbox", "--headless=new"])
        pg = br.new_page(viewport={"width": 1280, "height": 900}, device_scale_factor=1)
        pg.goto(url, timeout=30000, wait_until="load")
        pg.wait_for_timeout(1500)
        pg.screenshot(path=out, full_page=False)
        br.close()
    return url

# ---------- обработка ----------
def authorized(uid):
    return owner() is not None and uid == owner()

def handle(msg):
    chat_id = msg["chat"]["id"]
    uid = msg["from"]["id"]
    text = msg.get("text", "")
    if not text:
        return

    if text.startswith("/start"):
        send(chat_id, "Привет! Я пульт управления сервером 🎛\n\n"
                      "Сначала авторизуйся:\n/auth <секрет>\n(секрет тебе дал хозяин)", kb=True)
        return
    if text.startswith("/auth"):
        parts = text.split(maxsplit=1)
        if len(parts) == 2 and parts[1].strip() == SECRET:
            if owner() is None or owner() == uid:
                _write("control_owner.txt", uid)
                send(chat_id, "✅ Готово, ты авторизован!\n\n" + HELP, kb=True)
            else:
                send(chat_id, "⛔ У бота уже есть владелец.")
        else:
            send(chat_id, "⛔ Неверный секрет.")
        return

    if not authorized(uid):
        send(chat_id, "⛔ Доступ запрещён.\nАвторизуйся: /auth <секрет>")
        return

    # кнопки снизу
    if text in HINT:
        send(chat_id, HINT[text]); return
    if text in BTN:
        text = BTN[text]

    if text.startswith("/help"):
        send(chat_id, HELP, kb=True)
    elif text.startswith("/stats"):
        send(chat_id, stats())
    elif text.startswith("/info"):
        send(chat_id, info())
    elif text.startswith("/ps"):
        send_mono(chat_id, ps_top())
    elif text.startswith("/bots"):
        send(chat_id, bots_status())
    elif text.startswith("/free"):
        send(chat_id, free_view())
    elif text.startswith("/df"):
        send(chat_id, df_view())
    elif text.startswith("/uptime"):
        send(chat_id, uptime_view())
    elif text.startswith("/net"):
        api("sendChatAction", chat_id=chat_id, action="typing")
        send(chat_id, net())
    elif text.startswith("/temp"):
        send(chat_id, temp())
    elif text.startswith("/speed"):
        api("sendChatAction", chat_id=chat_id, action="typing")
        send(chat_id, speed())
    elif text.startswith("/tail"):
        send_mono(chat_id, tail_file(text[5:]))
    elif text.startswith("/sh"):
        cmd = text[3:].strip()
        if not cmd:
            send(chat_id, "Напиши команду после /sh:\n/sh ls -la")
        else:
            api("sendChatAction", chat_id=chat_id, action="typing")
            send_mono(chat_id, f"$ {cmd}\n\n{sh(cmd)}")
    elif text.startswith("/shot"):
        parts = text.split(maxsplit=1)
        if len(parts) != 2:
            send(chat_id, "Напиши адрес:\n/shot ya.ru")
        else:
            api("sendChatAction", chat_id=chat_id, action="upload_photo")
            out = os.path.join(HERE, "shot.png")
            try:
                final = screenshot(parts[1].strip(), out)
                send_photo(chat_id, out, caption="📸 " + final)
            except Exception as e:
                send(chat_id, f"⚠️ скриншот не вышел: {e}")
    else:
        send(chat_id, "Не знаю такой команды. Жми кнопку снизу или /help.")

def main():
    me = api("getMe")
    if not me or not me.get("ok"):
        print("[ctl] неверный токен?", me, flush=True); return
    api("setMyCommands", commands=json.dumps(
        [{"command": c, "description": d} for c, d in COMMANDS], ensure_ascii=False))
    print(f"[ctl] запущен как @{me['result']['username']} | секрет: {SECRET}", flush=True)
    offset = None
    while True:
        upd = api("getUpdates", offset=offset, timeout=30)
        if not upd or not upd.get("ok"):
            time.sleep(2); continue
        for u in upd["result"]:
            offset = u["update_id"] + 1
            if "message" in u:
                try:
                    handle(u["message"])
                except Exception as e:
                    print("[handle err]", e, flush=True)

if __name__ == "__main__":
    main()
