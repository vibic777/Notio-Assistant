# Перенос проекта на другую машину

Что взять с собой, что поставить на новом месте и как убедиться, что всё собирается. Сборка под Linux в WSL — в
`docs/LINUX_RU.md`.

## 1. Что взять с этой машины

Git переносит только то, что закоммичено. Остальное нужно взять отдельно.

| Что | Где лежит | Зачем |
| --- | --- | --- |
| Репозиторий со всей историей | `D:\Notio` | код и документы |
| Личные заметки | `D:\Notio\private\` (в Git не попадает) | памятка о ключах подписи |
| **Ключ подписи APK** | `D:\Notio\private\signing\debug.keystore` (сборка берёт его отсюда, Android D344) | без него новая машина не соберёт APK, который обновит уже установленное приложение |
| Готовые сборки (по желанию) | `D:\Notio\dist\` | последние APK, MSI, EXE, ZIP |

Всё, кроме сборок, упаковывает один скрипт (Git Bash, из корня репозитория):

```bash
bash tools/make_transfer.sh             # история одним файлом + private/ + памятка
bash tools/make_transfer.sh --with-key  # то же и ключ подписи
```

Получается папка `transfer/` (в Git не попадает). Её переносят сами — на флешке или своём диске; скрипт никуда ничего не
отправляет. Папку с ключом никому не передавать: кто владеет ключом, тот может выпустить обновление вашего приложения.

Не нужны на новой машине: `local.properties` (путь к Android SDK, создаётся заново), `build/`, `.gradle/`, эталонные
`D:\Notio-Android`, `D:\Notio-Windows`, `D:\Notio-Linux` (их содержимое и история уже в этом репозитории: `android/`, `windows/`, `linux/`).

## 2. Развернуть на новой машине

```bash
git clone Notio.bundle Notio
cd Notio
git config user.name vibic777
git config user.email vibic@yandex.ru      # правило репозитория: один автор, см. AGENT.md
```

Затем скопировать `private/` в корень репозитория: ключ подписи лежит в `private/signing/debug.keystore`, и сборка берёт
его оттуда сама. В `<домашняя папка>/.android/` его класть не нужно.

## 3. Что поставить (Windows)

| Что | Версия | Зачем |
| --- | --- | --- |
| JetBrains Runtime SDK | 25 (`jbrsdk-25…`) | сборка и упаковка Windows-приложения (заголовок окна в стиле приложения) |
| Android SDK | платформа 37, build-tools 36.0.0 | сборка APK |
| Git for Windows | любой свежий | Git Bash для скриптов |
| Python | 3.8+ | переводы (`android/translations`) |

Gradle ставить не нужно: `gradlew` скачает 9.8.0 сам. WiX для установщика скачивает сама сборка.

В корне репозитория создать `local.properties` с путём к Android SDK:

```properties
sdk.dir=C\:\\Users\\<вы>\\AppData\\Local\\Android\\Sdk
```

## 4. Проверить, что всё на месте

```powershell
$env:JAVA_HOME = 'C:\Users\<вы>\.jdks\jbrsdk-25…'
.\gradlew.bat :app:testDebugUnitTest :desktopApp:jvmTest   # тесты обеих платформ
.\gradlew.bat :packageDelivery                             # пять файлов в dist/
```

Ожидаемо: Android — все тесты проходят; Windows `:desktopApp` — все; в `:windowsShared` на машине с несколькими сетевыми
адаптерами может не пройти `JmdnsDiscoveryTest` (известный хвост, `docs/TODO_RU.md`).

Release APK без `android/keystore.properties` остаётся неподписанным и подписывается вручную — команда в `docs/DELIVERY.md`.

## 5. Что читать первым

`AGENT.md` в корне (правила и карта репозитория), затем `docs/SUMMARY_RU.md` (что сделано и что не проверено) и
`docs/TODO_RU.md` (что осталось).
