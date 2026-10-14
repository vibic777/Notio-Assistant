# Notio — диагностика на компьютере с Windows

Что собрать и прислать, когда Notio для Windows ведёт себя не так (не запускается, не стартует вместе с Windows, зависает).
Всё делается на том компьютере, где видна ошибка.

## 1. Журнал приложения

1. Открыть Проводник.
2. Щёлкнуть по адресной строке вверху, вставить и нажать Enter:
   ```
   %LOCALAPPDATA%\Notio\logs
   ```
3. Прислать `app.log` и, если есть, `app.1.log` (предыдущий журнал).

У переносной версии (распакованной из ZIP) журнал лежит в папке `data\logs` рядом с `Notio.exe`.

Если Notio открывается, журнал можно сохранить и из приложения: Настройки → О программе → Журнал приложения.

С версии 0.9.35 запуск, который не удался, сам пишет в этот журнал, почему: искать в конце файла строки
`FATAL app:` (повреждены файлы программы; ошибка до окна) и `ERROR app: a second start` (Notio уже запущен и не отвечает).
Не попадает в журнал только падение самой Java-машины: тогда рядом с местом запуска остаётся файл `hs_err_pid*.log`.

## 2. Команды диагностики

Пуск → набрать `PowerShell` → открыть (обычный, не от администратора). Вставлять по одной команде и нажимать Enter.
Прислать всё, что они выведут, даже если вывод пустой.

**Запущен ли Notio** (в том числе невидимым процессом):

```powershell
Get-Process Notio -ErrorAction SilentlyContinue | Select-Object Id, StartTime, Path
```

Пустой вывод — Notio не запущен.

**Записан ли автозапуск:**

```powershell
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' | Select-Object Notio*
```

Пустой вывод — автозапуск не записан (выключатель «Запускать вместе с Windows» в Настройки → Общие → Окно выключен
или запись стёрта удалением прежней версии).

## 3. Notio не запускается после перезагрузки

Команды из раздела 2 выполнять сразу после перезагрузки, пока Notio не запускали вручную. И ответить на вопрос:
Notio не стартует сам при входе в Windows или не открывается даже по ярлыку?

Что это может быть (по коду, на компьютере с ошибкой не проверено):

| Что видно | Возможная причина | Как проверить |
| --- | --- | --- |
| Первая команда показывает процесс, окна нет | Notio стартовал свёрнутым в трей (так по умолчанию при автозапуске); Windows 11 прячет новый значок под «^» у часов | Нажать «^» у часов и найти значок Notio |
| Первая команда пустая, вторая пустая | Автозапуск не записан | Настройки → Общие → Окно → «Запускать вместе с Windows» |
| Первая команда показывает процесс, по ярлыку ничего не открывается | Первый Notio невидим или завис и держит `instance.lock`; второй запуск молча завершается и ничего не пишет в журнал | Завершить процесс Notio в Диспетчере задач и запустить снова; прислать журнал |
| Вторая команда показывает путь не к `C:\Program Files\Notio\Notio.exe` (папка `build`, пробная сборка, распакованный ZIP); в журнале при старте другая версия | Notio 0.9.59 и раньше при каждом запуске переписывал автозапуск на себя, в том числе запуск при сборке (Windows D155) | Открыть установленный Notio и выключить и включить «Запускать вместе с Windows» |
| Первая команда пустая, вторая показывает запись, Notio не стартовал | Автозапуск отключён в Диспетчере задач → «Автозагрузка приложений», или путь в записи ведёт к перемещённой папке | Сравнить путь в выводе второй команды с тем, где лежит `Notio.exe` |

## 4. Полный отчёт одним блоком

Когда Notio не открывается совсем. Блок вставляется в PowerShell целиком, запускает Notio, ждёт до 15 секунд и открывает
Блокнот с отчётом `notio-diag.txt` на рабочем столе. В отчёте только имена и даты файлов Notio, его версия, события
установщика и последние строки журнала; содержимого заметок нет.

