"""Генерация текста из лучшего чекпоинта (ckpt.pt). temp + top-k для чистоты."""
import os, json, torch, torch.nn as nn
from torch.nn import functional as F

HERE = os.path.dirname(__file__)
meta = json.load(open(os.path.join(HERE, "meta.json")))
V, BLOCK, N_LAYER, N_EMBD = meta["V"], meta["block"], meta["layers"], meta["embd"]
N_HEAD = int(os.environ.get("N_HEAD", 6))
TEMP = float(os.environ.get("TEMP", 0.8))
TOPK = int(os.environ.get("TOPK", 40))
N_NEW = int(os.environ.get("N_NEW", 600))

# словарь — ровно как при обучении
text = open(os.path.join(HERE, "data", "russian.txt"), encoding="utf-8").read()
chars = sorted(set(text))
stoi = {c: i for i, c in enumerate(chars)}
itos = {i: c for i, c in enumerate(chars)}

class MHA(nn.Module):
    def __init__(self):
        super().__init__()
        self.c_attn = nn.Linear(N_EMBD, 3*N_EMBD); self.proj = nn.Linear(N_EMBD, N_EMBD)
        self.drop = nn.Dropout(0.0); self.nh = N_HEAD
    def forward(self, x):
        B,T,C = x.shape
        q,k,v = self.c_attn(x).split(C, dim=2)
        q=q.view(B,T,self.nh,C//self.nh).transpose(1,2); k=k.view(B,T,self.nh,C//self.nh).transpose(1,2); v=v.view(B,T,self.nh,C//self.nh).transpose(1,2)
        y=F.scaled_dot_product_attention(q,k,v,is_causal=True)
        return self.drop(self.proj(y.transpose(1,2).contiguous().view(B,T,C)))
class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.sa=MHA(); self.ff=nn.Sequential(nn.Linear(N_EMBD,4*N_EMBD),nn.GELU(),nn.Linear(4*N_EMBD,N_EMBD),nn.Dropout(0.0))
        self.ln1=nn.LayerNorm(N_EMBD); self.ln2=nn.LayerNorm(N_EMBD)
    def forward(self,x): x=x+self.sa(self.ln1(x)); return x+self.ff(self.ln2(x))
class GPT(nn.Module):
    def __init__(self):
        super().__init__()
        self.tok=nn.Embedding(V,N_EMBD); self.pos=nn.Embedding(BLOCK,N_EMBD)
        self.blocks=nn.Sequential(*[Block() for _ in range(N_LAYER)])
        self.lnf=nn.LayerNorm(N_EMBD); self.head=nn.Linear(N_EMBD,V)
    def forward(self,idx):
        B,T=idx.shape
        x=self.tok(idx)+self.pos(torch.arange(T)); x=self.lnf(self.blocks(x))
        return self.head(x)

m=GPT(); m.load_state_dict(torch.load(os.path.join(HERE,"ckpt.pt"))); m.eval()

@torch.no_grad()
def gen(prompt=""):
    idx = torch.tensor([[stoi.get(c,0) for c in prompt]], dtype=torch.long) if prompt else torch.zeros((1,1),dtype=torch.long)
    for _ in range(N_NEW):
        logits = m(idx[:,-BLOCK:])[:,-1,:]/TEMP
        v,_ = torch.topk(logits, TOPK)
        logits[logits < v[:,[-1]]] = float("-inf")
        p = F.softmax(logits, dim=-1)
        idx = torch.cat([idx, torch.multinomial(p,1)], dim=1)
    return "".join(itos[i] for i in idx[0].tolist())

print(gen(os.environ.get("PROMPT","")))
