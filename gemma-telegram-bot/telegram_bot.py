"""Telegram-бот на локальной Gemma 4 E4B (CPU). Long-polling.
Меню/кнопки/автоподсказки. Настройки контекста и лимита ответа с сохранением.
Токен — в bot_token.txt или env TG_TOKEN."""
import os, json, time, gc, html, urllib.request, urllib.parse
from llama_cpp import Llama
from llama_cpp.llama_chat_format import Jinja2ChatFormatter

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "gemma-4-E4B-it-Q4_K_M.gguf")
THOUGHT_HEADER = "<|channel>thought"
THOUGHT_DELIM = "<channel|>"
SETTINGS_FILE = os.path.join(HERE, "chat_settings.json")

CTX_OPTIONS = [2048, 4096, 8192]
LIMIT_OPTIONS = [256, 512, 1024, 2048]
USER_DEFAULTS = {"think": False, "max_tokens": 512, "temp": 0.7}
GLOBAL_DEFAULTS = {"n_ctx": 4096}

# ---------- настройки (сохраняются) ----------
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
histories = {}  # chat_id -> [messages]

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
    llm = Llama(model_path=MODEL, n_ctx=settings["n_ctx"], n_threads=4,
                n_threads_batch=4, verbose=False)
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
    gc.collect()
    set_state("unloaded")
    print("[bot] модель выгружена", flush=True)
    return True

def build_tokens(messages, think):
    res = _fmt(messages=messages, enable_thinking=think)
    return llm.tokenize(res.prompt.encode("utf-8"), add_bos=False, special=True)

def generate(toks, cfg):
    think = cfg["think"]
    budget = cfg["max_tokens"] + (1024 if think else 0)
    t0 = time.time(); first = None; n = 0; text = ""
    for chunk in llm.create_completion(toks, max_tokens=budget,
                                       temperature=cfg["temp"], stream=True):
        piece = chunk["choices"][0]["text"]
        if piece:
            if first is None:
                first = time.time()
            n += 1; text += piece
    now = time.time()
    read_t = (first - t0) if first else (now - t0)
    gen_t = (now - first) if first else 0.0
    stx = {
        "p": len(toks), "c": n, "read_t": read_t,
        "read_tps": (len(toks) / read_t) if read_t > 0 else 0.0,
        "gen_tps": (n / gen_t) if gen_t > 0 else 0.0,
    }
    thought, answer = "", text
    if think and THOUGHT_HEADER in text:
        text = text.split(THOUGHT_HEADER, 1)[1].lstrip("\n")
        if THOUGHT_DELIM in text:
            thought, answer = text.split(THOUGHT_DELIM, 1)
        else:
            thought, answer = text, ""
    return thought.strip(), answer.strip(), stx