```powershell
$r = "$env:USERPROFILE\Desktop\notio-diag.txt"
& {
  "== run"
  $p = Start-Process "C:\Program Files\Notio\Notio.exe" -PassThru; $null = $p.Handle
  if ($p.WaitForExit(15000)) { "ended, exit code " + $p.ExitCode + ", after " + [int]($p.ExitTime - $p.StartTime).TotalMilliseconds + " ms" } else { "still running after 15 s" }
  "== version"
  Get-ChildItem 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall' | ForEach-Object { $v = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue; if ($v.DisplayName -like 'Notio*') { $v.DisplayName + " " + $v.DisplayVersion + " " + $v.InstallDate + " " + $_.PSChildName } }
  "== installer events"
  Get-WinEvent -FilterHashtable @{LogName='Application'; ProviderName='MsiInstaller'; StartTime=(Get-Date).AddDays(-7)} -ErrorAction SilentlyContinue | Where-Object Message -like '*Notio*' | ForEach-Object { "{0}  {1}  {2}" -f $_.TimeCreated, $_.Id, $_.Message }
  "== program"
  Get-ChildItem "C:\Program Files\Notio","C:\Program Files\Notio\app" -ErrorAction SilentlyContinue | ForEach-Object { "{0}  {1}  {2}" -f $_.LastWriteTime, $_.Length, $_.FullName }
  "== cfg"
  Get-Content "C:\Program Files\Notio\app\Notio.cfg" -ErrorAction SilentlyContinue
  "== data"
  Get-ChildItem "$env:LOCALAPPDATA\Notio" -Force -ErrorAction SilentlyContinue | ForEach-Object { "{0}  {1}  {2}" -f $_.LastWriteTime, $_.Length, $_.Name }
  "== log starts"
  Select-String -Path "$env:LOCALAPPDATA\Notio\logs\app*.log" -Pattern 'app: start' -ErrorAction SilentlyContinue | Select-Object -Last 5 | ForEach-Object Line
  "== log tail"
  Get-Content "$env:LOCALAPPDATA\Notio\logs\app.log" -Tail 8 -ErrorAction SilentlyContinue
  "== last error"
  Get-Content "$env:LOCALAPPDATA\Notio\last_error.txt" -ErrorAction SilentlyContinue
  "== pending"
  (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations | Where-Object { $_ -like '*Notio*' }
} *> $r; notepad $r
```

Как читать:

- `== run`: `still running` — Notio работает (окно может быть в трее); `exit code 0` за доли секунды — он решил, что уже
  запущен; `exit code 1` за доли секунды — не смогла стартовать сама программа (файлы повреждены; до 0.9.35); `exit code 3` —
  Notio сам нашёл, что его файлы повреждены (с 0.9.35, строка FATAL в журнале); `exit code 4` — Notio уже запущен, но не
  отвечает (с 0.9.35, строка ERROR в журнале; завершить процесс в Диспетчере задач); `exit code 10` — падение,
  текст в `== last error`.
- `== program` и `== cfg`: каждая строка `app.classpath=$APPDIR\<имя>.jar` из `Notio.cfg` должна иметь файл в папке `app`.
  Файлов нет — установка повреждена.
- `== log starts`: строка `app: start <версия>` пишется при каждом запуске, дошедшем до журнала.
- `== version`: больше одной строки — в Windows записаны две установки Notio.

## 5. Разобранные случаи

### 2026-10-05 — Notio 0.9.34 не запускается после перезагрузки (компьютер владельца, не машина разработки)

Что видели и делали, по порядку:

1. Жалоба: после перезагрузки компьютера Notio не запускается.
2. `app.log`: последние строки — 4 октября, 23:02 (предупреждения `the window was busy`, к запуску не относятся). После
   перезагрузки нет ни строки `app: start`, ни ошибки: до журнала Notio не доходил.
3. `Get-Process Notio` — пусто: процесса нет, значит блокировку `instance.lock` никто не держит (её держит только живой процесс).
4. Автозапуск записан: `"C:\Program Files\Notio\Notio.exe" --autostart`; в `StartupApproved\Run` записи нет — Windows его не отключала.
5. `Start-Process "C:\Program Files\Notio\Notio.exe"` — окна нет, процесса нет; ошибок Notio в журнале приложений Windows нет.
6. Отчёт из раздела 4: `ended, exit code 1, after 96 ms`. В `C:\Program Files\Notio\app` 9 файлов: `Notio.cfg`, `notio.aot`,
   `icudtl.dat`, `skiko-windows-x64.dll`, jar-файлы самого Notio (`desktopApp-jvm-…`, `windowsShared-jvm-…`,
   `skiko-awt-runtime-…`). Около 60 библиотек из `Notio.cfg` (`kotlin-stdlib`, Compose, `jna`, `sqlite-jdbc` и другие) отсутствуют.
   Уцелевшие файлы датированы 04.10 20:58 (время сборки), папки `runtime` и `app\resources` — 04.10 21:15 (установка),
   папка `app` изменена 05.10 01:31:20.

**Вывод.** Установка повреждена: 5 октября в 01:31 из папки программы исчезли библиотеки, без них Java завершается за 0,1 секунды
с кодом 1, не дойдя до кода Notio — поэтому нет ни окна, ни журнала, ни сообщения. Данные (`%LOCALAPPDATA%\Notio`) целы.
С автозапуском и блокировкой это не связано.

