# Changelog

Notable user-facing changes to MeshCore Two, release by release, in Russian and English
(from 1.1.19). Dates are UTC+3 (Moscow).

## 1.1.21 — 2026-09-26

### Русский

- **Отключение от радио без удаления:** в меню значка радиостанции (вверху справа) внизу
  появился пункт «Отключить <имя устройства>». Он только разрывает связь: устройство остаётся
  в сохранённых, Bluetooth-сопряжение не снимается, а приложение не подключается само — ни в
  фоне, ни после перезапуска, — пока вы не нажмёте «Подключить».
- **Честный статус подключения:** надпись «Подключение к устройству…» теперь показывается, только
  когда подключение действительно идёт. Если вы отключились сами, экран пишет «Радио не
  подключено» и предлагает кнопку «Подключить».

### English

- **Disconnect from the radio without removing it:** the radio icon's menu (top right) now ends
  with "Disconnect <device name>". It only drops the link: the device stays saved, its Bluetooth
  pairing is kept, and the app won't reconnect on its own — in the background or after a
  restart — until you tap Connect.
- **Honest connection status:** "Connecting to device…" now appears only while a connection is
  actually being made. After you disconnect, the screen says "Radio not connected" and offers a
  Connect button.

## 1.1.20 — 2026-09-26

### Русский

- **Карта снова грузится без VPN.** Прежний сервер карт (OpenFreeMap) работает через
  Cloudflare, а соединения с ним российские провайдеры обрывают — карта оставалась пустой.
  Теперь источник карты можно выбрать в «Настройки → Карты → Источник карты»: OpenFreeMap,
  VersaTiles, OpenStreetMap или адрес своего сервера. По умолчанию стоит «Автоматически» —
  приложение само проверяет, какой сервер отвечает в вашей сети, и берёт первый рабочий.
- **Кнопка «Проверить доступность»** на том же экране показывает, какие источники доступны
  прямо сейчас и с какой задержкой.
- **Офлайн-карты** скачиваются с текущего источника, и в списке видно, с какого. Скачанная
  область работает только с тем источником, из которого она загружена. OpenStreetMap
  запрещает массовую загрузку, поэтому с ним сохраняется только топографический слой.
- **Подписи узлов** на карте отображаются с любым источником, в том числе с растровым
  OpenStreetMap.

### English

- **The map loads again without a VPN.** The previous map server (OpenFreeMap) is served
  through Cloudflare, which Russian ISPs cut off, so the map stayed blank. You can now choose
  the map source in Settings → Maps → Map source: OpenFreeMap, VersaTiles, OpenStreetMap, or
  your own server's URL. The default, "Automatic", checks which server answers on your network
  and uses the first one that works.
- **"Check availability"** on the same screen shows which sources are reachable right now and
  how fast.
- **Offline maps** are downloaded from the current source, and the list shows which one. A
  downloaded area only works with the source it came from. OpenStreetMap forbids bulk
  download, so with it only the topographic layer can be saved.
- **Node labels** on the map show up with every source, including raster OpenStreetMap.

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
