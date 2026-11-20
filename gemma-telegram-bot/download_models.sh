#!/usr/bin/env bash
# Качает в ./models/ всё, что нужно боту:
#   1) текстовую модель Gemma 4 E4B (GGUF)            — для диалога
#   2) визуальный проектор mmproj (~0.9 ГБ)           — «глаза», чтобы принимать фото
# Квант текстовой модели можно выбрать: ./download_models.sh Q6_K   (по умолчанию Q4_K_M)
# Без mmproj бот работает, но фото не принимает.
set -e
QUANT="${1:-Q4_K_M}"
REPO="unsloth/gemma-4-E4B-it-GGUF"
BASE="https://huggingface.co/${REPO}/resolve/main"
DIR="$(cd "$(dirname "$0")" && pwd)/models"
mkdir -p "$DIR"

get() {  # get <имя файла на HF> <куда сохранить>
  echo ">> $1"
  curl -L --retry 5 --retry-delay 3 -C - -o "$DIR/$2" "$BASE/$1"
}

get "gemma-4-E4B-it-${QUANT}.gguf" "gemma-4-E4B-it-${QUANT}.gguf"
get "mmproj-F16.gguf" "mmproj-F16.gguf"
echo ">> Готово: $DIR"
echo "   Если взял не Q4_K_M — поправь имя файла в MODEL в telegram_bot.py."
