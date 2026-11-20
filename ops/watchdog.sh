#!/usr/bin/env bash
# Сторож для телеграм-ботов: раз в INTERVAL секунд проверяет, что процессы живы,
# и поднимает упавшие. Запускает ботов строго через python из venv, чтобы у них были
# нужные пакеты (llama_cpp для зрения, playwright для /shot и браузера).
#
#   bash ops/watchdog.sh            # работать постоянно (запускать в фоне: nohup ... &)
#   bash ops/watchdog.sh --once     # одна проверка и выход (для проверки/cron)
#
# Переменные окружения (всё необязательно):
#   PY         python бота            (по умолчанию <корень репо>/venv/bin/python)
#   CHAT_DIR   папка чат-бота         (по умолчанию <корень>/gemma-telegram-bot)
#   CTL_DIR    папка пульта сервера   (по умолчанию <корень>/server-control-bot)
#   INTERVAL   период проверки, сек   (по умолчанию 20)
#   LOG        файл журнала           (по умолчанию <корень>/watchdog.log)
#
# Бот запускается, только если его папка и скрипт существуют (в ветке с одним ботом
# второй просто пропускается). После перезагрузки машины сторож сам не стартует —
# его нужно запустить заново (или повесить на systemd/cron @reboot).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PY:-$ROOT/venv/bin/python}"
CHAT_DIR="${CHAT_DIR:-$ROOT/gemma-telegram-bot}"
CTL_DIR="${CTL_DIR:-$ROOT/server-control-bot}"
INTERVAL="${INTERVAL:-20}"
LOG="${LOG:-$ROOT/watchdog.log}"

check() {  # check <папка> <скрипт>
  local dir="$1" script="$2"
  [ -f "$dir/$script" ] || return 0              # этого бота в ветке нет
  if pgrep -f "$script" >/dev/null; then
    [ "$ONCE" = 1 ] && echo "$script: работает"
    return 0
  fi
  echo "$(date '+%F %T') запускаю $script" | tee -a "$LOG"
  ( cd "$dir" && nohup "$PY" "$script" > "${script%.py}.log" 2>&1 & )
}

ONCE=0; [ "$1" = "--once" ] && ONCE=1

if [ ! -x "$PY" ]; then
  echo "Не найден python: $PY (задай PY=... или создай venv)" >&2
  exit 1
fi

while true; do
  check "$CHAT_DIR" telegram_bot.py
  check "$CTL_DIR" control_bot.py
  [ "$ONCE" = 1 ] && exit 0
  sleep "$INTERVAL"
done
