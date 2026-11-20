"""Telegram-бот на локальной Gemma 4 E4B (CPU). Long-polling.
Меню/кнопки/автоподсказки. Настройки (сохраняются). Генерация в потоке + Стоп.
Живой вывод: сообщение обновляется каждые ~4с (видно раздумья и печать в эфире).
Код оформляется с кнопкой копирования. Токен — в bot_token.txt или env TG_TOKEN."""
import os, json, time, gc, re, html, base64, threading, urllib.request, urllib.parse
from llama_cpp import Llama
from llama_cpp.llama_chat_format import Jinja2ChatFormatter, Gemma4ChatHandler

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "gemma-4-E4B-it-Q4_K_M.gguf")
MMPROJ = os.path.join(HERE, "models", "mmproj-F16.gguf")  # визуальный проектор (зрение)
THOUGHT_HEADER = "<|channel>thought"
THOUGHT_DELIM = "<channel|>"
SETTINGS_FILE = os.path.join(HERE, "chat_settings.json")
EDIT_INTERVAL = 4.0  # как часто обновлять сообщение в эфире, сек

CTX_OPTIONS = [2048, 4096, 8192, 16384]
LIMIT_OPTIONS = [256, 512, 1024, 2048, 4096]
USER_DEFAULTS = {"think": False, "max_tokens": 1024, "temp": 0.7}
GLOBAL_DEFAULTS = {"n_ctx": 8192}

# ---------- настройки ----------
def load_settings():
    try:
        s = json.load(open(SETTINGS_FILE))
    except Exception:
        s = {}
    for k, v in GLOBAL_DEFAULTS.items():
        s.setdefault(k, v)
    s.setdefault("users", {})
    return s

settings = load_settings()

def save_settings():
    try:
        json.dump(settings, open(SETTINGS_FILE, "w"), ensure_ascii=False)
    except Exception as e:
        print("[save err]", e, flush=True)

def ucfg(chat_id):
    u = settings["users"].setdefault(str(chat_id), {})
    for k, v in USER_DEFAULTS.items():
        u.setdefault(k, v)
    return u

# ---------- токен ----------
def get_token():
    t = os.environ.get("TG_TOKEN", "").strip()
    return t or open(os.path.join(HERE, "bot_token.txt")).read().strip()

TOKEN = get_token()
API = f"https://api.telegram.org/bot{TOKEN}"

# ---------- модель ----------
llm = None
_fmt = None
histories = {}
gen_lock = threading.Lock()
stop_events = {}

def set_state(s):
    try:
        open(os.path.join(HERE, "gemma_state.txt"), "w").write(s)
    except Exception:
        pass

def model_loaded():
    return llm is not None

def load_model():
    global llm, _fmt
    if llm is not None:
        return 0.0
    t0 = time.time()
    print("[bot] загружаю Gemma...", flush=True)
    handler = (Gemma4ChatHandler(clip_model_path=MMPROJ, verbose=False, use_gpu=False)
               if os.path.exists(MMPROJ) else None)
    llm = Llama(model_path=MODEL, chat_handler=handler, n_ctx=settings["n_ctx"],
                n_threads=4, n_threads_batch=4, verbose=False)
    tpl = llm.metadata["tokenizer.chat_template"]
    eos = llm._model.token_get_text(llm.token_eos())
    bos = llm._model.token_get_text(llm.token_bos())
    _fmt = Jinja2ChatFormatter(template=tpl, eos_token=eos, bos_token=bos, add_generation_prompt=True)
    set_state("loaded")
    dt = time.time() - t0
    print(f"[bot] модель готова за {dt:.0f}с (ctx={settings['n_ctx']})", flush=True)
    return dt

def unload_model():
    global llm, _fmt
    if llm is None:
        return False
    llm = None; _fmt = None
    gc.collect(); set_state("unloaded")
    print("[bot] модель выгружена", flush=True)
    return True

def build_tokens(messages, think):
    res = _fmt(messages=messages, enable_thinking=think)
    return llm.tokenize(res.prompt.encode("utf-8"), add_bos=False, special=True)