**Не установлено:** что удалило файлы в 01:31. Удалять из `C:\Program Files` может только процесс с правами администратора —
установщик Windows (вторая установка или удаление Notio), антивирус или чистильщик. Запрос версии в первом отчёте упал, поэтому
неизвестно, сколько установок Notio записано в Windows; в блоке раздела 4 запрос исправлен и добавлены события установщика.

**Лечение:** запустить установщик 0.9.34 ещё раз; если он откажется — удалить Notio в «Приложениях» Windows и поставить заново
(данные остаются; выключатель «Запускать вместе с Windows» после удаления включить снова).

**Причина по разбору установщика** (не воспроизведена: нужны установка поверх работающего Notio и перезагрузка). Новую версию
поставили, пока Notio работал (на вопрос «Закрыть Notio сейчас?» ответили «Нет»). Удаление старой версии не смогло стереть
занятые файлы и отложило это до перезагрузки. Новая версия нашла на месте библиотеки, которые между версиями не менялись
(то же имя и то же содержимое), и не стала писать их заново. Перезагрузка их удалила. Изменившиеся файлы были записаны заново
и уцелели — это и есть девять оставшихся.

**Что сделано в коде (Windows D91 и D95, версия 0.9.35):**

- установщик пишет каждый файл заново, что бы ни лежало на месте (`REINSTALLMODE=amus` в `main.wxs`);
- Notio начинается с класса `Launcher` на Java, которому не нужны библиотеки: он проверяет, что все файлы программы на месте.
  Если нет — окно «Notio не может запуститься: файлы программы отсутствуют или повреждены. Установите Notio заново. Заметки и
  настройки останутся на месте», строка `FATAL app: the installation is damaged: …` в `app.log` и код завершения 3.

- второй запуск больше не молчит (D95): в `app.log` строка `INFO app: a second start: the running Notio was asked to show its
  window`; если запущенный Notio не отвечает — строка `ERROR app: a second start: another Notio holds the data folder and does
  not answer`, окно «Notio уже запущен, но не отвечает…» и код завершения 4;
- ошибка раньше, чем включились журнал и окно ошибки: строка `FATAL app: the start failed before the window:` с полным текстом
  ошибки, окно с её первыми строками, код завершения 10.

**Не проверено:** установка 0.9.35 поверх работающего 0.9.34 с ответом «Нет» и перезагрузкой.

## Android: что будит телефон и сколько это стоит (Android D345)

**Журнал приложения** — Настройки → О приложении → Журнал, кнопка «Копировать». По умолчанию он очищается раз в сутки,
поэтому смотреть его утром после ночи с включёнными напоминаниями. Строки, по которым видно расход:

| Строка | Что значит |
| --- | --- |
| `app: started, version …` | Android запустил процесс Notio. Много таких строк за ночь — телефон будили часто |
| `reminder: an alarm went off (ab12cd34): rings, sound, the alarm screen, …` | сработал будильник напоминания; в конце — как оно повторяется (`again every 5 min until Done`, `extra ring 2 of 5`) |
| `reminder: the alarms are set again: TIME_SET` | телефон сообщил о смене часов (также `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED`). Если `TIME_SET` идёт каждые несколько минут — это телефон, и это стоит батареи |
| `reminder: the melody plays` / `the melody ended after 60 s` | мелодия и сколько секунд она держала телефон бодрствующим |
| `sync: the service waits for a sync in the background` | включено «Всегда готов к синхронизации»: служба работает всё время |
| `budget: the time of a merge came …` | сработал будильник слияния бюджета |

**Статистика батареи самого телефона** (нужен кабель и включённая отладка по USB; `adb` — в `platform-tools` Android SDK):

```powershell
adb shell dumpsys batterystats --reset                 # вечером: начать отсчёт
# ночь с включёнными напоминаниями, телефон не на зарядке
adb shell dumpsys batterystats com.notebudget.app > notio-battery.txt
adb shell dumpsys alarm | Select-String -Context 0,6 "com.notebudget.app" > notio-alarms.txt
adb shell dumpsys deviceidle | Select-String "notebudget"
```

В `notio-battery.txt` смотреть: `Wake lock` и `Wakeup alarm` (сколько раз Notio будил телефон), `Foreground services`
(время службы синхронизации и мелодии), `Wifi` / `Multicast` (поиск и объявление в сети), `Screen` (время экрана
будильника). В `notio-alarms.txt` — сколько будильников стоит и на какое время.
