"""Собирает русский корпус из случайных статей ru.wikipedia (чистый текст)."""
import json, time, urllib.request, urllib.parse, os, sys

HERE = os.path.dirname(__file__)
OUT = os.path.join(HERE, "data", "russian.txt")
TARGET = int(os.environ.get("TARGET_BYTES", 2_000_000))
API = "https://ru.wikipedia.org/w/api.php"
UA = "corpus-builder/1.0 (educational char-level LM demo)"

def fetch():
    params = {
        "action": "query", "format": "json", "prop": "extracts",
        "explaintext": "1", "exlimit": "max",
        "generator": "random", "grnnamespace": "0", "grnlimit": "10",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    out = []
    for p in d.get("query", {}).get("pages", {}).values():
        t = p.get("extract", "").strip()
        if len(t) > 200:  # отсеиваем заглушки
            out.append(t)
    return out

os.makedirs(os.path.dirname(OUT), exist_ok=True)
buf, total, calls = [], 0, 0
t0 = time.time()
while total < TARGET and calls < 400 and (time.time() - t0) < 240:
    calls += 1
    try:
        for t in fetch():
            buf.append(t); total += len(t.encode("utf-8"))
    except Exception as e:
        time.sleep(1); continue
    if calls % 10 == 0:
        print(f"  {calls} запросов, {total/1e6:.2f} МБ", flush=True)
    time.sleep(0.15)

text = "\n\n".join(buf)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(text)
print(f"[done] {len(text)} символов, {total/1e6:.2f} МБ, {calls} запросов -> {OUT}")
# немного статистики по алфавиту
cyr = sum(1 for c in text if 'а' <= c.lower() <= 'я' or c.lower() == 'ё')
print(f"[stat] кириллицы: {100*cyr/max(len(text),1):.0f}%")
