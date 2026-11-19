#!/usr/bin/env bash
# Качает GGUF-модель Gemma 4 E4B в ./models/
# По умолчанию — квант Q4_K_M (~4.7 ГБ). Можно задать другой: ./download_model.sh Q6_K
set -e
QUANT="${1:-Q4_K_M}"
REPO="unsloth/gemma-4-E4B-it-GGUF"
FILE="gemma-4-E4B-it-${QUANT}.gguf"
URL="https://huggingface.co/${REPO}/resolve/main/${FILE}"

mkdir -p "$(dirname "$0")/models"
OUT="$(dirname "$0")/models/${FILE}"

echo ">> Качаю ${FILE} из ${REPO} ..."
curl -L --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL"
echo ">> Готово: $OUT"
