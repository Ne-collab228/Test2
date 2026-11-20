"""Маленький GPT с нуля (char-level), nanoGPT-стиль. Обучение на CPU.
Пишет loss_log.csv (step,train,val,elapsed), samples.txt и checkpoint.
Режимы: обычный (обучение) и --bench (замер скорости и выход)."""
import os, sys, time, math, json
import torch
import torch.nn as nn
from torch.nn import functional as F

torch.manual_seed(1337)
HERE = os.path.dirname(__file__)

# ----- конфиг (через env) -----
N_LAYER   = int(os.environ.get("N_LAYER", 8))
N_HEAD    = int(os.environ.get("N_HEAD", 8))
N_EMBD    = int(os.environ.get("N_EMBD", 512))
BLOCK     = int(os.environ.get("BLOCK", 256))
BATCH     = int(os.environ.get("BATCH", 32))
LR        = float(os.environ.get("LR", 3e-4))
MAX_STEPS = int(os.environ.get("MAX_STEPS", 100000))
MAX_SECS  = float(os.environ.get("MAX_SECS", 900))
EVAL_EVERY= int(os.environ.get("EVAL_EVERY", 50))
EVAL_ITERS= int(os.environ.get("EVAL_ITERS", 5))
WARMUP    = int(os.environ.get("WARMUP", 100))
DECAY_STEPS=int(os.environ.get("DECAY_STEPS", 1500))
DROPOUT   = float(os.environ.get("DROPOUT", 0.1))
THREADS   = int(os.environ.get("THREADS", 4))
LABEL     = os.environ.get("LABEL", "русский (Википедия)")
DATA      = os.environ.get("DATA", os.path.join(HERE, "data", "russian.txt"))
torch.set_num_threads(THREADS)

# ----- данные -----
with open(DATA, "r", encoding="utf-8") as f:
    text = f.read()
chars = sorted(set(text))
V = len(chars)
stoi = {c: i for i, c in enumerate(chars)}
itos = {i: c for i, c in enumerate(chars)}
data = torch.tensor([stoi[c] for c in text], dtype=torch.long)
n = int(0.9 * len(data))
train_data, val_data = data[:n], data[n:]

def get_batch(split):
    d = train_data if split == "train" else val_data
    ix = torch.randint(len(d) - BLOCK - 1, (BATCH,))
    x = torch.stack([d[i:i+BLOCK] for i in ix])
    y = torch.stack([d[i+1:i+BLOCK+1] for i in ix])
    return x, y

