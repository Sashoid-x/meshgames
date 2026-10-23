# CLAUDE.md

Этот файл помогает Claude Code работать с репозиторием MeshGames.

**[AGENTS.md](AGENTS.md)** — источник истины о правилах, инвариантах
протокола и workflow; он не дублируется здесь, а импортируется:

@AGENTS.md

## Claude-Specific Instructions

- **Delegate to keep context lean:**
  - широкие поиски («где используется X», «какие есть реализации Y») →
    dispatch `Explore`-сабагента, в основной контекст не тащить дампы файлов;
  - сборки/тесты (`./gradlew …`) → dispatch сабагента-раннера: полный лог
    десятки килобайт, нужен только pass/fail + упавшие тесты;
  - навигация по символам → `rg`, затем читать только нужный диапазон.
- **Большие файлы не читать целиком:** `packets.json` (~6 МБ), экспорты
  пакетов, бинарники — только grep/фильтры.
- **Планы** для рефакторингов, выходящих за один файл — отдельным
  markdown в рабочей папке (репозиторий не git).

## Quick Reference

JDK 21+, Android SDK (compileSdk 37). Базовая верификация — запускать
**до** того, как сообщить о готовности:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :bot_server:test :bot_server:fatJar --console=plain
```

Ожидание: все юнит-тесты обоих проектов зелёные. Единственный транспорт
приложения — TCP-мост (`net/bridge/*`), сервер `bot_server` напрямую
подключается к ноде Meshtastic (порт 4403) и встроенному мосту (порт 8090).

APK: `app/build/outputs/apk/debug/app-debug.apk` — ставить на оба
устройства для полевой проверки.
Бот-сервер fatJar: `bot_server/build/libs/meshgames-bot-server-all.jar`.

**Один тест:**
```bash
./gradlew :app:testDebugUnitTest --tests "*SessionManagerTest*"
```

**Полевые логи** от пользователя: `meshgames-*-*.log.txt` в рабочей папке —
формат `MM-dd HH:mm:ss.SSS [TAG] msg`, теги `APP/S/TX/TX*/RX/BR/NET/LOG`
(см. AGENTS.md → context_and_memory).
