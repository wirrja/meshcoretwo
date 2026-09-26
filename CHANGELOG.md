# Changelog

Notable user-facing changes to MeshCore Two, release by release, in Russian and English
(from 1.1.19). Dates are UTC+3 (Moscow).

## 1.1.19 — 2026-09-26

### Русский

- **Первое подключение, шаг «Регион»:** вместо непонятного прочерка — кнопка «Выбрать
  регион». Под выбором региона появилась кнопка «Ручная настройка»: можно сразу задать свои
  частоту, полосу, SF, CR, мощность передатчика и path hash, не выбирая пресет.
- **Первое подключение:** приложение больше не «зависает» на экране «Добавить устройство»,
  если во время подключения повернуть телефон, — радиостанция подключалась, а переход дальше
  терялся. То же исправлено для подключения по WiFi.
- **Определение региона по местоположению** больше не висит бесконечно на некоторых
  телефонах (Android 12 и ниже, устройства без сервисов Google): не больше ~10 секунд, затем
  ручной выбор.
- **Настройки → «Ручная настройка» радио:** добавлен выбор path hash.
- **Чаты и комнаты:** над кнопкой отправки появляется круглая кнопка со стрелкой вниз, если
  вы прокрутили историю вверх, — одно нажатие возвращает к последнему сообщению.
- **Сохранённые устройства:** крестик теперь спрашивает подтверждение, отключает устройство,
  если оно подключено, и снимает его Bluetooth-сопряжение с телефоном — при повторном
  добавлении радиостанция снова спросит PIN. Если Android не даст снять сопряжение,
  приложение предложит открыть настройки Bluetooth.
- **Управление регионами → «Найти ближайшие регионы»:** вместо голого «Поиск…» видно, сколько
  репитеров ответило и сколько из них уже опрошено; найденные регионы появляются в списке
  сразу, а поиск можно остановить кнопкой «Остановить» — найденное сохранится.
- **Экран приветствия:** убрана строка о неофициальном порте; сведения о происхождении
  приложения и лицензии остаются в «Настройки → О приложении».

### English

- **First-time setup, Region step:** the bare dash is replaced by a "Set region" button. A new
  "Manual settings" button under the region picker lets you enter your own frequency,
  bandwidth, SF, CR, TX power and path hash right away instead of picking a preset.
- **First-time setup:** no longer gets stuck on the "Add Device" screen when the phone is
  rotated while connecting — the radio connected, but the step forward was lost. Connecting
  over WiFi got the same fix.
- **Detecting your region from location** no longer hangs indefinitely on some phones
  (Android 12 and older, devices without Google services): at most ~10 seconds, then the
  manual picker.
- **Settings → "Manual settings" (radio):** path hash can now be set there.
- **Chats and rooms:** once you scroll up through history, a round down-arrow button appears
  above the send button — one tap takes you back to the latest message.
- **Saved Devices:** the remove (×) button now asks for confirmation, disconnects the device if
  it's connected, and removes its Bluetooth pairing from the phone, so adding it again asks
  for the PIN. If Android refuses to unpair, the app offers to open Bluetooth settings.
- **Manage Regions → "Discover Nearby Regions":** instead of a bare "Discovering…", you see how
  many repeaters answered and how many have been asked so far; regions show up in the list as
  soon as they're found, and a "Stop" button ends the search while keeping what was found.
- **Welcome screen:** the "unofficial port" line is gone; the app's origin and license
  details remain under Settings → About.

## 1.1.18 — 2026-09-22

- **Chat:** conversations no longer cap out at the newest 100 messages — scrolling up now
  loads older history. Opening a conversation also jumps straight to the first unread
  message (with a "New Messages" divider) instead of always landing at the bottom.
- **Repeater/Room status:** the battery/RSSI trend arrow no longer points down for a reading
  that hasn't actually changed since the last visit.
- **Repeater/Room settings — Identity & Location:** fixed the Longitude field getting stuck
  permanently empty on some connections. Added a "Pick on Map" button to set a node's
  location from a map instead of typing coordinates by hand.
- **Contacts:** the list now opens on the Favorites tab. Fixed node names being truncated
  more aggressively than necessary, leaving a dead gap before the timestamp.
- **Maps:** repeater/neighbor/location pins with labels now draw correctly (a missing font
  reference on some map styles was silently failing the whole marker layer).
- **CLI:** the on-screen keyboard no longer covers the command input on the node CLI screen;
  fixed a crash triggered by the command autocomplete list.

## Earlier releases

Not tracked here yet — see the GitHub release list.
