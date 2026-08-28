#!/usr/bin/env bash
#
# Прогоняет все статические/динамические проверки обфусцированного jar.
#
# Использование:
#   tools/verify/verify.sh <obf.jar>
#
# - LinkCheck     : битые ссылки метод/поле (NoSuchMethodError/NoSuchFieldError)
# - AbstractCheck : нереализованные абстрактные методы (AbstractMethodError)
# - LoadVerify    : реальная загрузка с MC classpath (VerifyError/ClassFormatError)
#
# Для LoadVerify автоматически находит intermediary MC jar в кеше Fabric Loom
# и собирает библиотечный classpath из ~/.gradle/caches/modules-2.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
FATJAR="$ROOT_DIR/build/libs/aurion-obfuscator-1.0.0-all.jar"

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <obf.jar>"
    exit 1
fi
OBF="$1"

if [[ ! -f "$FATJAR" ]]; then
    echo "[!] Fat-jar не собран. Собираю..."
    (cd "$ROOT_DIR" && ./gradlew fatJar --console=plain)
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[*] Компиляция проверок..."
javac -cp "$FATJAR" -d "$WORK" \
    "$SCRIPT_DIR/LinkCheck.java" \
    "$SCRIPT_DIR/AbstractCheck.java" \
    "$SCRIPT_DIR/LoadVerify.java"

RC=0

echo ""
echo "==================== LinkCheck ===================="
java -cp "$WORK:$FATJAR" LinkCheck "$OBF" || RC=1

echo ""
echo "==================== AbstractCheck ===================="
java -cp "$WORK:$FATJAR" AbstractCheck "$OBF" || RC=1

echo ""
echo "==================== LoadVerify ===================="
LOOM_CACHE="$HOME/.gradle/caches/fabric-loom"
MC_JAR="$(find "$LOOM_CACHE" -name '*minecraft-merged-intermediary*.jar' 2>/dev/null \
          | grep -v sources | sort | tail -1 || true)"
if [[ -z "$MC_JAR" ]]; then
    echo "[!] Intermediary MC jar не найден — пропускаю LoadVerify."
    echo "    (LinkCheck/AbstractCheck уже дали статическую гарантию.)"
else
    LIBS="$(find "$HOME/.gradle/caches/modules-2" -name '*.jar' 2>/dev/null \
            | grep -vE 'sources|javadoc' | tr '\n' ':')"
    echo "$LIBS" > "$WORK/libs.txt"
    echo "[*] MC: $MC_JAR"
    java -cp "$WORK" LoadVerify "$OBF" "$WORK/libs.txt" "$MC_JAR" || RC=1
fi

echo ""
if [[ $RC -eq 0 ]]; then
    echo "[+] ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ."
else
    echo "[x] ЕСТЬ ПРОБЛЕМЫ — см. вывод выше."
fi
exit $RC
