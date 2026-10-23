# Custom TV Launcher (Tiles)

A lightweight Windows Phone style home screen for Android TV, written for low-end TVs (1 GB of RAM).
Every app is a flat tile in its own colour, the clock is a live tile, and the whole layout can be rearranged
from the remote.

![Home screen](docs/home.png)

| The clock tile flips to show the day | OK on the clock opens a full-screen clock |
|---|---|
| ![Clock tile flipped](docs/clock-flipped.png) | ![Full-screen clock](docs/full-screen-clock.png) |

(The screenshots were taken before the clock switched to 12-hour time with AM/PM.)

## Features

- **Tiles in four sizes**: small, medium, wide and large, packed Windows Phone style on a 10-unit grid (5 medium
  tiles per row).
- **Arrange it from the remote**: hold **OK** on a tile (or press **Menu**) to move, resize or hide it, show hidden
  apps again, open App info, or reset the layout. The layout is saved on the TV.
- **A live clock tile**, 12-hour with AM/PM. Every 15 seconds it flips over to show the day and date. **OK** on it
  opens a full-screen clock that drifts slightly every minute so nothing burns into the panel.
- **Colour per app**, taken from the app's own artwork or set in `config.xml`, and optional **white glyph icons**
  for a uniform look; apps without a glyph keep their own icon.
- **A greeting** (good morning / afternoon / evening / night) and a subtle black-to-navy background.
- **Two small top buttons**, Inputs and Wi-Fi (showing the network name), plain text until selected, then a white
  pill. No glass, blur or shadows anywhere.
- **Predictable D-pad navigation**: the ends of the top bar don't fall through to the tiles, and focus survives the
  remote's mouse mode (Android "touch mode").

## Lightweight

Plain Java and the Android framework's own views: no AndroidX, no libraries, no network access, no background
services. The APK is about 90 KB. Icons and colours are loaded once and only reloaded for apps that changed;
each icon is drawn once into a bitmap at its tile size.

Measured on the TV it was built for (HiSilicon Hi3751V350, Android 12, 1 GB RAM), compared with the launcher it
replaced, for the same 13 remote presses:

| | Tiles | Previous launcher |
|---|---|---|
| Cold start | 0.68 s | 1.16 s |
| Frame time, median / 90th percentile | 8 / 10 ms | 19 / 22 ms |
| Janky frames | 0 | 4 |
| Memory (PSS) | 23.5 MB | 25.0 MB |

## Requirements

- An Android TV device on Android 8.0 (API 26) or newer, with USB or network debugging switched on
- To build: JDK 17 (Android Studio's bundled `jbr` works) and the Android SDK with platform 34

## Build

```bash
cd tiles
./gradlew assembleRelease
```

The APK is `tiles/app/build/outputs/apk/release/app-release.apk` (signed with the debug key; it is meant for
sideloading, not for a store).

## Install

With [adb](https://developer.android.com/tools/adb):

```bash
adb connect <tv ip>:5555
adb install -r tiles/app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.tvtiles.home android.permission.ACCESS_FINE_LOCATION
adb shell cmd package set-home-activity --user 0 com.tvtiles.home/.MainActivity
```

The location permission is only there because Android hides the Wi-Fi network name without it.

On Windows, `install-tiles.ps1` builds and installs in one step. It uses the ADB tools from
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) (a pure-Python ADB client, more reliable than `adb push`
on some TVs): clone it into this folder as `cleanyourtv/` and set up its `tools/venv` as its README describes, then
run `.\install-tiles.ps1 -TvHost <tv ip>`. `-NoHome` installs without pressing Home (someone is watching).

## Customise

Everything TV-specific is in `tiles/app/src/main/res/values/config.xml`:

| Key | What it sets |
|---|---|
| `inputs_component` | What the Inputs button opens. The default is the source menu of Cultraview/HiSilicon TVs; other TVs fall back to Android's standard input picker |
| `default_layout` | Tile order and sizes on first start, as `package:size` (`clock` is the clock tile) |
| `excluded_packages` | Apps never shown, such as other launchers |
| `tile_colors` | A colour per app instead of the sampled one |
| `tile_icons` | A white glyph (`res/drawable/ic_tile_*.xml`) instead of the app's own icon |
| `tile_labels` | A tile name instead of the app's own |
| `clock_color`, `clock_flip_seconds` | The clock tile's colour, and how often it flips (0 keeps it still) |
| `greeting_name` | A name after the greeting |
| `background_colors` | The background gradient |

## Credits

- [Outfit](https://github.com/Outfitio/Outfit-Fonts) font, SIL Open Font License 1.1 (`tiles/FONT-LICENSE-OFL.txt`)
- Several glyphs are based on [Material Icons](https://fonts.google.com/icons), Apache License 2.0 (noted in each file)
- The debloating and ADB tooling around it come from [cleanyourtv](https://github.com/Gathin23/cleanyourtv)

App names and brands shown on tiles belong to their owners; the glyphs are simplified symbols, not their logos.