# ---------- клавиатура / меню ----------
KB = json.dumps({
    "keyboard": [
        ["🤔 Раздумья", "🧹 Сброс"],
        ["📏 Контекст", "🔢 Лимит ответа"],
        ["⚙️ Статус", "🔌 Модель"],
        ["❓ Помощь"],
    ],
    "resize_keyboard": True, "is_persistent": True,
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
    "Просто пиши сообщение — отвечу. На CPU это ~15–40 сек.\n\n"
    "⚙️ Команды:\n"
    "🤔 /think — раздумья: модель думает перед ответом (медленнее)\n"
    "🧹 /reset — сбросить диалог\n"
    "📏 /context — размер контекста (сколько помнит): 2048 / 4096 / 8192\n"
    "🔢 /limit — лимит длины ответа: 256 / 512 / 1024 / 2048\n"
    "🔌 /model — загрузить или выгрузить модель из памяти\n"
    "⚙️ /status — показать текущие настройки\n\n"
    "Настройки сохраняются. Снизу — кнопки, при вводе «/» — подсказки."
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

def send(chat_id, text, kb=False, inline=None):
    text = text or "(пусто)"
    first_id = None
    for i in range(0, len(text), 4000):
        p = {"chat_id": chat_id, "text": text[i:i+4000]}
        if kb and i == 0:
            p["reply_markup"] = KB
        if inline and i == 0:
            p["reply_markup"] = inline
        r = api("sendMessage", **p)
        if first_id is None and r and r.get("ok"):
            first_id = r["result"]["message_id"]
    return first_id

def edit(chat_id, mid, text):
    api("editMessageText", chat_id=chat_id, message_id=mid, text=text)

def answer_cb(cq_id, text=""):
    api("answerCallbackQuery", callback_query_id=cq_id, text=text)

def inline_kb(rows):
    return json.dumps({"inline_keyboard": rows}, ensure_ascii=False)

def status_text(chat_id):
    c = ucfg(chat_id)
    st = "🟢 загружена" if model_loaded() else "💤 выключена"
    return (
        "⚙️ НАСТРОЙКИ\n\n"
        f"Модель: {st}\n"
        f"📏 Контекст: {settings['n_ctx']} токенов\n"
        f"🔢 Лимит ответа: {c['max_tokens']} токенов\n"
        f"🤔 Раздумья: {'включены' if c['think'] else 'выключены'}\n"
        f"🌡 Температура: {c['temp']}\n"
        f"💬 Реплик в памяти: {len(histories.get(chat_id, []))}"
    )

# ---------- обработка сообщений ----------
def handle(msg):
    chat_id = msg["chat"]["id"]
    text = msg.get("text", "")
    if not text:
        return
    c = ucfg(chat_id)

    if text in BTN:
        text = BTN[text]

    if text.startswith("/start") or text.startswith("/help"):
        send(chat_id, HELP, kb=True); return
    if text.startswith("/reset"):
        histories[chat_id] = []
        send(chat_id, "🧹 Диалог сброшен."); return
    if text.startswith("/think"):
        c["think"] = not c["think"]; save_settings()
        send(chat_id, f"🤔 Раздумья {'ВКЛЮЧЕНЫ (ответы медленнее)' if c['think'] else 'выключены'}."); return
    if text.startswith("/status"):
        send(chat_id, status_text(chat_id)); return
    if text.startswith("/context"):
        rows = [[{"text": (("✅ " if n == settings["n_ctx"] else "") + str(n)),
                  "callback_data": f"ctx:{n}"} for n in CTX_OPTIONS]]
        send(chat_id, "📏 Размер контекста (сколько модель помнит).\n"
                      "Больше = помнит дольше, но медленнее и больше памяти.\n"
                      "Сейчас: " + str(settings["n_ctx"]), inline=inline_kb(rows)); return
    if text.startswith("/limit"):
        rows = [[{"text": (("✅ " if n == c["max_tokens"] else "") + str(n)),
                  "callback_data": f"lim:{n}"} for n in LIMIT_OPTIONS]]
        send(chat_id, "🔢 Лимит длины ответа (в токенах).\n"
                      "Больше = длиннее ответы, но дольше.\n"
                      "Сейчас: " + str(c["max_tokens"]), inline=inline_kb(rows)); return
    if text.startswith("/model"):
        if model_loaded():
            unload_model()
            send(chat_id, "💤 Модель выгружена, ~7 ГБ памяти освобождено.")
        else:
            send(chat_id, "⏳ Загружаю модель (~0.5–2 мин)...")
            api("sendChatAction", chat_id=chat_id, action="typing")
            dt = load_model()
            send(chat_id, f"✅ Модель загружена за {dt:.0f}с.")
        return
    if text.startswith("/load"):
        if model_loaded():
            send(chat_id, "✅ Модель уже загружена.")
        else:
            send(chat_id, "⏳ Загружаю (~0.5–2 мин)...")
            api("sendChatAction", chat_id=chat_id, action="typing")
            dt = load_model(); send(chat_id, f"✅ Готово за {dt:.0f}с.")
        return
    if text.startswith("/unload"):
        send(chat_id, "💤 Выгружено." if unload_model() else "Модель и так выключена."); return

    # ---- обычное сообщение ----
    if not model_loaded():
        send(chat_id, "⏳ Модель была выключена, загружаю (~0.5–2 мин)...")
        api("sendChatAction", chat_id=chat_id, action="typing")
        load_model()
        send(chat_id, "✅ Загрузил, отвечаю...")
    hist = histories.setdefault(chat_id, [])
    hist.append({"role": "user", "content": text})
    try:
        toks = build_tokens(hist, c["think"])
    except Exception as e:
        send(chat_id, f"⚠️ Ошибка: {e}"); hist.pop(); return
    # статус чтения контекста (в начале)
    status_mid = send(chat_id, f"📖 Читаю контекст: {len(toks)} токенов…")
    api("sendChatAction", chat_id=chat_id, action="typing")
    try:
        thought, answer, stx = generate(toks, c)
    except Exception as e:
        send(chat_id, f"⚠️ Ошибка генерации: {e}"); hist.pop(); return
    if status_mid:
        edit(chat_id, status_mid,
             f"📖 Контекст прочитан: {stx['p']} токенов за {stx['read_t']:.1f}с "
             f"({stx['read_tps']:.0f} ток/с)")
    if c["think"] and thought:
        send(chat_id, "🤔 Раздумья:\n" + thought)
    # ток/с генерации (в конце)
    tail = f"\n\n⚡ {stx['c']} токенов · {stx['gen_tps']:.1f} ток/с"
    if answer:
        send(chat_id, answer + tail)
    elif c["think"]:
        send(chat_id, "⚠️ Модель всё обдумала, но на ответ не хватило лимита.\n"
                      "Увеличь лимит (/limit) или выключи раздумья (/think)." + tail)
    else:
        send(chat_id, "(пустой ответ)" + tail)
    hist.append({"role": "assistant", "content": answer})

# ---------- обработка кнопок-настроек ----------
def handle_cb(cq):
    data = cq.get("data", "")
    msg = cq["message"]; chat_id = msg["chat"]["id"]; mid = msg["message_id"]
    if data.startswith("lim:"):
        n = int(data[4:]); ucfg(chat_id)["max_tokens"] = n; save_settings()
        answer_cb(cq["id"], f"Лимит: {n}")
        edit(chat_id, mid, f"🔢 Лимит ответа установлен: {n} токенов. ✅")
    elif data.startswith("ctx:"):
        n = int(data[4:]); settings["n_ctx"] = n; save_settings()
        answer_cb(cq["id"], f"Контекст: {n}")
        if model_loaded():
            edit(chat_id, mid, f"📏 Контекст: {n}. Перезагружаю модель с новым контекстом...")
            unload_model(); load_model()
            edit(chat_id, mid, f"📏 Контекст установлен: {n} токенов. ✅ Модель перезагружена.")
        else:
            edit(chat_id, mid, f"📏 Контекст установлен: {n} токенов. ✅ Применится при загрузке модели.")

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
