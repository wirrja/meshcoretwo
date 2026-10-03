# Changelog

Notable user-facing changes to MeshCore Two, release by release, in Russian and English
(from 1.1.19). Dates are UTC+3 (Moscow).

## 1.2 — 2026-10-03

### Русский

**Новое оформление: экспериментальные темы.** В «Настройки → Оформление» рядом с пятью прежними
темами (Default, Aurora, Sunrise, Graphite, Ultraviolet — они остались как были) появился шестой
квадрат со стрелкой — «Экспериментальные темы». За ним 15 новых тем в трёх группах. Тема
применяется сразу, как и раньше; вернуться к прежнему оформлению — выбрать любую из пяти. Пока
включена экспериментальная тема, шестой квадрат отмечен галочкой и подписан её названием. Темы
экспериментальные: их вид ещё может меняться от версии к версии.

**Функциональные** — сами выбирают светлый или тёмный режим, переключатель «Системная / Светлая /
Тёмная» при них выключен:

- **Night** — только красный свет на чёрном, чтобы не сбивать ночное зрение в поле, на крыше у
  ретранслятора или в машине. Красными становятся и статусы, и цвета имён, и карта: она рисуется
  инвертированной в красный — светлая подложка становится почти чёрной, дороги, подписи и метки
  узлов — красными.
- **Daylight** — максимальный контраст для экрана под прямым солнцем: чисто белый фон, чёрный текст,
  чёткие рамки вместо теней (на солнце тени не видны) и более плотный шрифт.

**Цветные** — как прежние темы: меняются только цвета, есть светлый и тёмный вариант:

- **Taiga** — мох и хвоя, спокойный зелёный.
- **Amber** — тёплый медовый.
- **Garnet** — глубокий малиновый.
- **Orchid** — приглушённый розово-сиреневый.

**С характером** — кроме цвета меняют фон, форму карточек и шрифт:

- **Mesh** — электрический циан и узор из узлов сети за заголовком; в тёмной версии карточки
  мягко светятся.
- **Blueprint** — синька: миллиметровая сетка на фоне, острые углы, моноширинные заголовки.
- **Topo** — бумажная карта: горизонтали рельефа и заголовки с засечками.
- **Phosphor** — зелёный экран старого терминала: моноширинный шрифт, строки развёртки,
  светящийся текст. Всегда тёмная.
- **Neon** — синтвейв: перспективная сетка до горизонта под полосатым закатным солнцем,
  малиновое свечение карточек и заголовков. Всегда тёмная.
- **Hazard** — жёлто-чёрная сигнальная лента внизу экрана, толстые рамки, жирные заголовки,
  исходящие сообщения — жёлтые «наклейки».
- **Notebook** — в светлой версии тетрадный лист с линовкой, красным полем и синими «чернилами»,
  в тёмной — школьная доска с мелом. Заголовки рукописным шрифтом, если он есть в системе
  телефона.
- **Newsprint** — газета: растровые точки, весь текст с засечками, прямые углы, тонкие чёрные
  линии и красный акцент.
- **Starmap** — в светлой версии звёздный атлас, в тёмной — ночное небо: звёзды и созвездия на
  фоне, золото на тёмно-синем.

Шрифты только системные — в приложение ничего не добавлено. Контраст текста во всех новых темах
проверяется автоматически: обычный текст — не ниже стандарта WCAG AA, подписи на кнопках, чипах и
плавающих кнопках — заметно контрастнее, чтобы не сливаться с заливкой.

**Исправлено:** название выбранной темы в «Оформлении» на некоторых темах почти не читалось
(например, в тёмной Aurora).

### English

**A new look: experimental themes.** Settings → Appearance now has a sixth tile with an arrow —
"Experimental themes" — next to the five themes you know (Default, Aurora, Sunrise, Graphite,
Ultraviolet, all unchanged). It opens 15 new themes in three groups. A theme applies as soon as
you tap it; to go back to the classic look, pick any of the five. While an experimental theme is
on, the sixth tile shows a check mark and the theme's name. These themes are experimental: their
look may still change from version to version.

**Functional** — they pick light or dark themselves, so the System / Light / Dark switch is off
while one of them is active:

- **Night** — nothing but red light on black, to keep your night vision in the field, on a roof by
  the repeater or in the car. Statuses, name colors and the map turn red too: the map is drawn
  inverted into red, so the light basemap goes almost black and roads, labels and node markers
  are red.
- **Daylight** — maximum contrast for a screen in direct sunlight: pure white, black text, crisp
  outlines instead of shadows (shadows vanish in the sun) and a heavier font.

**Colors** — like the classic themes, only the colors change, in light and dark:

- **Taiga** — moss and pine, a calm green.
- **Amber** — warm honey.
- **Garnet** — deep raspberry.
- **Orchid** — a muted pink-lilac.

**With character** — besides color they change the background, the card shapes and the fonts:

- **Mesh** — electric cyan and a mesh-network pattern behind the header; in dark, cards softly
  glow.
- **Blueprint** — a drafting grid in the background, sharp corners, monospace headings.
- **Topo** — a paper map: relief contour lines and serif headings.
- **Phosphor** — an old terminal's green screen: monospace type, scanlines, glowing text. Always
  dark.
