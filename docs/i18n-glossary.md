# Localization glossary

Languages: en (default), ru, fr, de, zh-CN, tr, fi, sv. Strings live in `app/src/main/res/values*/strings.xml`.

## Never translated (stay in English/Latin in every language)

Radio and protocol terms: **RX, TX, SNR, RSSI, CR, SF, BW**, coding/spreading/bandwidth parameter
names, **LoRa, BLE, WiFi, GPS, MeshCore, ACK, hop/hops** (as a metric label), **flood, direct, ping**,
**ID, pubkey, hex, hash**, units (**bytes, B, KB, dB, dBm, MHz, kHz, ms, s**), radio preset and region
names, channel names (`#connections`), repeater CLI commands.

A technical term inside a translated sentence stays Latin: `Получено: 12 bytes, SNR 7.5 dB`.
A string consisting only of such terms gets `translatable="false"` in `values/strings.xml`.

## Rules

- Positional placeholders only (`%1$s`, `%2$d`); never concatenate translated fragments — word order
  differs between languages. Counts use `<plurals>` (CLDR rules: ru one/few/many/other, fi/sv/de/tr
  one/other, fr one(0,1)/many/other, zh other).
- Language names in the picker are endonyms (`AppLanguage.nativeName`) and are not translated.
- Text produced outside Compose (ViewModels, services, notifications) is carried as `UiText` or
  resolved with `Context.getString` at the moment of display, never cached as a `String`.
- The Turkish, Finnish and Swedish translations are original work written for this project. The
  Russian, French, German and Simplified Chinese ones were written for this project too, but a
  comparison with MeshCore One's localization (GPL-3.0) found many identical strings, so they are
  treated as partly adapted from it and credited in `THIRD_PARTY_NOTICES.md`. None of them have been
  reviewed by native speakers — corrections are welcome.
