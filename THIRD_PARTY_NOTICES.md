# Third-party notices

MeshCore Two is licensed under GPL-3.0-only (see [LICENSE](LICENSE) and
[README.md](README.md)). It is a derivative work of
[MeshCore One](https://github.com/Avi0n/MeshCoreOne) (GPL-3.0), Copyright (C) 2025–2026 Avi0n and
MeshCore One contributors.

This file lists the other components whose code or data is included, together with their licenses.
Full license texts are in the [LICENSES](LICENSES) directory.

## Ported source code

The Kotlin `protocol` module is a translation of the following code, so their notices are reproduced
here.

| Component | License | Copyright | Full text |
|---|---|---|---|
| Swift MeshCore (the `MeshCore/` package of the MeshCore One repository) | MIT | Copyright © 2025 (no holder named in the original notice) | [LICENSES/MeshCore-Swift-MIT.txt](LICENSES/MeshCore-Swift-MIT.txt) |
| [meshcore_py](https://github.com/meshcore-dev/meshcore_py), of which Swift MeshCore is a port | MIT | Copyright (c) 2025 Florent de Lamotte | [LICENSES/meshcore_py-MIT.txt](LICENSES/meshcore_py-MIT.txt) |

## Translations

Parts of the Russian, French, German and Simplified Chinese UI strings are adapted from the
localization files of MeshCore One (GPL-3.0, Copyright (C) 2025–2026 Avi0n and MeshCore One
contributors); they are covered by this project's GPL-3.0-only license. The Turkish, Finnish and
Swedish translations are original to this project.

## Libraries included in the app

The list below covers every module of `:app:releaseRuntimeClasspath` as of 2026-09-13. Test-only
dependencies (JUnit, Robolectric, kotlinx-coroutines-test) are not shipped in the app and are not
listed. [runtime-modules.txt](runtime-modules.txt) — at the repository root, not inside `LICENSES/`,
since that directory's contents are displayed verbatim as license texts in Settings → About →
Licenses (`LicensesScreen.kt`) and this file is data, not a license — is the machine-readable,
exact-coordinate version of this same list. `RuntimeModuleListTest` (`app/build.gradle.kts`'s
`generateRuntimeModuleList` task) fails the build if the actual runtime classpath drifts from it, so
a new dependency can't silently ship without a matching update here.

### Apache License 2.0

Full text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

| Component | Maven group / artifacts |
|---|---|
| [Android Jetpack (AndroidX)](https://developer.android.com/jetpack/androidx), The Android Open Source Project | `androidx.*`: activity, annotation, arch.core, autofill, collection, compose (animation, foundation, material, material3, runtime, ui), concurrent, core, customview, datastore, emoji2, fragment, graphics, interpolator, lifecycle, loader, navigation, profileinstaller, room, savedstate, security-crypto, sqlite, startup, tracing, versionedparcelable, viewpager |
| [Kotlin standard library](https://github.com/JetBrains/kotlin), JetBrains | `org.jetbrains.kotlin`: kotlin-stdlib, kotlin-stdlib-common, kotlin-stdlib-jdk7, kotlin-stdlib-jdk8, kotlin-parcelize-runtime, kotlin-android-extensions-runtime |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), JetBrains | `org.jetbrains.kotlinx:kotlinx-coroutines-*` |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), JetBrains | `org.jetbrains.kotlinx:kotlinx-serialization-*` |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) | `org.jetbrains:annotations` |
| [JSpecify](https://jspecify.dev) | `org.jspecify:jspecify` |
| [Gson](https://github.com/google/gson) | `com.google.code.gson:gson` |
| [Tink](https://github.com/tink-crypto/tink-java) | `com.google.crypto.tink:tink-android` |
| [Guava ListenableFuture](https://github.com/google/guava) | `com.google.guava:listenablefuture` |
| [OkHttp](https://square.github.io/okhttp/), Square, Inc. | `com.squareup.okhttp3:okhttp` |
| [Okio](https://square.github.io/okio/), Square, Inc. | `com.squareup.okio:okio`, `okio-jvm` |
| [Timber](https://github.com/JakeWharton/timber), Jake Wharton | `com.jakewharton.timber:timber` |
| [ZXing ("Zebra Crossing") core](https://github.com/zxing/zxing), ZXing authors | `com.google.zxing:core` |
| [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded), JourneyApps | `com.journeyapps:zxing-android-embedded` |

NOTICE file shipped inside OkHttp (`okhttp3/internal/publicsuffix/NOTICE`), reproduced as required
by section 4(d) of the Apache License:

```
Note that publicsuffixes.gz is compiled from The Public Suffix List:
https://publicsuffix.org/list/public_suffix_list.dat

It is subject to the terms of the Mozilla Public License, v. 2.0:
https://mozilla.org/MPL/2.0/
```

### BSD 2-Clause License

| Component | Maven artifact | Full text and copyright |
|---|---|---|
| [MapLibre Native Android](https://github.com/maplibre/maplibre-native) 11.6.1, including the native `libmaplibre.so` | `org.maplibre.gl:android-sdk` | [LICENSES/MapLibre-Native-Android.md](LICENSES/MapLibre-Native-Android.md) |
| [MapLibre Gestures for Android](https://github.com/maplibre/maplibre-gestures-android), including portions of Android Gesture Detectors Framework (BSD) and the Android Support Library (Apache-2.0) | `org.maplibre.gl:maplibre-android-gestures` | [LICENSES/MapLibre-Gestures-Android.md](LICENSES/MapLibre-Gestures-Android.md) |

The native MapLibre library also contains C++ components under the ISC, BSD, MIT and Boost Software
licenses: kdbush.hpp, supercluster.hpp, shelf-pack-cpp, geojson-vt-cpp, cheap-ruler-cpp, Boost,
csscolorparser, earcut.hpp, eternal, parsedate, polylabel, protozero, unique_resource, vector-tile,
wagyu, mapbox-base, expected-lite, RapidJSON, geojson.hpp, geometry.hpp and variant. Their notices
come from MapLibre Native's `LICENSES.core.md` at tag `android-v11.6.1` and are reproduced in
[LICENSES/MapLibre-Native-core.md](LICENSES/MapLibre-Native-core.md). RapidJSON's JSON License
applies only to its `bin/jsonchecker/` directory, which is not part of the library.

### MIT License

| Component | Maven artifacts | Copyright | Full text |
|---|---|---|---|
| [MapLibre Java](https://github.com/maplibre/maplibre-java) (GeoJSON, Turf) | `org.maplibre.gl:android-sdk-geojson`, `android-sdk-turf` | Copyright (c) 2018 Mapbox | [LICENSES/MapLibre-Java-MIT.txt](LICENSES/MapLibre-Java-MIT.txt) |

The published Maven POMs of these two artifacts name Apache-2.0. The repository's LICENSE file
(MIT) is reproduced here, and both licenses are compatible with GPL-3.0.

## Bundled icon assets

Not a Maven dependency: the vector drawables in `app/src/main/res/drawable/ic_*.xml` are hand-converted from the "Material Icons" (Rounded) SVG source in
[google/material-design-icons](https://github.com/google/material-design-icons), Copyright Google
Inc., licensed under Apache License 2.0 (full text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)).

## Bundled emoji data

Not a Maven dependency: the reaction emoji picker's labels/shortcodes (`app/.../chat/emoji/EmojiCatalog.kt`)
are a small hand-curated subset (~170 of the full
dataset) derived from [emojibase-data](https://github.com/milesj/emojibase), the same underlying
dataset MeshCore One's iOS app consumes via the `matrix-org/emojibase-bindings` Swift package (see
`MC1/Settings.bundle/Packages/Emojibase.plist`, which reproduces that package's own Apache-2.0
notice — a different license from the emoji dataset it wraps, so it isn't cited here). Unicode emoji
characters themselves aren't copyrightable; only the English labels/shortcodes are reused.

| Data | Source | Copyright | Full text |
|---|---|---|---|
| Emoji labels/shortcodes | [emojibase-data](https://github.com/milesj/emojibase) | Copyright (c) 2017-2019 Miles Johnson | [LICENSES/emojibase-data-MIT.txt](LICENSES/emojibase-data-MIT.txt) |

## Data sources

Data the app displays or fetches, listed for transparency. These are terms of use of the data, not
licenses of this program's code.

| Data | Source | Terms |
|---|---|---|
| Map tiles (map, neighbor map, line of sight) | [OpenFreeMap](https://openfreemap.org) "liberty" style © [OpenMapTiles](https://openmaptiles.org), data from [OpenStreetMap](https://www.openstreetmap.org/copyright) | OpenStreetMap data: Open Database License (ODbL) 1.0 |
| Elevation profile (line of sight) | Copernicus DEM GLO-90 via the [Open-Meteo](https://open-meteo.com) Elevation API | Open-Meteo data: CC BY 4.0; the free API is for non-commercial use only. Copernicus DEM notice below (required by the [DEM licence](https://docs.sentinel-hub.com/api/latest/static/files/data/dem/resources/license/License-COPDEM-30.pdf), Article 6) |
| Battery discharge (OCV) curve presets | Values from the [Meshtastic firmware](https://github.com/meshtastic/firmware) (GPL-3.0), via MeshCore One | Numeric reference data |
| Chile radio preset | Community settings of the [MeshChile](https://meshchile.cl) network, via MeshCore One | Numeric reference data |

"produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH
2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"