- **Neon** — synthwave: a perspective grid running to the horizon under a striped sunset sun,
  magenta glow on cards and headings. Always dark.
- **Hazard** — a yellow-and-black hazard-tape band at the bottom of the screen, thick outlines,
  heavy headings, outgoing messages as yellow "stickers".
- **Notebook** — ruled notebook paper with a red margin and blue "ink" in light, a chalkboard in
  dark. Headings in a handwritten font, if the phone's system has one.
- **Newsprint** — a newspaper: halftone dots, serif type throughout, square corners, thin black
  rules and a red accent.
- **Starmap** — a star atlas in light, the night sky in dark: stars and constellations in the
  background, gold on navy.

Fonts are the phone's own — nothing was added to the app. Text contrast in every new theme is
checked automatically: regular text meets at least WCAG AA, and labels on buttons, chips and
floating buttons get noticeably more contrast so they don't blend into the fill.

**Fixed:** in Appearance, the selected theme's name was barely readable on some themes (dark
Aurora, for example).

## 1.1.23 — 2026-10-03

### Русский

- **Трассировка пути на карте.** В «Инструменты → Трассировка пути» вверху появился переключатель
  «Список / Карта». На карте путь собирается нажатиями на репитеры — и из контактов, и найденные
  поиском узлов; повторное нажатие на последний hop убирает его. Трассировку можно запустить прямо
  с карты: после ответа каждый участок пути раскрашивается по SNR, который сообщил его hop.
  Кнопки очистки пути, запуска и результатов плавают над картой.
- **Высоты для «Прямой видимости» без VPN.** Профиль рельефа раньше загружался только с
  Open-Meteo, а этот сервис из России работает с перебоями — поэтому анализ получался, только
  когда был включён VPN. Теперь высоты берутся из тайлов рельефа Mapterhorn с того же сервера
  VersaTiles, что и карта VersaTiles, и хранятся в кэше на телефоне; Open-Meteo остался
  запасным вариантом. Под профилем указан источник данных, а ошибки загрузки высот переведены.
- **Компас на карте** при повороте больше не прячется под строкой поиска узлов.

### English

- **Trace Path on a map.** Tools → Trace Path now has a List / Map switch at the top. On the map
  you build the path by tapping repeaters, both contacts and ones found by node discovery; tapping
  the last hop again takes it back. The trace runs straight from the map: once it answers, each
  segment of the path is colored by the SNR its hop reported. Clearing the path, running the trace
  and opening the results are floating buttons over the map.
- **Line of Sight elevations without a VPN.** The terrain profile used to come only from
  Open-Meteo, which is unreliable from Russia, so the analysis only worked with a VPN on.
  Elevations now come from Mapterhorn terrain tiles on the same VersaTiles server as the
  VersaTiles basemap and are cached on the phone; Open-Meteo stays as the fallback. The profile
  credits the data source it used, and elevation loading errors are translated.
- **The map's compass** no longer hides under the node search bar when the map is rotated.

## 1.1.22 — 2026-10-03

### Русский

- **Подключение больше не «залипает» до перезапуска Bluetooth.** Если связь с радио пропадала
  надолго (нода выключена или вне зоны), а приложение оставалось в фоне, каждая попытка
  переподключения оставляла в системе незакрытое Bluetooth-соединение. Через какое-то время
  их общий лимит в телефоне заканчивался, и подключиться не удавалось ни к одной ноде, пока не
  перезапустишь Bluetooth. Теперь каждая неудачная попытка закрывается, а между попытками есть
  пауза.
- **Можно уйти с экрана, пока идёт подключение.** Раньше, если нажать «Подключить» в сохранённых
  устройствах и сразу вернуться назад, подключение обрывалось на полпути: на экране навсегда
  оставалось «Подключение к устройству…», хотя радио было подключено. Теперь подключение
  доводится до конца, на каком бы экране вы ни были.
- **Своё соединение больше не принимается за чужое.** После такого обрыва радио могло
  показываться как «Подключено в другом приложении», и кнопка «Подключить» не работала.
  Теперь приложение узнаёт собственное соединение, закрывает его и подключается заново.
- **Счётчик синхронизации:** пока при подключении загружаются сообщения, накопившиеся на
  радио, под значком подключения видно «Синхронизация сообщений: N». Общего числа нет — радио
  не сообщает заранее, сколько сообщений у него в очереди.

### English

- **Connecting no longer gets stuck until Bluetooth is restarted.** When the radio was out of
  reach for a long time (switched off or out of range) while the app stayed in the background,
  every reconnect attempt left a Bluetooth connection open in the system. After a while the
  phone's shared limit ran out, and no radio would connect until Bluetooth was restarted. Each
  failed attempt is now closed, and attempts are spaced out.
- **You can leave the screen while connecting.** Previously, tapping Connect in saved devices
  and going straight back cut the connection off halfway: "Connecting to device…" stayed on
  screen forever even though the radio was connected. The connection now finishes whichever
  screen you're on.
- **The app no longer mistakes its own connection for another app's.** After such a cut-off,
  the radio could show as "Connected in another app" with a Connect button that did nothing.
  The app now recognizes its own connection, closes it and connects again.
- **Sync counter:** while messages stored on the radio are downloaded during connection, the
  connecting screen shows "Syncing messages: N". There is no total: the radio doesn't report
  how many messages it has queued.

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
