"""Telegram-бот «пульт» для сервера (headless CPU-контейнер).
Мониторинг, команды, скриншоты. Кнопки снизу + автоподсказки команд.
БЕЗОПАСНОСТЬ: команды только от авторизованного пользователя (/auth <секрет>)."""
import os, json, time, html, shlex, subprocess, secrets, urllib.request, urllib.parse, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
# путь к Chromium для скриншотов/браузера; переопределяется env SHOT_BROWSER.
# Если файла нет — Playwright возьмёт свой браузер (playwright install chromium).
SHOT_BROWSER = os.environ.get("SHOT_BROWSER", "/opt/pw-browsers/chromium")

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
    ("open", "Открыть сайт в браузере (можно лазить)"),
    ("click", "Кликнуть: /click <текст> или /click x y"),
    ("type", "Ввести текст в поле: /type <текст>"),
    ("enter", "Нажать Enter"),
    ("close", "Закрыть браузер"),
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
    "📸 /shot <url> — скриншот сайта (одно фото)\n"
    "📄 /tail <файл> — последние строки файла/лога\n\n"
    "🌐 БРАУЗЕР (лазить по сайту):\n"
    "/open <url> — открыть сайт\n"
    "/click <текст> — кликнуть по ссылке с этим текстом\n"
    "/click x y — кликнуть по координатам\n"
    "/type <текст> — ввести текст в поле\n"
    "/enter — нажать Enter (поиск)\n"
    "/close — закрыть браузер\n"
    "Под скриншотом — кнопки ⬆️⬇️ скролл, 🔙 назад, 🔄 обновить.\n\n"
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

def _multipart(fields, file_field, img, boundary):
    body = b""
    for n, v in fields:
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{n}\"\r\n\r\n{v}\r\n").encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
             f"filename=\"img.png\"\r\nContent-Type: image/png\r\n\r\n").encode()
    body += img + b"\r\n" + f"--{boundary}--\r\n".encode()
    return body

