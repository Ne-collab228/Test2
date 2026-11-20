"""Telegram-бот на локальной Gemma 4 E4B (CPU). Long-polling, без входящих портов.
Переключатель раздумий на каждого пользователя: /think.
Токен читается из bot_token.txt (рядом со скриптом) или env TG_TOKEN."""
import os, json, time, gc, urllib.request, urllib.parse
from llama_cpp import Llama
from llama_cpp.llama_chat_format import Jinja2ChatFormatter

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "gemma-4-E4B-it-Q4_K_M.gguf")
THOUGHT_HEADER = "<|channel>thought"
THOUGHT_DELIM = "<channel|>"
MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "512"))
N_CTX = int(os.environ.get("N_CTX", "4096"))

def get_token():
    t = os.environ.get("TG_TOKEN", "").strip()
    if t:
        return t
    with open(os.path.join(HERE, "bot_token.txt")) as f:
        return f.read().strip()

TOKEN = get_token()
API = f"https://api.telegram.org/bot{TOKEN}"

# ---- модель (ленивая загрузка/выгрузка) ----
llm = None
_fmt = None

def model_loaded():
    return llm is not None

def load_model():
    global llm, _fmt
    if llm is not None:
        return 0.0
    t0 = time.time()
    print("[bot] загружаю Gemma...", flush=True)
    llm = Llama(model_path=MODEL, n_ctx=N_CTX, n_threads=4, n_threads_batch=4, verbose=False)
    tpl = llm.metadata["tokenizer.chat_template"]
    eos = llm._model.token_get_text(llm.token_eos())
    bos = llm._model.token_get_text(llm.token_bos())
    _fmt = Jinja2ChatFormatter(template=tpl, eos_token=eos, bos_token=bos, add_generation_prompt=True)
    dt = time.time() - t0
    print(f"[bot] модель готова за {dt:.0f}с", flush=True)
    return dt

def unload_model():
    global llm, _fmt
    if llm is None:
        return False
    llm = None
    _fmt = None
    gc.collect()
    print("[bot] модель выгружена", flush=True)
    return True

chats = {}  # chat_id -> {"history": [...], "think": bool}

def st(chat_id):
    return chats.setdefault(chat_id, {"history": [], "think": False})

def generate(messages, think):
    res = _fmt(messages=messages, enable_thinking=think)
    toks = llm.tokenize(res.prompt.encode("utf-8"), add_bos=False, special=True)
    out = llm.create_completion(toks, max_tokens=MAX_TOKENS, temperature=0.7, stream=False)
    text = out["choices"][0]["text"]
    thought, answer = "", text
    if think and THOUGHT_HEADER in text:
        text = text.split(THOUGHT_HEADER, 1)[1].lstrip("\n")
        if THOUGHT_DELIM in text:
            thought, answer = text.split(THOUGHT_DELIM, 1)
        else:
            thought, answer = text, ""
    return thought.strip(), answer.strip()

def api(method, **params):
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(f"{API}/{method}", data=data)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except Exception as e:
        print("[api err]", method, e, flush=True)
        return None

def send(chat_id, text):
    for i in range(0, len(text), 4000):  # лимит Telegram 4096
        api("sendMessage", chat_id=chat_id, text=text[i:i+4000])

def handle(msg):
    chat_id = msg["chat"]["id"]
    text = msg.get("text", "")
    if not text:
        return
    s = st(chat_id)

    if text.startswith("/start"):
        send(chat_id, "Привет! Я бот на локальной Gemma 4, работаю на CPU-сервере.\n\n"
                      "Команды:\n/think — вкл/выкл раздумья\n/load — включить модель\n"
                      "/unload — выключить модель (освободить память)\n/reset — сбросить диалог\n"
                      "/status — статус\n\n"
                      "Просто пиши сообщение — отвечу. На CPU это ~15–40 сек, потерпи 🙂")
        return
    if text.startswith("/reset"):
        s["history"] = []
        send(chat_id, "🧹 Диалог сброшен.")
        return
    if text.startswith("/think"):
        s["think"] = not s["think"]
        send(chat_id, f"🤔 Раздумья {'ВКЛЮЧЕНЫ' if s['think'] else 'выключены'}.")
        return
    if text.startswith("/load"):
        if model_loaded():
            send(chat_id, "✅ Модель уже загружена.")
        else:
            send(chat_id, "⏳ Загружаю модель, это ~0.5–2 мин (чтение с диска)...")
            api("sendChatAction", chat_id=chat_id, action="typing")
            dt = load_model()
            send(chat_id, f"✅ Модель загружена за {dt:.0f}с. Пиши вопрос!")
        return
    if text.startswith("/unload"):
        if unload_model():
            send(chat_id, "💤 Модель выгружена, ~7 ГБ памяти освобождено. Включить снова: /load.")
        else:
            send(chat_id, "Модель и так выключена.")
        return
    if text.startswith("/status"):
        send(chat_id, f"Модель: Gemma 4 E4B (Q4) — {'🟢 загружена' if model_loaded() else '💤 выключена'}. "
                      f"Раздумья: {'вкл' if s['think'] else 'выкл'}. Реплик в памяти: {len(s['history'])}.")
        return

    # обычное сообщение — если модель выключена, загрузим
    if not model_loaded():
        send(chat_id, "⏳ Модель была выключена, загружаю (~0.5–2 мин)...")
        api("sendChatAction", chat_id=chat_id, action="typing")
        load_model()
        send(chat_id, "✅ Загрузил, отвечаю...")
    api("sendChatAction", chat_id=chat_id, action="typing")
    s["history"].append({"role": "user", "content": text})
    try:
        thought, answer = generate(s["history"], s["think"])
    except Exception as e:
        send(chat_id, f"⚠️ Ошибка генерации: {e}")
        s["history"].pop()
        return
    if s["think"] and thought:
        send(chat_id, "🤔 Раздумья:\n" + thought)
    send(chat_id, answer or "(пустой ответ)")
    s["history"].append({"role": "assistant", "content": answer})

def main():
    me = api("getMe")
    if not me or not me.get("ok"):
        print("[bot] неверный токен?", me, flush=True)
        return
    print(f"[bot] запущен как @{me['result']['username']}", flush=True)
    offset = None
    while True:
        upd = api("getUpdates", offset=offset, timeout=30)
        if not upd or not upd.get("ok"):
            time.sleep(2)
            continue
        for u in upd["result"]:
            offset = u["update_id"] + 1
            if "message" in u:
                try:
                    handle(u["message"])
                except Exception as e:
                    print("[handle err]", e, flush=True)

if __name__ == "__main__":
    main()
