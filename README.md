# OpenParsec Quest 2 Build

This repository is a lightly adapted Quest 2 build of
[NomadsGalaxy's Android OpenParsec client](https://github.com/nomadsgalaxy/OpenParsec).
NomadsGalaxy created the functional Android client and integrated the Parsec
SDK. This fork mainly provides a reproducible, Quest-friendly build.

It is not a new Parsec client, an Android port, or an immersive VR application.
The upstream Android APK may already run on a Quest when sideloaded.

## What this fork changes

- builds only for Quest-compatible ARM64 devices
- uses the separate package ID `com.openparsec.quest`
- configures the app as a resizable landscape 2D panel
- defaults to direct pointer input and 1080p/60 on Quest hardware
- displays basic Quest controller guidance
- adds session-menu actions for Alt+Tab, Ctrl+C, Ctrl+V, and Ctrl+Alt+Delete
- maps recognized Touch buttons to A = Alt+Tab, X = Copy, Y = Paste, and
  hold B = Ctrl+Alt+Delete
- adds an opt-in 3–50 Mbps bandwidth limit using Parsec's host video-config
  messages while preserving the host's other current video settings
- disables updates to the differently packaged upstream phone build
- includes small CMake, native audio-struct, and Android resource fixes needed
  by this build

The login flow, host list, streaming, decoding, audio, and input foundation
come from the upstream Android project.

## Download

The experimental Quest 2 APK is available from this repository's
[GitHub Releases](https://github.com/Talhasarac/OpenParsec-Quest/releases).

This is an unofficial community build and is not affiliated with Parsec,
Unity, Meta, NomadsGalaxy, or hugeBlack.

## How it runs

Horizon OS presents the app as a movable and resizable 2D screen, similar to
other Android media applications. It does not use OpenXR and does not provide
an immersive VR environment.

Point at the panel and pull the controller trigger to click. A paired
Bluetooth keyboard, mouse, or gamepad is recommended for full desktop or game
input. Touch-button shortcuts depend on Horizon exposing the controllers as
Android input devices; the in-session menu remains available when it does not.

## Build from source

Requirements:

- Java 17
- Android SDK API 34
- NDK `26.3.11579264`
- CMake 3.22.1

```bash
./gradlew assembleRelease
```

The APK is written to:

```text
app/build/outputs/apk/release/openparsec-quest2-0.1.0-quest-release.apk
```

With developer mode and ADB available, install it using:

```bash
adb install -r app/build/outputs/apk/release/openparsec-quest2-0.1.0-quest-release.apk
```

## Credits

- [NomadsGalaxy/OpenParsec](https://github.com/nomadsgalaxy/OpenParsec) —
  Android client used as the direct base for this build
- [hugeBlack/OpenParsec](https://github.com/hugeBlack/OpenParsec) — original
  iOS/iPadOS OpenParsec project
- [Parsec](https://parsec.app) — proprietary streaming SDK and service

## License and SDK notice

The OpenParsec source retains its GPL-3.0 license. The bundled proprietary
Parsec SDK binaries remain subject to Parsec's separate SDK terms. Verify
those terms before redistributing an APK or the SDK binaries.
