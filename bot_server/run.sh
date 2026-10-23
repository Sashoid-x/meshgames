#!/bin/bash
# ==============================================================================
# MeshGames Autonomous Bot Server — Скрипт запуска
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DIR/.." && pwd)"
JAR_FILE="$DIR/build/libs/meshgames-bot-server-all.jar"

# Если fatJar ещё не собран, собираем его
if [ ! -f "$JAR_FILE" ]; then
    echo "Сборка fatJar автономного сервера..."
    (cd "$ROOT_DIR" && ./gradlew :bot_server:fatJar --quiet --console=plain)
fi

echo "Запуск автономного сервера MeshGames Bot..."
java -jar "$JAR_FILE" "$@"
