# MeshCore Two

MeshCore Two is an unofficial Android app derived from [MeshCore One](https://github.com/Avi0n/MeshCoreOne), the iOS/macOS
messaging client for [MeshCore](https://meshcore.io) LoRa mesh radios.

> **This project is not affiliated with, endorsed by, or supported by the author of MeshCore One
> or the MeshCore project.** Please report problems with this app here, not in the MeshCore One
> repository.

Work in progress: not every feature of the iOS app has been ported yet.

## Origin and modifications

This program is a derivative work of **MeshCore One**, Copyright (C) 2025–2026 Avi0n and MeshCore
One contributors, licensed under the GNU General Public License v3.0
(<https://github.com/Avi0n/MeshCoreOne>).

Changes relative to the original, made starting 2026-09-05:

- The Swift source was rewritten in Kotlin for Android (Jetpack Compose, Room, Android BLE APIs),
  using MeshCore One as of version **v1.4.0** (commit `3846d569`) as the reference. Later upstream
  changes may be ported selectively. Most ported source files name the Swift file they were ported
  from in their documentation comment.
- In-app purchases and the store were removed; everything is free.
- The UI is available in English (default), Russian, French, German, Simplified Chinese, Turkish,
  Finnish and Swedish; the language follows the system setting and can be changed under
  Settings → Appearance → Language. Parts of the ru/fr/de/zh translations are adapted from MeshCore
  One (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
- Google Play Services are not used: maps via MapLibre, location via `LocationManager`, local
  notifications only.
- Features without an Android counterpart (Live Activities, App Intents, widgets, …) are omitted
  or replaced.

The protocol layer (`protocol` module) is ported from the Swift MeshCore package bundled in the
MeshCore One repository (MIT License), which is itself a port of
[meshcore_py](https://github.com/meshcore-dev/meshcore_py) (MIT License). Their notices, and the
licenses of the libraries included in the app, are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

Copyright (C) 2025–2026 Avi0n and MeshCore One contributors<br>
Copyright (C) 2026 wirrja

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
Public License for more details.

You should have received a copy of the GNU General Public License along with this program (see
[LICENSE](LICENSE)). If not, see <https://www.gnu.org/licenses/>.

SPDX-License-Identifier: `GPL-3.0-only`

## Source code of released builds

Every distributed APK is built from a tagged commit of this repository; the tag matching the app
version is the complete corresponding source, including the Gradle build scripts. Building it
requires no proprietary components and no Google Play Services.

## Building

Requirements: JDK 17 and the Android SDK (compile SDK 35).

```sh
./gradlew build                 # all modules: compile, unit tests, lint
./gradlew :app:assembleDebug    # debug APK
```

Release signing reads `keystore.properties` (not committed); see `keystore.properties.example`.

## Names

"MeshCore" and "MeshCore One" are the names of their respective projects, and are used here only to
describe where this app comes from and what it is compatible with. "MeshCore Two" is the name of this
independent app; it is not a release or successor endorsed by either project.
