# Bed Control

A native SwiftUI iPhone app that controls a Sleep Number bed directly over Bluetooth LE,
with no cloud account. Built for a 360 p6 Split E King on a FlexFit 2 base (split heads,
shared foot), and implements both of Sleep Number's BLE protocols:

- **MCR**: the binary protocol of the older BAM module (i8, 360 FlexFit 2). These beds
  advertise their MAC address as their name, e.g. `64:db:a0:xx:xx:xx`.
- **Fuzion**: the "BamKey" text protocol of newer beds (Climate 360, newer FlexFit).

The protocol is picked automatically from the services the bed exposes.

## Features

- Scan list that highlights likely Sleep Number beds; the chosen bed is remembered and
  reconnected automatically.
- Left and right panels: firmness (Sleep Number 5-100 in steps of 5, current value and
  adjustment status), head up/down, presets (Flat, Zero G, Snore, Favorite, Read, TV).
- Shared foot up/down, under-bed light, and a big STOP button.
- Press-and-hold movement uses bounded 10-point targets, so the bed stops on its own within
  one step if the phone or link fails. Releasing always sends a stop.
- Firmness changes run one side at a time; a change for the other side waits in a queue.
- Clear errors for pairing problems ("press the pair button") and the connection limit
  ("close the Sleep Number app").
- Diagnostics: every TX/RX frame in hex and decoded text, copyable and shareable.

## Layout

| Path | Contents |
|---|---|
| `BedKit/Sources` | Pure protocol layer, no CoreBluetooth: MCR/Fuzion framing, checksums, command builders, parsers, request/response sessions over an abstract `GATTLink`, scan classification |
| `BedKit/Tests` | XCTest: reference vectors, safety invariants, session behaviour with a fake link |
| `BedControl/BLE` | CoreBluetooth transport (`BLEManager`) |
| `BedControl/Control` | Protocol drivers, `BedController` (connection policy, polling, holds, firmness queue), diagnostics log |
| `BedControl/Views` | SwiftUI screens |
| `docs/PROTOCOL-NOTES.md` | What is sent and why, plus the list of unverified details |

## Build

Requires Xcode 26+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
xcodegen generate
# Unit tests (simulator)
xcodebuild test -project BedControl.xcodeproj -scheme BedKitTests \
  -destination 'platform=iOS Simulator,name=iPhone 17'
# Device build (automatic signing, team 232A77467G)
xcodebuild build -project BedControl.xcodeproj -scheme BedControl \
  -destination generic/platform=iOS -allowProvisioningUpdates
```

The simulator has no Bluetooth, so the app only does real work on a device.

## Using it

1. Close the Sleep Number app on every phone near the bed (the bed limits connections).
2. Open Bed Control and pick the bed from the list.
3. If the bed asks to pair, press the pair button on the pump or remote and accept the iOS
   prompt.
4. Check sides once with a short head movement. If Left moves the other side, turn on
   Settings → Swap left and right.

## Credits

The protocol knowledge comes from these projects (ha-adjustable-bed and BedRemote are MIT-licensed; sleepnumber-ble ships no license file). This app is an independent
implementation; no code was copied.

- [ha-adjustable-bed](https://github.com/kristofferR/ha-adjustable-bed) by kristofferR
  (with field reports from @homer-aty and @JonGilmore): both protocols, Fuzion auth and
  command catalog, MCR session details.
- [sleepnumber-ble](https://github.com/JonGilmore/sleepnumber-ble) by JonGilmore: the MCR
  protocol write-up from a live i8 / 360 FlexFit 2 bed.
- [BedRemote](https://github.com/canning1295/BedRemote) (SleepController): an iOS MCR app whose traces informed side numbering and the
  motion sequence.

Sleep Number, SleepIQ and FlexFit are trademarks of Sleep Number Corporation. This project
is not affiliated with or endorsed by Sleep Number.