def send_photo(chat_id, path, caption="", inline=None):
    b = "----ctl" + secrets.token_hex(8)
    img = open(path, "rb").read()
    fields = [("chat_id", chat_id)]
    if caption:
        fields.append(("caption", caption))
    if inline:
        fields.append(("reply_markup", inline))
    body = _multipart(fields, "photo", img, b)
    req = urllib.request.Request(f"{API}/sendPhoto", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={b}"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=90))
        return d["result"]["message_id"] if d.get("ok") else None
    except Exception as e:
        send(chat_id, f"⚠️ не отправил фото: {e}"); return None

def edit_photo(chat_id, mid, path, caption="", inline=None):
    b = "----ctl" + secrets.token_hex(8)
    img = open(path, "rb").read()
    media = {"type": "photo", "media": "attach://photo"}
    if caption:
        media["caption"] = caption
    fields = [("chat_id", chat_id), ("message_id", mid),
              ("media", json.dumps(media, ensure_ascii=False))]
    if inline:
        fields.append(("reply_markup", inline))
    body = _multipart(fields, "photo", img, b)
    req = urllib.request.Request(f"{API}/editMessageMedia", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={b}"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=60))
        return bool(d.get("ok"))
    except Exception as e:
        print("[editphoto err]", e, flush=True)
        return False

def inline_kb(rows):
    return json.dumps({"inline_keyboard": rows}, ensure_ascii=False)

def answer_cb(cq_id, text=""):
    api("answerCallbackQuery", callback_query_id=cq_id, text=text)

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

def gemma_state():
    """Состояние модели, которое пишет чат-бот: рядом с пультом, в соседней папке
    репозитория (gemma-telegram-bot/) или по пути из env GEMMA_STATE_FILE."""
    for p in (os.environ.get("GEMMA_STATE_FILE", ""),
              os.path.join(HERE, "gemma_state.txt"),
              os.path.join(HERE, "..", "gemma-telegram-bot", "gemma_state.txt")):
        if p and os.path.exists(p):
            try:
                return open(p).read().strip()
            except Exception:
                pass
    return ""

def gemma_line():
    if not _running("telegram_bot.py"):
        return "⚪️ остановлен"
    state = gemma_state()
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

# ---------- браузер (общий, живёт между командами) ----------
_pw = None
_browser = None
_bpage = None
browse_state = {"mid": None, "zoom": None, "chat": None, "sig": None}
BROWSE_KB = inline_kb([
    [{"text": "⬆️", "callback_data": "b:up"}, {"text": "⬇️", "callback_data": "b:down"},
     {"text": "🔄", "callback_data": "b:reload"}],
    [{"text": "🔙 Назад", "callback_data": "b:back"}, {"text": "🔗 URL", "callback_data": "b:url"},
     {"text": "❌ Закрыть", "callback_data": "b:close"}],
])

def _ensure_browser():
    global _pw, _browser
    if _browser is None:
        from playwright.sync_api import sync_playwright
        _pw = sync_playwright().start()
        kw = {"args": ["--no-sandbox", "--headless=new"]}
        if SHOT_BROWSER and os.path.exists(SHOT_BROWSER):
            kw["executable_path"] = SHOT_BROWSER
        _browser = _pw.chromium.launch(**kw)
    return _browser

def screenshot(url, out):  # /shot — одноразовый, на общем браузере
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    pg = _ensure_browser().new_page(viewport={"width": 1280, "height": 900}, device_scale_factor=1)
    try:
        pg.goto(url, timeout=30000, wait_until="load")
        pg.wait_for_timeout(1200)
        pg.screenshot(path=out, full_page=False)
    finally:
        pg.close()
    return url

def bpage():
    global _bpage
    br = _ensure_browser()
    if _bpage is None or _bpage.is_closed():
        _bpage = br.new_page(viewport={"width": 1080, "height": 1600}, device_scale_factor=1)
    return _bpage

def browser_quit():
    global _bpage
    try:
        if _bpage and not _bpage.is_closed():
            _bpage.close()
    except Exception:
        pass
    _bpage = None

def b_click_text(arg):
    pg = bpage()
    for loc in (pg.get_by_role("link", name=arg, exact=False),
                pg.get_by_role("button", name=arg, exact=False),
                pg.get_by_text(arg, exact=False)):
        try:
            loc.first.click(timeout=3500)
            return True
        except Exception:
            continue
    return False

def b_smart_click(x, y, tol=90):
    """Клик с прилипанием к ближайшему кликабельному элементу (в радиусе tol)."""
    pg = bpage()
    try:
        t = pg.evaluate(
            "(a) => {const x=a[0],y=a[1];"
            "const els=[...document.querySelectorAll("
            "'a,button,input,select,textarea,[role=button],[role=link],[onclick],summary,label')];"
            "let best=null,bd=1e18;"
            "for(const e of els){const r=e.getBoundingClientRect();"
            "if(r.width<4||r.height<4||r.bottom<0||r.top>innerHeight)continue;"
            "const cx=r.left+r.width/2,cy=r.top+r.height/2,d=Math.hypot(cx-x,cy-y);"
            "if(d<bd){bd=d;best=[cx,cy,d];}}return best;}",
            [x, y, tol])
    except Exception:
        t = None
    if t and t[2] <= tol:
        pg.mouse.click(int(t[0]), int(t[1])); return True
    pg.mouse.click(x, y); return False

def page_opened():
    try:
        return bpage().url not in ("about:blank", "")
    except Exception:
        return False

# ---------- сетка для тыка по экрану (два уровня) ----------
L1_N = 8   # крупная сетка: выбор зоны
L2_N = 5   # сетка внутри зоны: точный клик
_FONTS = {}
CONTROL_ROW = [
    {"text": "⬆️", "callback_data": "b:up"}, {"text": "⬇️", "callback_data": "b:down"},
    {"text": "🔄", "callback_data": "b:reload"}, {"text": "🔙", "callback_data": "b:back"},
    {"text": "❌", "callback_data": "b:close"}]

def _grid_font(size):
    if size in _FONTS:
        return _FONTS[size]
    from PIL import ImageFont
    import matplotlib, glob
    cands = glob.glob(os.path.join(os.path.dirname(matplotlib.__file__),
                                   "mpl-data/fonts/ttf/DejaVuSans-Bold.ttf"))
    cands += ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
    f = None
    for p in cands:
        try:
            f = ImageFont.truetype(p, size); break
        except Exception:
            continue
    if f is None:
        f = ImageFont.load_default()
    _FONTS[size] = f
    return f

def _draw_cells(im, n):
    from PIL import ImageDraw
    d = ImageDraw.Draw(im, "RGBA")
    W, H = im.size
    cw, ch = W / n, H / n
    for i in range(1, n):
        d.line([(cw * i, 0), (cw * i, H)], fill=(255, 45, 45, 150), width=2)
        d.line([(0, ch * i), (W, ch * i)], fill=(255, 45, 45, 150), width=2)
    f = _grid_font(max(16, int(min(cw, ch) / 2.6)))
    k = 1
    for r in range(n):
        for c in range(n):
            x, y = int((c + 0.5) * cw), int((r + 0.5) * ch)
            t = str(k)
            bb = d.textbbox((x, y), t, font=f, anchor="mm")
            d.rectangle([bb[0] - 4, bb[1] - 3, bb[2] + 4, bb[3] + 3], fill=(255, 45, 45, 160))
            d.text((x, y), t, fill=(255, 255, 255), anchor="mm", font=f)
            k += 1
    return im

def cells_kb(n, prefix, extra_rows):
    rows, k = [], 1
    for r in range(n):
        rows.append([{"text": str(k + c), "callback_data": f"{prefix}:{k + c}"} for c in range(n)])
        k += n
    return inline_kb(rows + extra_rows)

def frame_sig(path):
    try:
        from PIL import Image
        return list(Image.open(path).convert("L").resize((32, 32)).getdata())
    except Exception:
        return None

def frame_changed(a, b, thresh=0.03):
    if not a or not b or len(a) != len(b):
        return True
    diff = sum(1 for x, y in zip(a, b) if abs(x - y) > 22)
    return diff / len(a) > thresh

def show_browser(chat_id, note="", raw=None):
    gridded = os.path.join(HERE, "browse_grid.png")
    browse_state["chat"] = chat_id
    try:
        if raw is None:                       # обычный вызов — делаем свежий скриншот
            raw = os.path.join(HERE, "browse.png")
            bpage().screenshot(path=raw, full_page=False)
        browse_state["sig"] = frame_sig(raw)  # запоминаем подпись кадра
        from PIL import Image
        im = Image.open(raw).convert("RGB")
        z = browse_state.get("zoom")
        if z:
            crop = im.crop((z[0], z[1], z[2], z[3]))
            scale = 1080 / max(1, crop.width)
            crop = crop.resize((1080, max(1, int(crop.height * scale))))
            _draw_cells(crop, L2_N); crop.save(gridded)
            kb = cells_kb(L2_N, "h", [[{"text": "⬅️ Вся страница", "callback_data": "b:unzoom"}], CONTROL_ROW])
            hint = "🔎 Увеличено. Жми клетку — кликну точно туда."
        else:
            _draw_cells(im, L1_N); im.save(gridded)
            kb = cells_kb(L1_N, "g", [CONTROL_ROW])
            hint = "👆 Жми клетку — увеличу её для точного клика."
    except Exception as e:
        print("[browser shot err]", e, flush=True)
        send(chat_id, f"⚠️ не смог сделать скриншот:\n{str(e)[:150]}"); return
    cap = "🌐 " + bpage().url[:70] + "\n" + hint + (("\n" + note) if note else "")
    mid = browse_state.get("mid")
    if mid and edit_photo(chat_id, mid, gridded, caption=cap, inline=kb):
        return
    browse_state["mid"] = send_photo(chat_id, gridded, caption=cap, inline=kb)

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
    elif text.startswith("/sh ") or text.strip() == "/sh":
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
                print("[shot err]", repr(e), flush=True)
                send(chat_id, f"⚠️ скриншот не вышел:\n{str(e)[:300]}")
    elif text.startswith("/open") or text.startswith("/browse"):
        parts = text.split(maxsplit=1)
        if len(parts) != 2:
            send(chat_id, "Напиши адрес:\n/open ya.ru")
        else:
            api("sendChatAction", chat_id=chat_id, action="upload_photo")
            try:
                url = parts[1].strip()
                if not url.startswith(("http://", "https://")):
                    url = "https://" + url
                bpage().goto(url, timeout=30000, wait_until="load")
                bpage().wait_for_timeout(1000)
                browse_state["mid"] = None; browse_state["zoom"] = None
                show_browser(chat_id, "Жми клетку → увеличу → тык для точного клика.")
            except Exception as e:
                send(chat_id, f"⚠️ не открыл:\n{str(e)[:200]}")
    elif text.startswith("/click"):
        arg = text[6:].strip()
        if not page_opened():
            send(chat_id, "Сначала открой сайт: /open ya.ru")
        elif not arg:
            send(chat_id, "/click <текст ссылки>  или  /click <x> <y>")
        else:
            api("sendChatAction", chat_id=chat_id, action="upload_photo")
            try:
                p = arg.split()
                if len(p) == 2 and p[0].isdigit() and p[1].isdigit():
                    b_smart_click(int(p[0]), int(p[1]))
                    ok = True
                else:
                    ok = b_click_text(arg)
                bpage().wait_for_timeout(1200)
                browse_state["zoom"] = None
                show_browser(chat_id, f"кликнул: {arg}" if ok else f"не нашёл «{arg}» на странице")
            except Exception as e:
                send(chat_id, f"⚠️ не кликнул «{arg}»:\n{str(e)[:150]}")
    elif text.startswith("/type"):
        s = text[5:].strip()
        if not page_opened():
            send(chat_id, "Сначала открой сайт: /open ya.ru")
        elif not s:
            send(chat_id, "/type <текст для ввода в поле>")
        else:
            try:
                bpage().keyboard.type(s)
                show_browser(chat_id, f"ввёл: {s}")
            except Exception as e:
                send(chat_id, f"⚠️ {str(e)[:150]}")
    elif text.startswith("/enter"):
        if not page_opened():
            send(chat_id, "Сначала открой сайт: /open ya.ru")
        else:
            try:
                bpage().keyboard.press("Enter"); bpage().wait_for_timeout(1500)
                show_browser(chat_id, "нажал Enter")
            except Exception as e:
                send(chat_id, f"⚠️ {str(e)[:150]}")
    elif text.startswith("/close"):
        browser_quit(); browse_state["mid"] = None
        send(chat_id, "🌐 Браузер закрыт.")
    else:
        send(chat_id, "Не знаю такой команды. Жми кнопку снизу или /help.")

def handle_cb(cq):
    uid = cq["from"]["id"]
    chat_id = cq["message"]["chat"]["id"]
    if not authorized(uid):
        answer_cb(cq["id"], "⛔ Доступ запрещён"); return
    data = cq.get("data", "")
    is_action = data.startswith(("g:", "h:")) or (data.startswith("b:") and data != "b:close")
    if is_action and not page_opened():
        answer_cb(cq["id"], "Сначала /open <сайт>"); return
    vp = bpage().viewport_size or {"width": 1080, "height": 1600}
    try:
        if data.startswith("g:"):            # уровень 1 -> зум в клетку
            n = int(data[2:]); r = (n - 1) // L1_N; c = (n - 1) % L1_N
            cw, ch = vp["width"] / L1_N, vp["height"] / L1_N
            browse_state["zoom"] = (int(c * cw), int(r * ch), int((c + 1) * cw), int((r + 1) * ch))
            answer_cb(cq["id"], f"🔎 зона {n}")
            show_browser(chat_id); return
        if data.startswith("h:"):            # уровень 2 -> точный клик
            z = browse_state.get("zoom")
            if not z:
                answer_cb(cq["id"], "нет зума"); return
            n = int(data[2:]); r = (n - 1) // L2_N; c = (n - 1) % L2_N
            x0, y0, x1, y1 = z
            x = int(x0 + (c + 0.5) * (x1 - x0) / L2_N)
            y = int(y0 + (r + 0.5) * (y1 - y0) / L2_N)
            snapped = b_smart_click(x, y); bpage().wait_for_timeout(1200)
            browse_state["zoom"] = None
            answer_cb(cq["id"], "клик по элементу ✅" if snapped else "клик (рядом пусто)")
            show_browser(chat_id, "кликнул"); return
        if data == "b:unzoom":
            browse_state["zoom"] = None; answer_cb(cq["id"]); show_browser(chat_id); return
        if data == "b:url":
            answer_cb(cq["id"], bpage().url[:200]); return
        if data == "b:close":
            browser_quit(); browse_state["mid"] = None; browse_state["zoom"] = None
            answer_cb(cq["id"], "Закрыто"); send(chat_id, "🌐 Браузер закрыт."); return
        # навигация — сбрасываем зум
        browse_state["zoom"] = None
        if data == "b:down":
            bpage().mouse.wheel(0, 900); show_browser(chat_id)
        elif data == "b:up":
            bpage().mouse.wheel(0, -900); show_browser(chat_id)
        elif data == "b:reload":
            bpage().reload(timeout=20000); bpage().wait_for_timeout(1000); show_browser(chat_id)
        elif data == "b:back":
            bpage().go_back(timeout=20000); bpage().wait_for_timeout(1000); show_browser(chat_id)
        answer_cb(cq["id"])
    except Exception as e:
        answer_cb(cq["id"], f"ошибка: {str(e)[:60]}")

def auto_refresh():
    # авто-обновление фото, если страница изменилась сама (не в режиме прицела)
    if not browse_state.get("mid") or browse_state.get("zoom"):
        return
    chat = browse_state.get("chat")
    if not chat or _bpage is None or _bpage.is_closed():
        return
    try:
        tmp = os.path.join(HERE, "browse_auto.png")
        _bpage.screenshot(path=tmp, full_page=False)
        if frame_changed(frame_sig(tmp), browse_state.get("sig")):
            show_browser(chat, raw=tmp)
    except Exception as e:
        print("[auto err]", e, flush=True)

def main():
    me = api("getMe")
    if not me or not me.get("ok"):
        print("[ctl] неверный токен?", me, flush=True); return
    api("setMyCommands", commands=json.dumps(
        [{"command": c, "description": d} for c, d in COMMANDS], ensure_ascii=False))
    print(f"[ctl] запущен как @{me['result']['username']} | секрет: {SECRET}", flush=True)
    offset = None
    while True:
        browsing = bool(browse_state.get("mid")) and not browse_state.get("zoom")
        upd = api("getUpdates", offset=offset, timeout=(3 if browsing else 25))
        if upd and upd.get("ok"):
            for u in upd["result"]:
                offset = u["update_id"] + 1
                try:
                    if "message" in u:
                        handle(u["message"])
                    elif "callback_query" in u:
                        handle_cb(u["callback_query"])
                except Exception as e:
                    print("[handle err]", e, flush=True)
        auto_refresh()

if __name__ == "__main__":
    main()