# ----- модель -----
class MHA(nn.Module):
    """Эффективное причинное self-attention: слитый QKV + fused SDPA."""
    def __init__(self):
        super().__init__()
        assert N_EMBD % N_HEAD == 0
        self.c_attn = nn.Linear(N_EMBD, 3 * N_EMBD)
        self.proj = nn.Linear(N_EMBD, N_EMBD)
        self.drop = nn.Dropout(DROPOUT)
        self.nh = N_HEAD
    def forward(self, x):
        B, T, C = x.shape
        q, k, v = self.c_attn(x).split(C, dim=2)
        q = q.view(B, T, self.nh, C // self.nh).transpose(1, 2)
        k = k.view(B, T, self.nh, C // self.nh).transpose(1, 2)
        v = v.view(B, T, self.nh, C // self.nh).transpose(1, 2)
        y = F.scaled_dot_product_attention(q, k, v, is_causal=True,
                                           dropout_p=DROPOUT if self.training else 0.0)
        y = y.transpose(1, 2).contiguous().view(B, T, C)
        return self.drop(self.proj(y))

class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.sa = MHA()
        self.ff = nn.Sequential(nn.Linear(N_EMBD, 4*N_EMBD), nn.GELU(),
                                nn.Linear(4*N_EMBD, N_EMBD), nn.Dropout(DROPOUT))
        self.ln1 = nn.LayerNorm(N_EMBD); self.ln2 = nn.LayerNorm(N_EMBD)
    def forward(self, x):
        x = x + self.sa(self.ln1(x))
        x = x + self.ff(self.ln2(x))
        return x

class GPT(nn.Module):
    def __init__(self):
        super().__init__()
        self.tok = nn.Embedding(V, N_EMBD)
        self.pos = nn.Embedding(BLOCK, N_EMBD)
        self.blocks = nn.Sequential(*[Block() for _ in range(N_LAYER)])
        self.lnf = nn.LayerNorm(N_EMBD)
        self.head = nn.Linear(N_EMBD, V)
    def forward(self, idx, targets=None):
        B, T = idx.shape
        x = self.tok(idx) + self.pos(torch.arange(T))
        x = self.lnf(self.blocks(x))
        logits = self.head(x)
        loss = None
        if targets is not None:
            loss = F.cross_entropy(logits.view(B*T, V), targets.view(B*T))
        return logits, loss
    @torch.no_grad()
    def generate(self, idx, n_new):
        for _ in range(n_new):
            logits, _ = self(idx[:, -BLOCK:])
            probs = F.softmax(logits[:, -1, :], dim=-1)
            nxt = torch.multinomial(probs, 1)
            idx = torch.cat([idx, nxt], dim=1)
        return idx

model = GPT()
n_params = sum(p.numel() for p in model.parameters())
print(f"[model] params={n_params/1e6:.1f}M vocab={V} ctx={BLOCK} layers={N_LAYER} embd={N_EMBD} heads={N_HEAD}", flush=True)
with open(os.path.join(HERE, "meta.json"), "w") as f:
    json.dump({"V": V, "params_M": round(n_params/1e6, 1), "label": LABEL,
               "block": BLOCK, "layers": N_LAYER, "embd": N_EMBD}, f, ensure_ascii=False)

@torch.no_grad()
def est_loss():
    model.eval(); out = {}
    for sp in ("train", "val"):
        losses = torch.zeros(EVAL_ITERS)
        for k in range(EVAL_ITERS):
            x, y = get_batch(sp); _, l = model(x, y); losses[k] = l.item()
        out[sp] = losses.mean().item()
    model.train(); return out

def lr_at(step):
    if step < WARMUP:
        return LR * (step + 1) / WARMUP
    if step >= DECAY_STEPS:
        return LR * 0.1
    ratio = (step - WARMUP) / max(1, DECAY_STEPS - WARMUP)
    return LR * 0.1 + 0.5 * (LR - LR * 0.1) * (1 + math.cos(math.pi * ratio))

opt = torch.optim.AdamW(model.parameters(), lr=LR)

# ----- bench -----
if "--bench" in sys.argv:
    model.train()
    for _ in range(2):  # разогрев
        x, y = get_batch("train"); _, l = model(x, y); opt.zero_grad(); l.backward(); opt.step()
    t0 = time.time(); K = 8
    for _ in range(K):
        x, y = get_batch("train"); _, l = model(x, y); opt.zero_grad(); l.backward(); opt.step()
    dt = (time.time() - t0) / K
    toks = BATCH * BLOCK
    print(f"[bench] {dt*1000:.0f} ms/step | {toks/dt:.0f} tok/s | {n_params/1e6:.1f}M params", flush=True)
    sys.exit(0)

# ----- обучение -----
log = open(os.path.join(HERE, "loss_log.csv"), "w")
log.write("step,train,val,elapsed\n"); log.flush()
start = time.time()
model.train()
step = 0
best_val = 1e9
while step < MAX_STEPS and (time.time() - start) < MAX_SECS:
    if step % EVAL_EVERY == 0:
        lo = est_loss(); el = time.time() - start
        log.write(f"{step},{lo['train']:.4f},{lo['val']:.4f},{el:.1f}\n"); log.flush()
        print(f"step {step:5d} | train {lo['train']:.3f} | val {lo['val']:.3f} | {el:.0f}s", flush=True)
        # примерчик
        ctx = torch.zeros((1, 1), dtype=torch.long)
        sample = "".join(itos[i] for i in model.generate(ctx, 300)[0].tolist())
        with open(os.path.join(HERE, "samples.txt"), "w", encoding="utf-8") as sf:
            sf.write(f"=== step {step} (val {lo['val']:.3f}) ===\n{sample}\n")
        if lo["val"] < best_val:
            best_val = lo["val"]
            torch.save(model.state_dict(), os.path.join(HERE, "ckpt.pt"))
    for g in opt.param_groups:
        g["lr"] = lr_at(step)
    x, y = get_batch("train")
    _, loss = model(x, y)
    opt.zero_grad(set_to_none=True); loss.backward(); opt.step()
    step += 1

print(f"[done] steps={step} best_val={best_val:.3f} time={time.time()-start:.0f}s", flush=True)
with open(os.path.join(HERE, "train_status.json"), "w") as f:
    json.dump({"done": True, "steps": step, "best_val": best_val, "params_M": n_params/1e6}, f)