def generate(toks, cfg, stop_ev, on_update=None):
    think = cfg["think"]
    budget = cfg["max_tokens"] + (1024 if think else 0)
    t0 = time.time(); first = None; n = 0; text = ""; stopped = False
    last_edit = t0
    for chunk in llm.create_completion(toks, max_tokens=budget,
                                       temperature=cfg["temp"], stream=True):
        if stop_ev.is_set():
            stopped = True; break
        piece = chunk["choices"][0]["text"]
        if piece:
            if first is None:
                first = time.time()
            n += 1; text += piece
            if on_update and (time.time() - last_edit) >= EDIT_INTERVAL:
                try:
                    on_update(text)
                except Exception:
                    pass
                last_edit = time.time()
    now = time.time()
    read_t = (first - t0) if first else (now - t0)
    gen_t = (now - first) if first else 0.0
    stx = {"p": len(toks), "c": n, "read_t": read_t, "stopped": stopped,
           "read_tps": (len(toks) / read_t) if read_t > 0 else 0.0,
           "gen_tps": (n / gen_t) if gen_t > 0 else 0.0}
    thought, answer = "", text
    if think and THOUGHT_HEADER in text:
        body = text.split(THOUGHT_HEADER, 1)[1].lstrip("\n")
        if THOUGHT_DELIM in body:
            thought, answer = body.split(THOUGHT_DELIM, 1)
        else:
            thought, answer = body, ""
    return thought.strip(), answer.strip(), stx

def live_view(raw, think):
    if think and THOUGHT_HEADER in raw:
        body = raw.split(THOUGHT_HEADER, 1)[1].lstrip("\n")
        if THOUGHT_DELIM in body:
            ans = body.split(THOUGHT_DELIM, 1)[1]
            return "🤔 (раздумья готовы)\n\n💬 " + ans.strip()
        return "🤔 Думаю:\n" + body.strip()
    s = raw.strip()
    return ("💬 " + s) if s else "📖 Обрабатываю…"

# ---------- клавиатура ----------
KB = json.dumps({
    "keyboard": [
        ["🤔 Раздумья", "🧹 Сброс"],
        ["📏 Контекст", "🔢 Лимит ответа"],
        ["⚙️ Статус", "🔌 Модель"],
        ["❓ Помощь"],
    ], "resize_keyboard": True, "is_persistent": True,
}, ensure_ascii=False)

BTN = {
    "🤔 Раздумья": "/think", "🧹 Сброс": "/reset", "📏 Контекст": "/context",
    "🔢 Лимит ответа": "/limit", "⚙️ Статус": "/status", "🔌 Модель": "/model",
    "❓ Помощь": "/help",
}

COMMANDS = [
    ("think", "Вкл/выкл раздумья"),
    ("reset", "Сбросить диалог"),
    ("status", "Текущие настройки"),
    ("context", "Размер контекста модели"),
    ("limit", "Лимит длины ответа"),
    ("model", "Загрузить/выгрузить модель"),
    ("help", "Помощь"),
]

