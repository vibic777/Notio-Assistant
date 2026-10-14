# Сборка и пять форматов поставки

## Требуемые файлы

| № | Имя по шаблону | Компонент |
| --- | --- | --- |
| 1 | `Notio-by-vibic-<version>-debug.apk` | Android debug APK |
| 2 | `Notio-by-vibic-<version>-release.apk` | Android release APK |
| 3 | `Notio-<version>.exe` | Windows установщик EXE (тот же MSI в оболочке jpackage) |
| 4 | `Notio-<version>.msi` | Windows установщик MSI |
| 5 | `Notio-<version>-portable.zip` | Windows portable архив со встроенным runtime |

Шаблоны имён задают ожидаемую номенклатуру. Следует согласовать их с уже опубликованными именами и настройками упаковки до первого релиза из объединённого репозитория.

## Единый вызов

```powershell
.\gradlew.bat :packageDelivery
```

Задача зависит от `:app:assembleDebug`, `:app:assembleRelease` и `:desktopApp:release`. `release` сам кладёт MSI, EXE и portable ZIP в `dist/`; `packageDelivery` добавляет туда APK текущей версии Android. Промежуточные файлы остаются в `build/` модулей и не попадают в Git.

## Версии

У платформ свои линии версий в `version.properties` (решение владельца, 2026-10-04): `android.version` / `android.build` (сейчас 0.9.92 / 109) и `windows.version` / `windows.build` (0.9.39 / 20). Номер Windows растёт сам по себе, пока функции платформ не сравняются. При первом объединении (2026-10-03) Windows-пакеты были собраны с номером Android 0.9.82 — эти файлы удалены и не выпускались; если такой пакет был где-то установлен, его нужно удалить перед установкой 0.9.28+ (MSI не понижает версию).

## Windows: MSI и EXE

Оба установщика делает jpackage из одного app image с обученным AOT-кэшем и с WiX-файлами `windows/desktopApp/installer/` (задачи `packageInstaller` и `packageInstallerExe`). Установщик говорит на десяти языках Notio: язык Windows, если он из десяти, иначе английский (`windows/AGENT.md` D80; EXE несёт внутри тот же MSI). В обоих есть галочка «Запустить Notio», правило брандмауэра для синхронизации и закрытие запущенного Notio перед заменой файлов. Стандартная задача плагина `packageExe` этого не умеет и не используется.

## Android: подпись release APK

Без `android/keystore.properties` release APK остаётся неподписанным (`…-release-unsigned.apk`) и подписывается ключом проекта `private/signing/debug.keystore` (вне Git; порядок — в `private/signing/SIGNING_RU.md`). Тем же ключом Gradle подписывает debug APK, когда файл лежит на месте (Android D344); без файла подписывает отладочный ключ машины, и такую поставку `tools/publish_delivery.sh` не делает. Вручную:

```powershell
$apksigner = "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat"
& $apksigner sign --ks "private\signing\debug.keystore" `
  --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
  --out dist\Notio-by-vibic-<version>-release.apk dist\Notio-by-vibic-<version>-release-unsigned.apk
& $apksigner verify --verbose --print-certs dist\Notio-by-vibic-<version>-release.apk
```

Это ключ проекта (SHA-256 сертификата `e146abb83b22728ed8ab345d0d8443902505c539dd93b2ed52219d9d73b0f979`, им подписаны все выпущенные APK). Не создавать новый ключ поверх старого и не публиковать файл ключа. Поставки 0.9.100 и первая 0.9.101 (2026-10-09) вышли с отладочным ключом новой машины сборки (`b835122f…e909e416`) и поверх установленного приложения не ставятся; 0.9.101 перевыпущена 2026-10-10 с ключом проекта.

## Проверки

2026-10-04, из корня: Android — 908 тестов прошли; Windows `:desktopApp` — 239 прошли; `:windowsShared` — 193, из них не прошёл один `JmdnsDiscoveryTest` (поиск по mDNS на машине с несколькими сетевыми адаптерами; код теста и поиска не менялся при объединении).

FitEasy реализует похожий агрегирующий Gradle task `packageDelivery`, managed задачи Windows EXE/MSI и portable ZIP с приложением и runtime. Это полезный образец организации конвейера. Его код не является готовым Notio решением: Windows упаковка, лицензии, установочные действия, имена, данные и runtime Notio требуют отдельного сохранения и адаптации.

## Условия для каждого формата

- **Debug APK:** собрать из версии текущего checkout; предназначен для проверки и разработки.
- **Release APK:** подписать настроенным релизным ключом; ключ и пароли хранить вне Git.
- **EXE и MSI:** собирать из одного Windows app image и одной версии. EXE/MSI должны сохранять нужные Notio действия установщика, обновление поверх предыдущей версии, корректное поведение с работающим приложением и правила данных.
- **Portable ZIP:** включать весь app image и runtime, необходимые файлы лицензий и описание portable режима; не включать пользовательскую базу, профили или машинные конфигурации. Данные portable-копии должны быть отдельны от установленной версии.

## Учёт поставки

Для каждого выпуска зафиксировать версию, номер сборки, Git ревизию, фактический результат каждой задачи, размеры и SHA-256 всех пяти файлов, а также способ подписи release APK. Пакеты сборки не хранить в исходном Git-репозитории.

## Папка поставки (с 2026-10-08)

Как в FitEasy, каждая поставка лежит в своей папке с названием программы, версиями и датой выпуска:

```
dist/Notio-android-<версия Android>-windows-<версия Windows>-<гггг-мм-дд>/
    Notio-by-vibic-<версия>-debug.apk
    Notio-by-vibic-<версия>-release.apk
    Notio-<версия>.msi
    Notio-<версия>.exe
    Notio-<версия>-portable.zip
    manifest.json
```

Порядок: `gradlew :packageDelivery`, коммит, затем из корня `bash tools/publish_delivery.sh tests="<итог тестов>"`. Скрипт
подписывает неподписанный release APK ключом проекта (то же, что описано выше вручную), сверяет сертификат обоих APK с
записанным выше и при другом сертификате останавливается, не написав `manifest.json`,
переносит пять файлов текущих версий из `dist/` в папку и пишет `manifest.json`: версии и номера сборок, дата, ревизия Git
(HEAD — поэтому сначала коммит), размер и SHA-256 каждого файла. Ничего не удаляет. Прежнюю поставку можно разложить так же:
`bash tools/publish_delivery.sh android=0.9.94 windows=0.9.41 date=2026-10-07 source=c24f7ca`. Размеры и контрольные суммы
поставки теперь берутся из её `manifest.json`; в `docs/SUMMARY_RU.md` записывается только путь к папке.
