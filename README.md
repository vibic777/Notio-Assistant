# Notio Assistant — единый проект Android и Windows
[Русский](README.md) · [English](README.en.md)

**GitHub About / English:** A cross-platform Notio organizer with an optional Windows AI assistant powered by a local model. Actions use configurable permissions, user confirmation, and an undo log.
**Описание GitHub / Русский:** Кроссплатформенный органайзер Notio с дополнительным ИИ-помощником для Windows на локальной модели. Действия ограничиваются правами, подтверждаются пользователем и записываются для отмены.

Этот репозиторий объединяет Android и Windows варианты Notio в одном Git/Gradle-проекте. Общую логику переносим постепенно; платформенные интерфейсы и системные функции сохраняем в Android и Windows слоях.

## Эталонные проекты

До отдельного решения владельца проекты ниже считаются эталонами и остаются неизменными:

- `D:\Notio-Android` — Android приложение и его функциональные требования.
- `D:\Notio-Windows` — Windows приложение и его текущая реализация.
- `D:\FitEasy` — пример общей поставки Android/Windows из одной Gradle-сборки.

Полные исходные деревья импортированы в `android/` и `windows/`. Их Git-истории связаны с общей веткой; исходные каталоги вне этого репозитория можно продолжать использовать как неизменные эталоны. FitEasy остаётся примером организации общей сборки.

## Сборка

Из корня репозитория:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease
.\gradlew.bat :desktopApp:release :desktopApp:packageExe
.\gradlew.bat :desktopApp:syncAndroidStrings
```

Целевая общая поставка всех пяти форматов запускается одной командой:

```powershell
.\gradlew.bat :packageDelivery
```

Нужен `local.properties` с Android SDK; он игнорируется Git. Ключ подписи Android задаётся в `android/keystore.properties`, ключ и пароли не добавлять в репозиторий.

## Цель поставки

Одна версия исходного кода должна собирать пять файлов:

| Платформа | Формат | Назначение |
| --- | --- | --- |
| Android | Debug APK | Установка и проверка разработческой сборки |
| Android | Release APK | Пользовательский APK, подписанный настроенным ключом выпуска |
| Windows | EXE | Установщик Windows |
| Windows | MSI | Установщик Windows для MSI-сценариев |
| Windows | Portable ZIP | Приложение со встроенным runtime, запускаемое после распаковки |

Команда общей поставки: `gradlew.bat :packageDelivery`, файлы появляются в `dist/`. У платформ свои линии версий (`version.properties`): Android `android.version`, Windows `windows.version`.

## Документы

- [Общая архитектура](docs/ARCHITECTURE.md)
- [План объединения](docs/MIGRATION_PLAN.md)
- [Поставка и сборка](docs/DELIVERY.md)
- [Текущее резюме и выводы](docs/SUMMARY_RU.md)
- [Список изменений](CHANGELOG.md)
- [Общий список дел](docs/TODO_RU.md)
- [Перенос на другую машину](docs/MOVE_RU.md)
- [Linux: состояние и сборка в WSL](docs/LINUX_RU.md)
- [Правила работы в репозитории](AGENT.md)
- [Правила вклада](CONTRIBUTING.md)
- [Лицензия MIT](LICENSE), [сторонние компоненты](NOTICE.md), [безопасность](SECURITY.md)

## Статус

Android 0.9.99 и Windows 0.9.45 (2026-10-09) собираются из одного Gradle-корня одной командой; у платформ свои линии версий. Windows повторяет функции Android. Общие Kotlin-файлы постепенно выделяются в `shared/src` (пока два). Лицензия — MIT. Что сделано и что осталось проверить: `docs/SUMMARY_RU.md`, `docs/TODO_RU.md`.
