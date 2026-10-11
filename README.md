# Bed Control

Native iPhone and Android apps that control a Sleep Number bed directly over Bluetooth LE: no
cloud account, no Wi-Fi, no official app. Both apps speak the same protocol as the official
Sleep Number app, reconstructed from its decompiled Android build (v5.4.11), and add things the
official app doesn't have, such as multiple saved favorites per side.

Built and tested on a **Sleep Number 360 p6, Split E King (split head, shared foot) on a FlexFit 2
base**. Other beds may work; see [Compatibility](#compatibility).

> **Use at your own risk.** This is an unofficial project that moves motorized furniture and
> changes air pressure. Keep people, pets and objects clear of the base while it moves. STOP is
> always on screen.

## Features

- **Scan and connect.** Finds nearby Sleep Number beds, highlights the likely one, remembers it
  and reconnects automatically.
- **Per-side controls** (Left / Right):
  - Sleep Number (firmness) 5–100 in steps of 5, with the live value and adjustment status.
  - Head up / down, press and hold.
  - The presets your base supports. On a FlexFit 2: Favorite, Flat, Zero G, Snore and Partner
    Snore (Snore sent to the other side).
- **Shared foot** up / down, **under-bed light**, and a big **STOP** that also cancels anything
  queued.
- **Multiple favorites per side.** Each favorite saves that side's head position, the foot
  position and that side's Sleep Number. "Save current" captures the bed's live values, which you
  can adjust before saving. Rename, edit, reorder and delete them. Apply moves the head, then the
  foot, then sets the firmness, with progress shown; STOP or Cancel stops whatever is moving and
  skips the rest. The bed's built-in Favorite preset is untouched.
- **Safe by construction.** Every movement command moves exactly one motor and marks every other
  one "don't move", and this is checked again before each frame is sent. Firmness changes run one
  side at a time, and a change for the other side waits in a queue. Leaving the app stops any
  movement.
- **Diagnostics.** Every frame sent and received, in hex and decoded text, copyable for bug
  reports.

### Verified on the real bed (360 p6, FlexFit 2)

- Connect, presets, head and foot hold-to-move, Sleep Number, under-bed light, STOP.
- Favorites: save and apply.
- **Dead-man stop:** if the phone drops mid-hold (tested with airplane mode), the bed stops
  immediately, because the base only keeps moving while the app keeps polling it.

The Android app shares the same protocol layer and the same tests, but has seen less use on the
real bed than the iPhone app.

## Compatibility

Sleep Number beds use one of two BLE protocols. The apps detect which one from the services the
bed exposes.

| Protocol | Beds | Status |
|---|---|---|
| **MCR**: binary frames to the BAM pump module. The bed's BLE name is its MAC (`64:db:a0:…`). | 360 beds with FlexFit 1/2/3, i8 and other pre-Fuzion models | Tested on a 360 p6 / FlexFit 2 |
| **Fuzion** ("BamKey"): text commands, requires pairing | Climate 360, newer FlexFit Smart beds | Implemented from the decompiled app; **not tested on hardware** |

Close the official Sleep Number app first: the bed accepts one Bluetooth connection at a time.

## Using it

1. Close the Sleep Number app on every phone near the bed.
2. Open Bed Control and pick your bed from the list.
3. Fuzion beds only: if asked to pair, press the pair button on the pump or remote and accept the
   pairing prompt.
4. Check sides once with a short head movement. If Left moves the other side, turn on
   Settings → Swap left and right.

## Repository layout

| Path | Contents |
|---|---|
| `BedKit/` | Swift protocol layer with no CoreBluetooth: MCR and Fuzion framing, checksums, command builders, parsers, sessions over an abstract link, favorites sequencing. Plus XCTest suites. |
| `BedControl/` | iOS SwiftUI app: CoreBluetooth transport, drivers, controller, screens. |
| `android/bedkit/` | Pure-JVM Kotlin port of BedKit, checked against the same test vectors. |
| `android/app/` | Android Jetpack Compose app: GATT transport with a one-at-a-time operation queue, controller, screens. |
| `docs/PROTOCOL-FROM-APK.md` | The official app's BLE protocol, reconstructed from its decompiled code, with class-level citations. The authoritative reference. |
| `docs/PROTOCOL-NOTES.md` | What these apps send and why, the list of unverified details, and deferred review items. |
| `docs/FAVORITES-SPEC.md` | Favorites data model, file format, apply sequence and stop rules, with test vectors. |

## Building

### iOS

Requires Xcode 26+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
xcodegen generate
xcodebuild test -project BedControl.xcodeproj -scheme BedControl \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

For a device build, set your own team (`DEVELOPMENT_TEAM` in `project.yml`) and bundle ID, then
build the `BedControl` scheme for a connected iPhone. The simulator has no Bluetooth, so the app
only does real work on a device.

### Android

Requires JDK 17 and the Android SDK (platform 35).

```sh
cd android
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app needs the Nearby devices (Bluetooth) permission. On Android 11 and older it asks for
location instead, because that's what Android requires for Bluetooth scanning there.

## How the protocol was worked out

1. Pulled the official app's APK from a phone with `adb` and decompiled it with
   [JADX](https://github.com/skylot/jadx). The app is native Kotlin.
2. Traced its Bluetooth stack: how it chooses MCR or Fuzion, the frame formats, the session
   handshake, and every command these apps use. Each claim in `docs/PROTOCOL-FROM-APK.md` cites
   the decompiled class and method.
3. Cross-checked against the community write-ups below, correcting several details. For
   example, MCR side 0 is the **right** side, and stop is a key write to a separate controller
   node rather than a foundation command.
4. Verified on the real bed using the apps' diagnostics log.

## Credits

Earlier community work that made this much faster:

- [ha-adjustable-bed](https://github.com/kristofferR/ha-adjustable-bed) by kristofferR (MIT),
  with field reports from @homer-aty and @JonGilmore: both protocols, Fuzion auth and command
  catalog, MCR session details.
- [sleepnumber-ble](https://github.com/JonGilmore/sleepnumber-ble) by JonGilmore: the MCR
  protocol write-up from a live i8 / 360 FlexFit 2 bed.
- [BedRemote](https://github.com/canning1295/BedRemote) by canning1295 (MIT): an iOS MCR app whose
  traces informed side numbering and the motion sequence.

These apps are an independent implementation; no code was copied from those projects or from
the official app.

## License

[MIT](LICENSE).

Sleep Number, SleepIQ and FlexFit are trademarks of Sleep Number Corporation. This project is
not affiliated with, endorsed by or supported by Sleep Number. Using it may affect your
warranty or support.