HELP = (
    "🤖 Я бот на локальной Gemma 4 (CPU).\n\n"
    "Пиши текст — отвечу в прямом эфире. 🖼 Можешь прислать ФОТО (с подписью-вопросом "
    "или без) — я его разгляжу и опишу. Во время ответа есть кнопка ⛔ Стоп.\n\n"
    "⚙️ Команды:\n"
    "🤔 /think — раздумья (видно, как думает; медленнее)\n"
    "🧹 /reset — сбросить диалог\n"
    "📏 /context — контекст: 2048 / 4096 / 8192 / 16384\n"
    "🔢 /limit — лимит ответа: 256 … 4096\n"
    "🔌 /model — загрузить/выгрузить модель\n"
    "⚙️ /status — текущие настройки\n\n"
    "Код — в блоке с кнопкой «копировать». Настройки сохраняются."
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

def send(chat_id, text, kb=False, inline=None, mode=None):
    text = text or "(пусто)"
    first_id = None
    for i in range(0, len(text), 4000):
        p = {"chat_id": chat_id, "text": text[i:i+4000]}
        if mode:
            p["parse_mode"] = mode
        if kb and i == 0:
            p["reply_markup"] = KB
        if inline and i == 0:
            p["reply_markup"] = inline
        r = api("sendMessage", **p)
        if first_id is None and r and r.get("ok"):
            first_id = r["result"]["message_id"]
    return first_id

def edit(chat_id, mid, text, inline=None, mode=None):
    p = {"chat_id": chat_id, "message_id": mid, "text": (text or "…")[:4096]}
    if mode:
        p["parse_mode"] = mode
    p["reply_markup"] = inline if inline else json.dumps({"inline_keyboard": []})
    return api("editMessageText", **p)

def answer_cb(cq_id, text=""):
    api("answerCallbackQuery", callback_query_id=cq_id, text=text)

def inline_kb(rows):
    return json.dumps({"inline_keyboard": rows}, ensure_ascii=False)

STOP_KB = inline_kb([[{"text": "⛔ Стоп", "callback_data": "stop"}]])

def opt_rows(options, cur, prefix, per=3):
    btns = [{"text": ("✅ " if n == cur else "") + str(n), "callback_data": f"{prefix}:{n}"}
            for n in options]
    return [btns[i:i+per] for i in range(0, len(btns), per)]

def md_to_html(text):
    parts = text.split("```")
    out = []
    for i, seg in enumerate(parts):
        if i % 2 == 1:
            lines = seg.split("\n")
            if lines and lines[0].strip() and " " not in lines[0].strip() and len(lines) > 1:
                seg = "\n".join(lines[1:])
            out.append(f"<pre><code>{html.escape(seg.strip(chr(10)))}</code></pre>")
        else:
            esc = html.escape(seg)
            esc = re.sub(r"`([^`\n]+)`", lambda m: f"<code>{m.group(1)}</code>", esc)
            out.append(esc)
    return "".join(out)

def status_text(chat_id):
    c = ucfg(chat_id)
    st = "🟢 загружена" if model_loaded() else "💤 выключена"
    return ("⚙️ НАСТРОЙКИ\n\n"
            f"Модель: {st}\n"
            f"📏 Контекст: {settings['n_ctx']} токенов\n"
            f"🔢 Лимит ответа: {c['max_tokens']} токенов\n"
            f"🤔 Раздумья: {'включены' if c['think'] else 'выключены'}\n"
            f"🌡 Температура: {c['temp']}\n"
            f"💬 Реплик в памяти: {len(histories.get(chat_id, []))}")

def render_final(chat_id, mid, thought, answer, tail, stopped):
    head = ("🤔 Раздумья:\n" + thought + "\n\n💬 Ответ:\n") if thought else ""
    ans = answer if answer else ("⛔ Остановлено." if stopped else "(пустой ответ)")
    plain = head + ans + tail
    if len(plain) <= 4000:
        if "`" in ans:
            body = html.escape(head) + md_to_html(ans) + html.escape(tail)
            if len(body) <= 4096:
                r = edit(chat_id, mid, body, mode="HTML")
                if r and r.get("ok"):
                    return
        edit(chat_id, mid, plain)
    else:
        edit(chat_id, mid, "✅ Готово — ответ ниже:")
        if thought:
            send(chat_id, "🤔 Раздумья:\n" + thought)
        if "`" in ans:
            b = md_to_html(ans) + html.escape(tail)
            if len(b) <= 4096 and api("sendMessage", chat_id=chat_id, text=b, parse_mode="HTML"):
                return
        send(chat_id, ans + tail)

# ---------- загрузка фото из Telegram ----------
def get_image_uri(file_id):
    r = api("getFile", file_id=file_id)
    if not r or not r.get("ok"):
        return None
    path = r["result"]["file_path"]
    url = f"https://api.telegram.org/file/bot{TOKEN}/{path}"
    try:
        data = urllib.request.urlopen(url, timeout=60).read()
    except Exception as e:
        print("[file err]", e, flush=True); return None
    mime = "image/png" if path.lower().endswith(".png") else "image/jpeg"
    return f"data:{mime};base64,{base64.b64encode(data).decode()}"

# ---------- воркер (поток) ----------
def worker(chat_id, user_text, file_id=None):
    try:
        c = ucfg(chat_id)
        if not model_loaded():
            send(chat_id, "⏳ Загружаю модель (~0.5–2 мин)...")
            load_model()
        if file_id:
            run_vision(chat_id, c, user_text, file_id)
        else:
            run_text(chat_id, c, user_text)
    except Exception as e:
        send(chat_id, f"⚠️ Ошибка: {e}")
    finally:
        stop_events.pop(chat_id, None)
        gen_lock.release()

def run_text(chat_id, c, user_text):
    hist = histories.setdefault(chat_id, [])
    hist.append({"role": "user", "content": user_text})
    toks = build_tokens(hist, c["think"])
    live_mid = send(chat_id, f"📖 Читаю контекст: {len(toks)} токенов…", inline=STOP_KB)
    ev = threading.Event(); stop_events[chat_id] = ev
    api("sendChatAction", chat_id=chat_id, action="typing")

    def on_update(raw):
        disp = live_view(raw, c["think"])
        if len(disp) > 3800:
            disp = "…" + disp[-3800:]
        edit(chat_id, live_mid, disp + " ▍", inline=STOP_KB)

    thought, answer, stx = generate(toks, c, ev, on_update)
    stop_events.pop(chat_id, None)
    tail = (f"\n\n⚡ {stx['c']} ток · {stx['gen_tps']:.1f} ток/с"
            + (" · ⛔ остановлено" if stx["stopped"] else ""))
    render_final(chat_id, live_mid, thought if c["think"] else "", answer, tail, stx["stopped"])
    hist.append({"role": "assistant", "content": answer})

def run_vision(chat_id, c, caption, file_id):
    live_mid = send(chat_id, "🖼 Смотрю на картинку…", inline=STOP_KB)
    uri = get_image_uri(file_id)
    if not uri:
        edit(chat_id, live_mid, "⚠️ Не смог скачать фото."); return
    ev = threading.Event(); stop_events[chat_id] = ev
    api("sendChatAction", chat_id=chat_id, action="typing")
    prompt = (caption or "").strip() or "Что на этой картинке? Опиши подробно по-русски."
    messages = [{"role": "user", "content": [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": uri}}]}]
    t0 = time.time(); first = None; n = 0; text = ""; stopped = False; last = t0
    for chunk in llm.create_chat_completion(messages=messages, max_tokens=c["max_tokens"],
                                            temperature=c["temp"], stream=True):
        if ev.is_set():
            stopped = True; break
        d = chunk["choices"][0]["delta"].get("content")
        if d:
            if first is None:
                first = time.time()
            n += 1; text += d
            if time.time() - last >= EDIT_INTERVAL:
                disp = ("💬 " + text.strip())
                edit(chat_id, live_mid, ("…" + disp[-3800:] if len(disp) > 3800 else disp) + " ▍",
                     inline=STOP_KB)
                last = time.time()
    stop_events.pop(chat_id, None)
    gen_t = (time.time() - first) if first else 0.0
    tps = (n / gen_t) if gen_t > 0 else 0.0
    tail = f"\n\n🖼 ⚡ {n} ток · {tps:.1f} ток/с" + (" · ⛔ остановлено" if stopped else "")
    render_final(chat_id, live_mid, "", text.strip(), tail, stopped)

# ---------- обработка ----------
def handle(msg):
    chat_id = msg["chat"]["id"]
    # фото (или картинка-документ) -> зрение Gemma
    doc = msg.get("document")
    if "photo" in msg or (doc and str(doc.get("mime_type", "")).startswith("image/")):
        if not gen_lock.acquire(blocking=False):
            send(chat_id, "⏳ Секунду, я ещё отвечаю. Нажми ⛔ Стоп."); return
        file_id = msg["photo"][-1]["file_id"] if "photo" in msg else doc["file_id"]
        threading.Thread(target=worker, args=(chat_id, msg.get("caption", ""), file_id),
                         daemon=True).start()
        return
    text = msg.get("text", "")
    if not text:
        return
    c = ucfg(chat_id)
    if text in BTN:
        text = BTN[text]

    if text.startswith("/start") or text.startswith("/help"):
        send(chat_id, HELP, kb=True); return
    if text.startswith("/reset"):
        histories[chat_id] = []; send(chat_id, "🧹 Диалог сброшен."); return
    if text.startswith("/think"):
        c["think"] = not c["think"]; save_settings()
        send(chat_id, f"🤔 Раздумья {'ВКЛЮЧЕНЫ (медленнее)' if c['think'] else 'выключены'}."); return
    if text.startswith("/status"):
        send(chat_id, status_text(chat_id)); return
    if text.startswith("/context"):
        send(chat_id, "📏 Размер контекста (сколько помнит).\nБольше = помнит дольше, но медленнее.\n"
                      f"Сейчас: {settings['n_ctx']}",
             inline=inline_kb(opt_rows(CTX_OPTIONS, settings["n_ctx"], "ctx", 2))); return
    if text.startswith("/limit"):
        send(chat_id, "🔢 Лимит длины ответа (токенов).\nБольше = длиннее, но дольше.\n"
                      f"Сейчас: {c['max_tokens']}",
             inline=inline_kb(opt_rows(LIMIT_OPTIONS, c["max_tokens"], "lim", 3))); return
    if text.startswith(("/model", "/load", "/unload")):
        if not gen_lock.acquire(blocking=False):
            send(chat_id, "⏳ Идёт генерация. Подожди или нажми ⛔ Стоп."); return
        try:
            if text.startswith("/unload"):
                send(chat_id, "💤 Выгружено." if unload_model() else "Модель и так выключена.")
            elif text.startswith("/load"):
                if model_loaded():
                    send(chat_id, "✅ Уже загружена.")
                else:
                    send(chat_id, "⏳ Загружаю…"); dt = load_model(); send(chat_id, f"✅ Готово за {dt:.0f}с.")
            else:
                if model_loaded():
                    unload_model(); send(chat_id, "💤 Модель выгружена, ~7 ГБ освобождено.")
                else:
                    send(chat_id, "⏳ Загружаю (~0.5–2 мин)…"); dt = load_model(); send(chat_id, f"✅ Загружена за {dt:.0f}с.")
        finally:
            gen_lock.release()
        return

    if not gen_lock.acquire(blocking=False):
        send(chat_id, "⏳ Секунду, я ещё отвечаю. Нажми ⛔ Стоп, чтобы прервать.")
        return
    threading.Thread(target=worker, args=(chat_id, text), daemon=True).start()

def handle_cb(cq):
    data = cq.get("data", "")
    msg = cq["message"]; chat_id = msg["chat"]["id"]; mid = msg["message_id"]
    if data == "stop":
        ev = stop_events.get(chat_id)
        answer_cb(cq["id"], "Останавливаю…" if ev else "Уже нечего останавливать")
        if ev:
            ev.set()
        return
    if data.startswith("lim:"):
        n = int(data[4:]); ucfg(chat_id)["max_tokens"] = n; save_settings()
        answer_cb(cq["id"], f"Лимит: {n}")
        edit(chat_id, mid, f"🔢 Лимит ответа: {n} токенов. ✅")
    elif data.startswith("ctx:"):
        n = int(data[4:]); settings["n_ctx"] = n; save_settings()
        answer_cb(cq["id"], f"Контекст: {n}")
        if model_loaded():
            if gen_lock.acquire(blocking=False):
                try:
                    edit(chat_id, mid, f"📏 Контекст {n}: перезагружаю модель…")
                    unload_model(); load_model()
                    edit(chat_id, mid, f"📏 Контекст: {n} ток. ✅ Модель перезагружена.")
                finally:
                    gen_lock.release()
            else:
                edit(chat_id, mid, f"📏 Контекст {n} сохранён. Применится после генерации (/model).")
        else:
            edit(chat_id, mid, f"📏 Контекст: {n} ток. ✅ Применится при загрузке.")

def main():
    me = api("getMe")
    if not me or not me.get("ok"):
        print("[bot] неверный токен?", me, flush=True); return
    api("setMyCommands", commands=json.dumps(
        [{"command": c, "description": d} for c, d in COMMANDS], ensure_ascii=False))
    set_state("unloaded")
    print(f"[bot] запущен как @{me['result']['username']}", flush=True)
    offset = None
    while True:
        upd = api("getUpdates", offset=offset, timeout=30)
        if not upd or not upd.get("ok"):
            time.sleep(2); continue
        for u in upd["result"]:
            offset = u["update_id"] + 1
            try:
                if "message" in u:
                    handle(u["message"])
                elif "callback_query" in u:
                    handle_cb(u["callback_query"])
            except Exception as e:
                print("[handle err]", e, flush=True)

if __name__ == "__main__":
    main()
