# Работа в фоне и батарея — правила и откуда они

Записано 2026-10-10 по указанию владельца («самое важное — оптимально работать в фоновом режиме, не сажать аккумулятор;
найти в интернете самые оптимальные решения и применить»). Источники — руководства самого Android; что из них взято и
как это сделано в Notio. Решение — Android D353. Те же правила записаны в проект FitEasy.

## Источники

- [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule) — какие будильники
  бывают и какой когда брать.
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) — что
  телефон запрещает спящему приложению и что остаётся.
- [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager) — поиск устройств в сети.
- [Excessive partial wake locks](https://developer.android.com/topic/performance/vitals/excessive-wakelock) и
  [Battery Technical Quality Enforcement](https://android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html)
  — что Android считает чрезмерным расходом.
- [Monitor your app's technical quality with Android vitals](https://support.google.com/googleplay/android-developer/answer/9844486?hl=en)
  — пробуждения.

## Правила

| № | Правило | Что говорит источник | В Notio |
| --- | --- | --- | --- |
| 1 | В фоне нет таймеров, опросов и своей службы «чтобы жить». То, что должно случиться в срок, — будильник `AlarmManager` | «Don't start a foreground service just to prevent the system from determining that your app is idle» | Так: будильник на напоминание и на слияние бюджета, больше ничего |
| 2 | Неточный будильник везде, где минута не важна; точный — только для того, что человек сам назначил на минуту | «It's highly recommended that you create an inexact alarm whenever possible» | Напоминание — `setExactAndAllowWhileIdle`; в режиме экономии батареи (D308) — неточный `setAndAllowWhileIdle` |
| 3 | `setAlarmClock` не брать без нужды: телефон выходит из сна перед каждым таким будильником | «can significantly affect the device's resources, such as battery life» | Не используется |
| 4 | В глубоком сне телефон отдаёт будильники одного приложения не чаще раза в 9 минут; на это нельзя рассчитывать чаще | «…cannot fire alarms more than once per nine minutes, per app» | Напоминание с повтором чаще девяти минут на лежащем телефоне приходит с опозданием — так устроен Android |
| 5 | Будить телефон редко: больше 10 пробуждений в час Android vitals считает чрезмерным | Android vitals, «more than 10 wake-ups per hour» | Одно пробуждение на звонок. Напоминание с повтором чаще раза в 6 минут превышает это — так его настроил человек |
| 6 | Своих блокировок сна нет. Больше 2 часов частичной блокировки за сутки — чрезмерно; звук не считается | Android vitals, «more than two hours in a 24-hour period» | Своих нет; телефон бодрствует только пока играет мелодия (до минуты) |
| 7 | Служба переднего плана — только для того, чего человек ждёт прямо сейчас | «Only use a foreground service for tasks the user expects the system to execute immediately or without interruption» | Мелодия на свою минуту, запись, и ожидание синхронизации, которое человек сам включил переключателем (по умолчанию выключено) |
| 8 | Поиск устройств в сети останавливать, когда приложение не на экране | NsdManager: stopServiceDiscovery «whenever the application is paused or stopped»; поиск «will continue until the application calls stopServiceDiscovery» | Так с D345 |
| 9 | Объявление себя в сети (mDNS) тоже снимать в фоне: пока оно есть, Wi-Fi отдаёт телефону каждый групповой пакет сети | NsdManager: регистрация и поиск — отдельные вызовы, каждый снимается своим | Так с D353: в фоне ждёт только порт, сопряжённое устройство приходит по адресу прошлой синхронизации |
| 10 | Экран — самое дорогое: держать его не дольше, чем нужно | — (общее правило расхода) | Окно будильника держит экран 60 секунд, на втором неотвеченном звонке 15, дальше по 5 (D331, D351) |
| 11 | Не просить вывести приложение из оптимизации батареи | Doze: исключение допустимо только когда «the core function of the app is adversely affected» | Не просим; приложение работает и при ограничении батареи |
| 12 | После перезагрузки будильники ставить заново, и только это | «By default, all alarms are canceled when a device shuts down» | `RescheduleReceiver`: одно чтение напоминаний и бюджетов |
| 13 | В сне сети нет: фоновая работа не должна на неё рассчитывать | Doze: «Suspends network access» | Синхронизация в фоне отвечает, когда телефон проснулся; написано под переключателем |
| 14 | Процесс, разбуженный будильником, делает только то, за чем пришёл | — (следствие 1 и 5) | Уборка корзины — не чаще раза в сутки, счётчик задач читает только настройки задач (D221, D331) |

## Что не сделано и почему

- **Запуск по «часы переведены».** Телефон сам переставляет будильники по времени суток при смене часов; Notio при
  этом всё равно запускается и ставит их заново. Некоторые телефоны шлют это сообщение каждые несколько минут. Убирать
  обработку без данных не стал: журнал приложения (D345) теперь пишет каждую такую строку — по нему и решать.
- **Выключать приёмник перезагрузки, когда нет ни одного будильника.** Руководство это советует; выигрыш — один запуск
  при включении телефона, цена ошибки — потерянные напоминания. Не сделано.

## Как проверить на телефоне

Команды — в `docs/DIAGNOSTICS_RU.md` («Android: что будит телефон и сколько это стоит»). Проверка сна:

```powershell
adb shell dumpsys deviceidle force-idle      # усыпить телефон
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```
