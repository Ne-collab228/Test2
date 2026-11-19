#!/usr/bin/env python3
"""Мост к локальной Gemma 4 (с раздумьями). Шлёт вопрос на сервер (localhost:8080),
держит историю в history.json, печатает РАЗДУМЬЯ и ОТВЕТ раздельно + скорость."""
import json, sys, os, urllib.request

HIST = os.path.join(os.path.dirname(__file__), "history.json")
URL = "http://127.0.0.1:8080/api/chat"

def load():
    try:
        with open(HIST) as f: return json.load(f)
    except Exception:
        return []

def save(h):
    with open(HIST, "w") as f: json.dump(h, f, ensure_ascii=False)

def main():
    args = sys.argv[1:]
    think = True
    if "--nothink" in args:
        think = False; args.remove("--nothink")
    if args and args[0] == "--reset":
        save([]); print("[история очищена]"); return
    prompt = " ".join(args).strip()
    if not prompt:
        print("usage: chat_cli.py [--nothink] <сообщение>  |  --reset"); return

    h = load()
    h.append({"role": "user", "content": prompt})
    body = json.dumps({"messages": h, "temperature": 0.7, "max_tokens": 2048, "think": think}).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})

    thought, answer, meta = "", "", ""
    with urllib.request.urlopen(req, timeout=1200) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if not line.startswith("data:"): continue
            d = json.loads(line[5:].strip())
            if d.get("think"): thought += d["think"]
            elif d.get("t"): answer += d["t"]
            elif d.get("done"):
                meta = f"[{d['tokens']} токенов · {d['tok_per_s']} tok/s · первый токен {d['ttft_s']}s]"
            elif d.get("error"): answer += f"\n[ошибка: {d['error']}]"

    if thought.strip():
        print("🤔 РАЗДУМЬЯ:")
        print(thought.strip())
        print()
    print("💬 ОТВЕТ:")
    print(answer.strip())
    if meta: print(meta)

    # в историю кладём только финальный ответ (контекст чище)
    h.append({"role": "assistant", "content": answer.strip()})
    save(h)

if __name__ == "__main__":
    main()
