#!/usr/bin/env bash
#
# Удобная обёртка над обфускатором для Fabric-модов.
#
# Автоматически находит intermediary-mapped Minecraft jar в кеше Fabric Loom
# (~/.gradle/caches/fabric-loom) и добавляет его как --library, чтобы
# override-анализ MC-методов работал корректно (это КЛЮЧЕВОЙ момент —
# без него методы, переопределяющие MC, могут быть ошибочно переименованы,
# и мод сломается).
#
# Использование:
#   ./obfuscate.sh <input.jar> <output.jar> [доп.аргументы обфускатора...]
#
# Пример:
#   ./obfuscate.sh mod.jar mod-obf.jar --dictionary illegal -v
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/aurion-obfuscator-1.0.0-all.jar"

if [[ ! -f "$JAR" ]]; then
    echo "[!] Fat-jar не собран. Запускаю сборку..."
    (cd "$SCRIPT_DIR" && ./gradlew fatJar --console=plain)
fi

if [[ $# -lt 2 ]]; then
    echo "Использование: $0 <input.jar> <output.jar> [доп.аргументы...]"
    exit 1
fi

INPUT="$1"; shift
OUTPUT="$1"; shift

# --- Авто-поиск intermediary MC jar ---
# Приоритет: merged-intermediary (содержит клиент+сервер с intermediary-именами).
LOOM_CACHE="$HOME/.gradle/caches/fabric-loom"
MC_JAR=""
if [[ -d "$LOOM_CACHE" ]]; then
    # Берём самый свежий подходящий jar
    MC_JAR="$(find "$LOOM_CACHE" -name '*minecraft-merged-intermediary*.jar' 2>/dev/null \
              | grep -v 'sources' | sort | tail -1 || true)"
fi

LIB_ARGS=()
if [[ -n "$MC_JAR" ]]; then
    echo "[+] Найден intermediary MC jar:"
    echo "    $MC_JAR"
    LIB_ARGS+=(-l "$MC_JAR")
else
    echo "[!] Intermediary MC jar не найден в $LOOM_CACHE."
    echo "    Override-анализ MC-методов будет ограничен. Рекомендуется указать"
    echo "    MC jar вручную через -l <путь>."
fi

set -x
java -jar "$JAR" -i "$INPUT" -o "$OUTPUT" "${LIB_ARGS[@]}" "$@"
