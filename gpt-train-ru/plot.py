"""Строит график обучения из loss_log.csv -> loss.png"""
import csv, os, json, math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(__file__)
meta = {"V": 65, "params_M": "?", "label": "текст"}
try:
    with open(os.path.join(HERE, "meta.json")) as f:
        meta.update(json.load(f))
except Exception:
    pass
steps, tr, val, el = [], [], [], []
with open(os.path.join(HERE, "loss_log.csv")) as f:
    for row in csv.DictReader(f):
        steps.append(int(row["step"])); tr.append(float(row["train"]))
        val.append(float(row["val"])); el.append(float(row["elapsed"]))

plt.style.use("dark_background")
fig, ax = plt.subplots(figsize=(9, 5.2), dpi=130)
ax.plot(steps, tr, color="#7aa2f7", lw=2, label="train loss")
ax.plot(steps, val, color="#f7768e", lw=2, label="val loss")
ax.scatter(steps, val, s=14, color="#f7768e", zorder=3)
ax.set_xlabel("шаг обучения"); ax.set_ylabel("loss (cross-entropy)")
title = f"Обучение GPT {meta['params_M']}M с нуля (char-level, {meta['label']}, CPU)"
if val:
    title += f"\nшаг {steps[-1]} · val {val[-1]:.3f} · {el[-1]:.0f}s"
ax.set_title(title, fontsize=11)
ax.grid(alpha=0.18); ax.legend(frameon=False)
# опорная линия: случайная модель = ln(vocab)
base = math.log(meta["V"])
ax.axhline(base, color="#565f89", ls="--", lw=1, alpha=0.7)
ax.text(steps[-1] if steps else 0, base+0.03, f"случайная модель (ln {meta['V']})",
        color="#565f89", fontsize=8, ha="right", va="bottom")
fig.tight_layout()
fig.savefig(os.path.join(HERE, "loss.png"))
print("saved loss.png")
