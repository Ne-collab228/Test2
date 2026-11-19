import json
import time
import os
from llama_cpp import Llama
from llama_cpp.llama_chat_format import Jinja2ChatFormatter
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse, HTMLResponse
import uvicorn

MODEL_PATH = os.path.join(os.path.dirname(__file__), "models", "gemma-4-E4B-it-Q4_K_M.gguf")
N_CTX = int(os.environ.get("N_CTX", "8192"))
N_THREADS = int(os.environ.get("N_THREADS", "4"))

THOUGHT_HEADER = "<|channel>thought"
THOUGHT_DELIM = "<channel|>"

print(f"[load] loading {os.path.basename(MODEL_PATH)} (n_ctx={N_CTX}, threads={N_THREADS}) ...", flush=True)
_t0 = time.time()
llm = Llama(
    model_path=MODEL_PATH,
    n_ctx=N_CTX,
    n_threads=N_THREADS,
    n_threads_batch=N_THREADS,
    verbose=False,
)
_tpl = llm.metadata["tokenizer.chat_template"]
_eos = llm._model.token_get_text(llm.token_eos())
_bos = llm._model.token_get_text(llm.token_bos())
_fmt = Jinja2ChatFormatter(template=_tpl, eos_token=_eos, bos_token=_bos, add_generation_prompt=True)
print(f"[load] ready in {time.time()-_t0:.1f}s | ctx={N_CTX} | thinking=native", flush=True)

app = FastAPI()
HTML_PATH = os.path.join(os.path.dirname(__file__), "index.html")


@app.get("/", response_class=HTMLResponse)
def index():
    with open(HTML_PATH, "r", encoding="utf-8") as f:
        return f.read()


@app.get("/api/info")
def info():
    return {
        "model": os.path.basename(MODEL_PATH),
        "n_ctx": N_CTX,
        "n_threads": N_THREADS,
        "thinking": True,
    }


def build_tokens(messages, enable_thinking):
    res = _fmt(messages=messages, enable_thinking=enable_thinking)
    return llm.tokenize(res.prompt.encode("utf-8"), add_bos=False, special=True)


@app.post("/api/chat")
async def chat(request: Request):
    body = await request.json()
    messages = body.get("messages", [])
    temperature = float(body.get("temperature", 0.7))
    max_tokens = int(body.get("max_tokens", 2048))
    think = bool(body.get("think", True))

    toks = build_tokens(messages, think)

    def sse(obj):
        return "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"

    def gen():
        t0 = time.time()
        first = None
        n = 0
        buf = ""
        header_seen = not think
        state = "think" if think else "answer"
        HOLD = len(THOUGHT_DELIM)
        try:
            for chunk in llm.create_completion(
                toks, temperature=temperature, max_tokens=max_tokens, stream=True
            ):
                piece = chunk["choices"][0]["text"]
                if not piece:
                    continue
                if first is None:
                    first = time.time()
                n += 1
                buf += piece

                # Пока не поняли, думает модель или нет
                if not header_seen:
                    if THOUGHT_HEADER in buf:
                        buf = buf.split(THOUGHT_HEADER, 1)[1]
                        if buf.startswith("\n"):
                            buf = buf[1:]
                        header_seen = True
                        state = "think"
                    elif len(buf) > 40:
                        # модель решила не думать — всё это ответ
                        header_seen = True
                        state = "answer"
                        yield sse({"t": buf}); buf = ""
                        continue
                    else:
                        continue

                if state == "think":
                    if THOUGHT_DELIM in buf:
                        think_part, buf = buf.split(THOUGHT_DELIM, 1)
                        if think_part:
                            yield sse({"think": think_part})
                        state = "answer"
                        if buf:
                            yield sse({"t": buf}); buf = ""
                    elif len(buf) > HOLD:
                        out, buf = buf[:-HOLD], buf[-HOLD:]
                        yield sse({"think": out})
                else:
                    yield sse({"t": buf}); buf = ""

            if buf:
                tag = "think" if state == "think" else "t"
                yield sse({tag: buf})

            dt = time.time() - (first or t0)
            ttft = (first - t0) if first else 0.0
            yield sse({
                "done": True,
                "tokens": n,
                "tok_per_s": round(n / dt, 2) if dt > 0 else 0.0,
                "ttft_s": round(ttft, 2),
            })
        except Exception as e:
            yield sse({"error": str(e)})

    return StreamingResponse(gen(), media_type="text/event-stream")


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8080")), log_level="warning")
