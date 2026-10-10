# Sleep Number BLE Protocol, From the Official Android App

How the official SleepIQ app talks to the bed over Bluetooth LE, reconstructed from its decompiled code. Every claim cites the decompiled class and method. Use it to build an iPhone controller for a Sleep Number 360 p6, Split E King (split head, shared foot), on a FlexFit 2 base.

- **Source:** `/Volumes/Cache/repos/SleepNumberApp/apk/base.apk`, package `com.selectcomfort.SleepIQ` 5.4.11 (build `5.4.11-19013_originalPlaystore`), taken from the owner's phone.
  - SHA-256 `8b0ab92839ddf064caea701b571282efc3f4b5bbd3d25f6a2582a68da7ae6474`.
  - The HA community doc cites a different 5.4.11 hash (`710b7dfd...`). The likely reason is that the Play Store ships a base APK plus split APKs. The code below matches the parts that doc describes.
- **Tools:** jadx 1.5.6 decompiled the whole APK. For methods jadx could not structure, I re-ran it per class in `-m fallback` mode. jadx renames obfuscated classes: `p281ne.r` means obfuscated class `ne.r`, and `C2993q` means obfuscated `q`. Both forms are given where it helps.
- **REA:** the `rea-agents` CLI (6.3.0) was tried. Every Android command (`inspect-android-package/class/method`) stopped with `capability_unavailable: Set REA_JADX_MCP_JAR ... jadx-headless-mcp 0.7.1 JAR. REA does not download or install it.`, so plain jadx was used throughout.
- **Confidence:** this is static analysis only. Nothing here was checked against a live bed.

---

## 0. TL;DR for the iOS app

1. **Choose the protocol from the bed's generation.** The app uses Fuzion only when the bed generation is `FUZION`. `THREE_SIXTY` ("360") and `LEGACY` both use MCR. A 360 p6 is generation `360`, so expect **MCR**.
   - To confirm, check which service the bed advertises: `ffffd1fd-…-ee0` means MCR, `09d23fae-…` means Fuzion.
2. **MCR basics:**
   - Write frames to `ffffd1fd-388d-938b-344a-939d1f6efee2` and listen for notifications on `…ee1`.
   - Frame layout: `16 16 | 10-byte header | 0–15 byte payload | 2-byte running-sum checksum`.
   - Bind first with cmd `0x00` and an 8-byte client ID. Then put the client address from the bind reply in header bytes 1–2 and 6–7, and the bed address in bytes 3–4.
   - Send one request at a time. Timeout is 900 ms, with up to 3 retries.
3. **MCR sides:** right = 0, left = 1, carried in the high nibble of header byte 9.
4. **MCR motion on a FlexFit 2:**
   - **Hold to move:** cmd `0x11` to the foundation (node `0x42`) with a 12-byte payload, target 100 for up or 0 for down.
   - **While moving:** poll foundation status (cmd `0x12`) with sub-nibble `side+2` about every 333 ms.
   - **Stop:** a SleepExpert key write `MFHL`/`MFHR` = `"110"` (node `0x52`, cmd `0x1B`).
   - **Presets:** cmd `0x15`, payload `[id, 0]`. Ids: Favorite 1, Read 2, Watch TV 3, Flat 4, Zero-G 5, Snore 6. A FlexFit 2 offers only Favorite, Flat, Zero-G, Snore and Partner-Snore.
   - **Firmness:** cmd `0x11` to the pump (node `0x02`), payload `[0, SN]`, SN 5–100. Then poll pump status every second until it reports idle.
5. **The app does not special-case a shared foot on MCR.** On a SPLIT_HEAD base with foot control, the app's actuator list includes LEFT_FOOT and RIGHT_FOOT (`device/model/a.a()`). It sends foot commands with each sleeper's own side and lets the firmware drive the single foot actuator. Foundation status reports both foot bytes; on a shared foot they track together.

---

## 1. Protocol selection, UUIDs, MTU, write type

### 1.1 How MCR vs Fuzion is chosen

There are three ways to reach the bed: BLE MCR, BLE Fuzion, and the cloud (`makeLegacyCdcCall`, REST, not covered here).

**Where `Bed.Generation` comes from**
- The enum is `com.selectcomfort.sleepiq.core.model.Bed.Generation`, with values `FUZION("fuzion")`, `THREE_SIXTY("360")`, `LEGACY("legacy")`.
- Logged in: it comes from the cloud bed record `network/api/bed/Bed.generation`, parsed by `core/userdata/database/dao/g.j()` from the strings `"FUZION"`, `"THREE_SIXTY"`, `"LEGACY"`.
- Not logged in: it comes from the scan result. `NonLoggedScanningViewModel.b0()` maps `ScannedBed.Model.FUZION` to `FUZION`, `THREE_SIXTY` to `THREE_SIXTY`, and anything else to `LEGACY`.

**How the controller is built** (`core/bedcontrol/controller/o.java`, ~l.515–552)
- If `generation == Bed.Generation.FUZION`:
  - Connection is `bedcontrolframework.connection.ble.d`, the FuzionBLE stack.
  - Call maker is `device_temp.call_maker.d` (FuzionCallMaker), which wraps a `BamkeyClient`.
- Otherwise (360 and legacy):
  - Connection is `connection.ble.l`, the MCR stack.
  - Call maker is `device_temp.call_maker.e` (`LegacyCallMaker`). It calls `Call.makeLegacyBleCall` → `runScComCall`, behind a mutex, so only one MCR call is in flight at a time.

**What each MCR call does with `runScComCall`.** A `Call` is one of two kinds:
- `device.a` (McrCall): builds one binary MCR frame (`mcrRequest()`) and parses the reply (`fromMcrReply()`).
- `device.b` / `device.c` (`BulkDataCall` / `BulkDataWriteCall`, "SE" calls): sends a 4-letter ASCII key and value to the SleepExpert node *over MCR* (§2.6). This is effectively a third command layer. Head/foot set-position, stop, preset reset and auto-UBL all go through it on MCR beds.

**BLE or cloud** (`device_temp/call_handler/b`, `CallPolicy`). Each call has a `CallPolicy` of `DEFAULT`, `SMART_FEATURE`, `CDC_ONLY_CALL` or `BLE_CALL_ONLY`. `MicroAdjustFoundationCall` (`device/call/I`) and the "keep adjusting" status call (`device/call/C2988l`) are `BLE_CALL_ONLY`. The latter's `makeLegacyCdcCall` throws "Foundation adjustment can be continued only via BLE".

**How scanning identifies the bed**
- **MCR scanner:** `pe.a(re.b.f51131a)` filters on service `ffffd1fd-…-ee0` (`controller/o.java:451`). `blelib/device/SmartPump` then classifies the model from manufacturer data (AD type 0xFF; flag helpers in `je.b`):
  - `THREE_SIXTY` if the record is 7 bytes long and `data[3]==6`, or if the bed is a legacy-adult board whose MAC starts with `64`.
  - `GENIE` if `data[3]==5`.
  - K1/K2 kids beds from flag bits.
- **Fuzion scanner:** `com.fuzionble.implementation.scanner.c` filters on service `09d23fae-…`. Manufacturer data uses company ID `0xFFFF` (`scanner/a.d()`):
  - `byte0`: `0` Close, `1` Open (pairable), `0xFF` Full.
  - `byte1`: SKU — `0` A, `5` B, `10` C (`binding/scanning/a`).

### 1.2 UUIDs

**MCR service:** `ffffd1fd-388d-938b-344a-939d1f6efee0` (`blelib/mcr/manager/n.f24703g`, `qe.b`).

| Characteristic | UUID | Use | Evidence |
|---|---|---|---|
| UART RX | `ffffd1fd-388d-938b-344a-939d1f6efee2` | App → bed writes | `mcr/manager/n.h`, `qe.b.w0()` |
| UART TX | `ffffd1fd-388d-938b-344a-939d1f6efee1` | Bed → app notifications | `n.f24704i`, `qe.b.k0()` enables notify |
| Unused | `ffffd1fd-…-ee3`, `0000d829-…`, `734e5582-bb4e-ee4a-744c-6579444dd829/82a/82b/82c` | Declared only | static init of `re.b` |

**Fuzion service:** `09d23fae-90e6-44c2-95b6-0b3d0f1abf25` (`com.fuzionble.implementation.J.f18841a`).

| Characteristic (enum `fuzionble.implementation.I`) | UUID | Required properties | Use |
|---|---|---|---|
| `Auth` (`I$a`) | `8d4675a5-b5fa-42b2-b587-0ee71c46b709` | READ | Read once after bonding; returns the 16-byte session UUID |
| `TransferInfo` (`I$c`) | `e8d06e2a-c987-48f8-93a8-4d18d56b4337` | READ | Bulk file transfer only |
| `BulkTransfer` (`I$b`) | `0ec9a5a3-8ac3-4582-92f3-1666421f323d` | READ, WRITE_NR, NOTIFY | Bulk file transfer only |
| BamKey ("public" characteristic) | `421e00f3-ae76-4c49-ab6e-39e4df4a5333` | NOTIFY, READ, WRITE_NR | All text commands (`C2592h.f18945q`, `FuzionBlobBleCommunication.Characteristic.BamKey`) |

- The enum UUIDs only show up in `-m fallback` output of the `I$a`/`I$b`/`I$c` constructors; normal jadx output drops them.
- In `C2592h.b.a.w0()`, service support requires all three `I` characteristics to be present with their properties. Anything else, including BamKey, goes into the "public" map.

### 1.3 MTU, chunking, write type

**MCR** (`qe.b`, `mcr/manager/l` case 0)
- The app never requests a larger MTU.
- The write type is WITH response (`setWriteType(2)`) when RX has the WRITE property; otherwise the characteristic's default is used.
- Each frame is written through Nordic's default splitter (`u0Var.f49054o = u0.f49053v`), so it goes out in MTU−3 (20-byte) pieces. A full frame is at most 2+10+15+2 = 29 bytes.
- Notifications are joined by a Nordic data merger (`McrDataMerger`, `mcr/manager/k`; its body was stripped by R8) and parsed by `ne.r.a.a()`.
- After service discovery the app sleeps `SERVICE_DISCOVERY_DELAY` = 128 ms before enabling notifications (`qe.b.C1239b.k0`, `he.b`).
- Config defaults in `he.b`:
  - `CONNECTION_TIMEOUT` 30000 ms
  - `CONNECTION_IDLE_TIMEOUT` 10000 ms (auto-disconnect is off by default)
  - `MCR_COMMAND_TIMEOUT` 900 ms (×2 for SE read-chunk calls `ne.z`/`ne.x`)

**Fuzion** (`C2592h`)
- Requests MTU 517 on connect and in `onMTUChanged`. The effective MTU is `i() = min(negotiated, 517)`.
- Blob writes go to the BamKey characteristic as WRITE_NO_RESPONSE (`C2592h.t()`, `setWriteType(1)`).
- Chunk size is `MTU − 4`, or `MTU − 5` on Android 14+ (`datatransfer/blob/b.C0366b.f18857a`, `blob/e`).
- `u()` (write with response) exists but blob writes don't use it.

---

## 2. MCR (360 / legacy pump + FlexFit foundation)

### 2.1 Frame layout (`ne.r` = "McrMessage")

```
16 16 | H0 H1 H2 H3 H4 H5 H6 H7 H8 H9 | payload[0..len-1] | CK_hi CK_lo
```

| Byte | Setter / getter in `ne.r` | Meaning |
|---|---|---|
| H0 | `n(int)` | Node/message class (see 2.3). Same value as H5 for requests |
| H1–H2 | set in `mcr/manager/i` case 0 | **Source** address, i.e. this client's assigned address (big-endian) |
| H3–H4 | `c()`; set in `mcr/manager/i` | **Destination** address, i.e. the bed's (big-endian) |
| H5 | `h(int)` | Node/message class, repeated |
| H6–H7 | `d()`; set in `mcr/manager/i` | Source address again (BE). Replies are matched on it |
| H8 | `g(int)` / `b()` | Command. The reply sets bit 7 (`cmd \| 0x80`) |
| H9 | `o(int)` high nibble / `f()` | "Sub-command" (the app's analytics name `sub_command_id`): side, outlet or sequence number |
| H9 | `m(int)` low nibble / `e()` | Payload length, 0–15 |

- **Payload:** `new ne.r()` allocates a 15-byte buffer. Setters `j`/`k`/`i` refuse to write past `len`. `a()` fills the payload with `0xFF`.
- **Checksum:** computed over header + payload, sync bytes excluded (`mcr/manager/l` case 0; verified in `ne.r.a.a`):
  ```
  s = 0; r = 0
  for b in header+payload: s += b; r += s
  CK = r & 0xFFFF  (big-endian, appended)
  ```
- **Parsing a reply** (`ne.r.a.a`): if the frame starts with `16 16`, the app strips the sync bytes and checksum and verifies the checksum. The first 10 bytes are the header. The payload is the next `H9 & 0x0F` bytes.
- **Little-endian multi-byte values** use `me.a.a(value, true, minLen)`. It writes the value's hex digits as little-endian bytes, using the minimum number of bytes unless `minLen` is given. If the result doesn't fit, `k()` silently writes nothing.

### 2.2 Handshake / addressing ("bind")

**Bind request** (`blelib/connection/e.d()`, class `ne.c`):
```
H0=0x02 H1..H4=00 H5=0x02 H6..H7=00 H8=0x00 H9=0x08
payload = 8-byte client ID, big-endian
```
- The client ID is `he.b.e()`: a random `long` generated once and stored in SharedPreferences `com.selectcomfort.blelib` / `DEVICE_ID`. It is **not** derived from the bed's MAC.

**Bind reply** (`blelib/connection/b.a()`):
- `bleConnection.f24656b` (our source address) = `reply.payload[8..9]` (big-endian).
- `bleConnection.f24657c` (bed address) = `reply.header[1..2]`.
- `f24655a = true` (bound).

**Every later request** (`blelib/mcr/manager/i` case 0, inside `j.a()`) is stamped with H1–H2 = H6–H7 = our address and H3–H4 = the bed's address. Requests fail with `ERROR_UNEXPECTED_CLOSE` if the connection isn't bound.

**Reply correlation** (`mcr/manager/a`). A reply matches a pending request only if all of these hold:
- `reply.H8 & 0x80` is set.
- `reply.H3H4 == request.H6H7`. If that value is 0, the reply is accepted immediately, which is how the bind reply matches.
- `reply.H6H7 == request.H3H4`.
- `reply.H8 == request.H8 | 0x80`.
- `reply.H0 & 0xF0 == request.H0 & 0xF0`.

**Errors** (`blelib/mcr/error/McrError`, `Cg.l` default case for SE writes):
- A reply sub-nibble of `0xE` means `ERROR_BAD_PARAMETER`; `0xF` means `ERROR_NACK`.
- Timeouts retry up to 3 times (`device/a` default retry strategy `fe.a({TIMEOUT:3})`).

### 2.3 Node classes (header byte 0 / 5)

Node class IDs come from `bedcontrolframework/device/model/Node`. Requests use the node class with its low nibble set to 2; replies come back with the node's own class ID in H0 (for example 0x01 or 0x41).

| Node (`Node.classId`) | Request H0/H5 | What it is |
|---|---|---|
| PUMP (0x01) | `0x02` | Air pump / firmness |
| SENSE_AND_DO (0x31) | `0x32` | Sense & Do outlet toggle |
| FOUNDATION (0x41) | `0x42` | FlexFit base: motors, lights, footwarming, massage |
| SLEEP_EXPERT (0x51) | `0x52` | SE key/value store: `MF**` foundation keys, `MUA*`, Wi-Fi config |
| TEMPERATURE_ENGINE (0x61/0x63/0x64) | — | (DualTemp) |
| PROXY (0x71) | `0x72` | Node list, foundation bind |
| BRIDGE (0x81) | — | |
| SMART_OUTLET (0x91) | `0x92` | Kids light/outlet |

`GetNodeListCall` (`device/call/C2995t`): H0 `0x72`, cmd `0x12`, no payload. The reply payload is a list of node class IDs mapped with `Node.a()`. The community sample shows SLEEP_EXPERT (0x51) present on a 360 FF2, which is the node the stop and set-position keys go to.

### 2.4 Command table (MCR binary)

S is the side in the H9 high nibble: **RIGHT = 0, LEFT = 1** (`Bed.Side.toDeviceValue()`; `device/call/C2964b.b()`). All requests below are built in each call's `mcrRequest()`; replies are parsed in `fromMcrReply()`.

**Pump (H0/H5 = 0x02)**

| Call class (source file) | cmd | H9 hi | len | Request payload | Reply |
|---|---|---|---|---|---|
| `SetSleepNumberCall` (`m0`) | `0x11` | S | 2 | `[0x00, SN]` | ack |
| `GetPumpStatusCall` (`C2998w`), `GetSleepNumberControlStatusCall` (`E`) — class `ne.w` | `0x12` | 0 | 0 | — | `[0]` ≠0 means busy (`PumpTaskState.ACTIVE`); `[1]` = **right** SN; `[2]` = **left** SN; reply H9 hi nibble = chamber type |
| `GetSleepNumberFavoriteCall` (`F`) | `0x14` | 0 | 0 | — | `[0]` = right favorite, `[1]` = left favorite |
| `SetSleepNumberAsFavoriteCall` (`l0`) | `0x13` | S | 1 | `[SN]` | ack |
| `ForceIdleCall` (`C2967e`) — interrupt an inflation in progress | `0x02` | 0 | 0 | — | ack |
| `GetChamberTypesCall` (`C2985i`) | `0x61` | 2 | 2 | `[0, 0]` | `[0]` R presence, `[1]` R type, `[2]` L presence, `[3]` L type, then if ≥8 bytes: `[4]` R occupancy, `[5]` R refresh, `[6]` L occupancy, `[7]` L refresh. Type: 0 STANDARD, 1 KID, 2 HEADTILT, 3 GENIE |

**Foundation (H0/H5 = 0x42)**

| Call class | cmd | H9 hi | len | Request payload | Reply |
|---|---|---|---|---|---|
| `GetFoundationStatusCall` (`C2991o`) — class `ne.i(0)` | `0x12` | 0 | 0 | — | 15-byte status, see 2.5 |
| "Keep adjusting" status (`C2988l`) — class `ne.i(side+2)` | `0x12` | **2 = right, 3 = left** | 0 | — | same 15-byte status. Polled while a micro-adjust runs (§4) |
| `GetFoundationSystemStatusCall` (`C2993q`) | `0x25` | 0 | 0 | — | Configuration and features, see 2.5 |
| `MicroAdjustFoundationCall` (`I`) | `0x11` | S | 12 | All `FF`, then `[0]=head` (or FF), `[1]=0` if head is set else `FF`, `[2]=foot` (or FF), `[3]=0` if foot is set else `FF` | ack |
| `SetFoundationPresetCall` (`X`) | `0x15` | S | 2 | `[presetId, 0]` | ack |
| `ModifyPresetCall` (`K`) / `SaveCurrentFoundationPositionAsFavoriteCall` (`N`) — class `ne.m` | `0x16` | S | 1 | `[presetId]`: saves the current position into that preset (Favorite = 1) | ack |
| `SetPresetTimerCall` (`g0`) | `0x11` | S | 12 | All `FF`, `[7..8]` = timer LE, `[9]` = presetId | ack |
| `SetHeadMassageCall` (`b0`) — class `ne.p` | `0x11` | S | 12 | All `FF`, `[4]` = head intensity, `[6]` = 0, `[10..11]` = timer LE (if given) | ack |
| `SetFootMassageCall` (`S`) | `0x11` | S | 12 | `[5]` = foot intensity, `[6]` = 0, timer as above | ack |
| `SetMassageWaveModeCall` (`f0`) | `0x11` | S | 12 | `[6]` = wave mode, timer as above | ack |
| `SetMassageTimerCall` (`e0`) | `0x11` | S | 12 | `[10..11]` = timer LE only | ack |
| `GetMassageStatusCall` (`C2994s`) | `0x1A` | S | 0 | — | `[1]` head intensity, `[2]` foot intensity, `[3]` mode, `[5..6]` timer LE, `[7..8]` and `[9..10]` LE: cool-down and run time (`Td.a`) |
| `SetFoundationOutletCall` (`U`), `SetUnderBedLightSettingCall` (`p0`) — class `ne.j` | `0x13` | outlet # | 3 | `[mode (1 on / 0 off), timer LE (2 bytes)]`. Under-bed light is outlet **3** | ack |
| `GetFoundationOutletCall` (`C2987k`) | `0x14` | outlet # | 0 | — | `[0]` on/off, `[1..2]` = timer LE |
| `SetUnderBedLightIntensityCall` (`o0`), `SetFoundationOutletIntensity` (`V`) — class `ne.n` | `0x24` | 0 | 3 | `[0xFF, right, left]` (`0xFF` = don't change). UBL `Intensity`: OFF 1, LOW 1, MEDIUM 30, HIGH 100. Outlet/nightlight `Intensity`: LOW 45, MEDIUM 75, HIGH 100 | ack |
| `SetFootWarmingStatusCall` (`T`) | `0x29` | S | 3 | `[temperature, timer LE]`. Temperature: OFF 0, LOW 31, MEDIUM 57, HIGH 72 (`FootWarmingStatus.Intensity`) | ack |
| `GetFootWarmingStatusCall` (`C2986j`) | `0x2A` | S | 0 | — | `[0]` temperature, `[1..2]` = timer LE |
| `GetPinchStateCall` (`C2996u`) | `0x28` | 0 | 0 | — | 5 bytes, see 2.5 |
| `DoFoundationShortBindCall` (`C2966d`) — H0 `0x72` | `0x11` | 2 | 1 | `[0x41]`. **Installer pairing: do not send** | ack |

**Other nodes**

| Node | Call | cmd / payload |
|---|---|---|
| Sense & Do (`0x32`) | Get (`C3000y`), Set (`j0`) | Get: cmd `0x12`. Set: cmd `0x14`, payload `[outlet ordinal, on?0:1]` |
| Smart outlet / kids (`0x92`) | `SetKidsLightStatusCall` (`c0`), `SetKidsOutletStatusCall` (`d0`) — class `ne.G` | cmd `0x13`, H9 hi = outlet, payload `[outletBool or FF, lightBool or FF, 0]` |
| Smart outlet / kids (`0x92`) | `GetSmartOutletStatusCall` (`G`) | cmd `0x12` |

**Shared struct for foundation cmd `0x11` (12-byte payload).** Every axis defaults to `FF` = "don't change" (`r.a()`), which is the app's main protection against unintended moves.

| Offset | Field |
|---|---|
| 0 | Head target (0–100) |
| 1 | Head flag (`00` = apply) |
| 2 | Foot target |
| 3 | Foot flag |
| 4 | Head massage intensity |
| 5 | Foot massage intensity |
| 6 | Massage wave mode |
| 7–8 | Preset timer, little-endian |
| 9 | Preset ID used with the timer |
| 10–11 | Massage timer, little-endian |

**Preset IDs** (`device/call/C2964b.a()`; parsed back by `C2991o.f()`):

| Preset | ID |
|---|---|
| FAVORITE | 1 |
| READ | 2 |
| WATCH_TV | 3 |
| FLAT | 4 |
| ZERO_G | 5 |
| SNORE | 6 |
| PARTNER_SNORE | 6 (sent to the other side, see §4) |

**Massage enums** (`features/massage`):
- Intensity: OFF 0, LOW 1, MEDIUM 2, HIGH 3.
- Mode: OFF 0, SMOOTH 1, REVITALIZE 2, WAVE 3.

A FlexFit 2 has no massage (see 2.5).

### 2.5 Status layouts

**Foundation status, cmd `0x12`, 15 bytes** (`C2991o.fromMcrReply` → `Nd.d`)

| Byte | Field |
|---|---|
| 0 | Flags: bit0 `isMoving`, bit5 (`0x20`) `needsHoming`, bit6 (`0x40`) `isConfigured` |
| 1 | Right head position (0–100) |
| 2 | Left head position |
| 3 | Right foot position |
| 4 | Left foot position |
| 5–6 | Right preset-timer remaining, LE (`b6*256+b5`) |
| 7–8 | Left preset-timer remaining, LE |
| 9 | Right-head actuator status |
| 10 | Left-head actuator status |
| 11 | Right-foot actuator status |
| 12 | Left-foot actuator status |
| 13 | Timer preset: low nibble right, high nibble left (preset IDs) |
| 14 | Current preset: low nibble right, high nibble left |

**Actuator status byte** (`C2991o.a(int,int)`, bits tested by `d(v, n)`):

| Bit | Meaning |
|---|---|
| 0 | Moving |
| 1 | Limit switch MISSING |
| 2 | Limit switch UNEXPECTED |
| 3 | Current UNDER |
| 4 | Current OVER |
| 5 | Movement NONE |
| 6 | Movement UNEXPECTED |

**Foundation system status, cmd `0x25`** (`C2993q.fromMcrReply` → `device/model/a`)

| Byte | Field |
|---|---|
| 0 | Configuration: `0` SINGLE, `1` SPLIT_HEAD, `2` SPLIT_KING, `3` EASTERN_KING |
| 1 | Right UBL intensity |
| 2 | Left UBL intensity |
| 3 | Flags: bit0 board DUAL; bit1 `hasMassageAndLighting`; bit2 `hasFootControl`; bit3 (`0x08`) **`hasFootWarming`**; bit4 (`0x10`) **`hasUnderBedLight`** |
| 4 | Low nibble = board HW revision |
| 6 | bit2 left board under-performing; bit3 right board under-performing |

- **FlexFit classification** (`device/model/a.b()`):
  - foot + UBL + footwarming → 360 FF3
  - foot + UBL → **360 FF2**
  - foot + massage → legacy FF3
  - foot only → legacy FF2
  - no foot → FF1
- **Presets offered per model:**
  - Always: Favorite, Flat.
  - FF2/FF3: also Zero-G and Snore, plus Partner-Snore if SPLIT_HEAD or SPLIT_KING.
  - FF3 only: Watch TV and Read.
  - FF1 on a 360: Snore, plus Partner-Snore on split bases.
- **Actuators** (`device/model/a.a()`):
  - Always RIGHT_HEAD.
  - RIGHT_FOOT if `hasFootControl`.
  - On SPLIT_HEAD or SPLIT_KING, also LEFT_HEAD, and LEFT_FOOT if `hasFootControl`.

**Pinch state, cmd `0x28`, 5 bytes** (`C2996u`)

| Field | Right | Left |
|---|---|---|
| headDisconnected | `b0` bit2 | `b0` bit3 |
| continuousPinchHead | `b0` bit6 | `b0` bit7 |
| pinchEventHead | `b[3]` | `b[4]` |
| footDisconnected | `b0` bit0 | `b0` bit1 |
| continuousPinchFoot | `b0` bit4 | `b0` bit5 |
| pinchEventFoot | `b[1]` | `b[2]` |

### 2.6 SleepExpert "bulk data" keys over MCR (node `0x52`)

`device/c.runSeCall$suspendImpl` (write) and `oe.b` (read) carry 4-letter ASCII keys. The key is packed big-endian into a 4-byte int by `oe.b.a.b()` and unpacked by `oe.b.a.a()`, so it appears on the wire as plain ASCII.

**Short write** (value ≤ 15 bytes), class `ne.C`:
```
H0=H5=0x52, cmd 0x1B, H9 hi 0, len = 4 + n
payload = key(4 ASCII) + value
```
- The reply must have len 0. Sub-nibble `0xE` means bad parameter, `0xF` means NACK (`Cg.l` default branch).

**Long write** (value > 15 bytes): header frame cmd `0x1D`, H9 hi 1, len 12, payload = `key, CRC32(value) BE, length BE` (class `ne.A`). The value then follows in 15-byte chunks, cmd `0x1D`, with a sequence number in H9 hi (class `ne.z`, `oe.d`).

**Read:** cmd `0x1D`, H9 hi 2, payload = key (class `ne.y`). The value is pulled in chunks (`ne.x`, `oe.c`) and checked with CRC32 (`oe.a`).

**Keys the app uses:**

| Key | Call | Value |
|---|---|---|
| `MFHL` / `MFHR` | `StopFoundationMovementCall` (`q0`) — **the MCR stop** | ASCII `"110"` |
| `MFUL` / `MFUR` | `SetFoundationPositionCall` (`W`) — head to an absolute position | ASCII `"<pos>_0"`, e.g. `"35_0"` (`C4.a.e(pos,"_0")`) |
| `MFFL` / `MFFR` | `W` — foot to an absolute position | `"<pos>_0"` |
| `MFRL` / `MFRR` | `ResetFoundationPresetCall` (`L`) | ASCII preset ID |
| `MUAS` | `SetUnderBedLightAutoCall` (`n0`) | 1 byte |
| `MUAG` | `GetAutoUnderBedLightStateCall` (`C2983g`) | 4-byte int; 1 = on |
| `SWSC`, `SWST`, `SWCF`, `SREL`, `SRFS`, `SFWU`, `SIQR/L`, `SSNR/L`, … | Wi-Fi, firmware and misc (listed in `oe.b` static init) | — |

---

## 3. Fuzion (FuzionBLE + BamKey text commands)

### 3.1 Connect, bond, session

Sequence in `fuzionble.implementation.C2592h` (BleManager) and `n` (the auth reader):

1. Connect and discover services. `isServiceSupported` (`C2592h.b.a.w0`) requires the three `I` characteristics.
2. Request MTU 517.
3. **Bond.** If the device isn't bonded yet (`C2592h.j()`), the app sends `CREATE_BOND` (Nordic `createBondInsecure`) and waits up to 5000 ms for the bond (`n`, `O0.b(DEFAULT_TIMEOUT, z)`).
4. **Read Auth** (`8d4675a5…`), with a 10 s timeout per read and up to 3 attempts (`G.c(3, m, n)`). The read must return exactly 16 bytes, which `C2592h.k()` turns into a UUID (two big-endian longs):
   - not 16 bytes / unreadable → treated as `00…01` → "Authentication failed", disconnect.
   - `00000000-0000-0000-0000-000000000000` → "Connection limit reached", disconnect.
   - anything else → session `authId` (`C2592h.h`).
5. **Subscribe to BamKey notifications** (`C2592h.p()`). Each notification is a 16-byte UUID. If it equals our `authId` (or is all-zero), the app reads the BamKey characteristic (repeatedly, until a complete frame has arrived) to get the response.
   - BulkTransfer notifications are only subscribed during file transfers.
6. A bonded device whose Auth fails is treated as "Pairing info is corrupted" and disconnected (`performConnect` logs).

The session UUID is **not** sent back in commands. It is only used to filter notifications and to report the connection state.

### 3.2 Framing ("fUzIoN" blob)

Built by `datatransfer/blob/a.l()`, parsed and validated by `blob/g.e()` and `A2.a.write()`:
```
"fUzIoN" (66 55 7A 49 6F 4E) | u32 LE totalLength | payload | u32 LE CRC32
```
- `totalLength` covers the whole frame, CRC included.
- The CRC is standard `java.util.zip.CRC32` over the first `totalLength-4` bytes (`utils/extensions/e.a`).
- **Request payload:** UTF-8 `"<KEY> <args>"` (`FuzionBlobBleCommunication.write`, `Y.b.b` = concatenation). A command with no arguments is `"KEY "` with a trailing space. Arguments are joined by single spaces in constructor order (`Constants.RETURN_DELIMITER=" "`).
- **Response payload:** UTF-8 text.
- Writes are chunked at MTU−4 or MTU−5 with WRITE_NO_RESPONSE (§1.3).
- One command at a time behind a mutex: 7000 ms send timeout, 7128 ms response timeout (`FuzionBlobBleCommunication.SEND_COMMAND_TIMEOUT` / `BAM_KEY_TIMEOUT`).

### 3.3 Responses

Status handling (`BamkeyResponseParserImpl`, `BamkeyClient.parseOutputString`):

| Response | Meaning |
|---|---|
| `PASS:<values>` | Success. Values are space-separated, or the whole string when exactly one value is expected. `PASS:ACK` means success with no values. |
| `FAIL:0` | Unknown BamKey |
| `FAIL:1` | Timeout |
| `FAIL:2` | Generic error. The UI shows "troubleshooting needed". |

**Grouping:** `BAMG` sends up to 15 commands as a JSON array, `[{"bamkey":"ACTG","args":"left head"},…]`, and gets back a JSON array of strings.

### 3.4 Enums (wire strings)

| Enum | Values |
|---|---|
| Side | `left`, `right` |
| ActLocation | `head`, `foot` |
| ArticulationPreset | `none`, `in_progress`, `flat`, `favorite`, `snore`, `zero_g`, `watch_tv`, `read` |
| ResetType | `flat`, `favorite`, `snore`, `zero_g`, `watch_tv`, `read`, `all` |
| Homing | `done`, `in_progress`, `required`, `error` |
| FootwarmingSetting | `off`, `low`, `medium`, `high` |
| UblLevel | `off`, `low`, `medium`, `high` |
| UblAutoEnable | `true`, `false` |
| EnableFlag | `yes`, `no` |
| ChamberType | `single`, `dual` |
| BaseType | `unknown`, `no_base`, `integrated_base`, `ff1`, `ff2`, `ff3`, `c360` |
| MattressSize | `twin`, `twinxl`, `full`, `double`, `queen`, `king`, `king-split`, `king-flex` |
| ActuatorMovementStatus | 0, 1 |
| SleepNumberAdjustmentStatus | 0, 1 |

All are in `fuzion/bamkey/generated/*`.

### 3.5 Bed-control command table

All classes are in `fuzion/bamkey/generated`. The full list of about 170 keys (Wi-Fi, thermal, firmware, diagnostics) is in those classes and was not needed here.

**Articulation**

| Key | Args | Response | App call |
|---|---|---|---|
| `ACTS` | side, location, pos 0–100 | ACK | `SetFoundationPositionCall` (`W`), `AdjustFuzionFoundationCall` (`C2963a`): set-and-go |
| `ASTM` | side, location, pos | ACK | `MicroAdjustFoundationCall` (`I`): hold-to-move (target 100/0) |
| `ACTG` | side, location | pos | `GetFuzionActuatorPositionCall` |
| `ACTM` | — | rightHead rightFoot leftHead leftFoot (each 0/1) | `GetFuzionActuatorMovementStatus` |
| `ACHA` | — | ACK | `StopFuzionActuatorsCall` (`r0`): halts **all** actuators, both sides |
| `ACTH` | side, location | ACK | Defined but **never used** by the app |
| `ASTP` | side, preset | ACK | `SetFuzionFlexFitPresetCall` (`Y`) |
| `ACSP` | side, preset, timer (min) | ACK | `SetFuzionFlexFitTargetPresetCall` (`Z`) |
| `ACCP` | side | ACK | `CancelFuzionFlexFitTargetPresetCall` (`C2965c`) |
| `AGCP` | side | preset | `GetCurrentPresetCall` |
| `ACGP` | side | preset, remaining | `GetTargetPresetCall` |
| `ACPS` | side, preset, head, foot | ACK | `ModifyFuzionPresetCall` (`J`), save as favorite |
| `ACPG` | side, preset | head foot | (unused) |
| `ACFR` | side, ResetType | ACK | `ResetFuzionPresetsCall` (`M`) |
| `ACHS` / `ACHG` | — | ACK / Homing | Homing (`ResetFuzionFoundationCall`, `C2968f`) |

**Firmness and air**

| Key | Args | Response | App call |
|---|---|---|---|
| `PSNS` | side, SN | ACK | `SetSleepNumberCall` (`m0`) |
| `PSNI` | — | ACK | `ForceIdleCall` (`C2967e`) |
| `SNCG` | side | adjusting(0/1), ambientSN, userSN | `GetSleepNumberControlStatusCall` (`E`) |
| `SNFS` / `SNFG` | side[, SN] | ACK / SN | Favorite firmness (`l0`, `GetFuzionSleepNumberFavoriteCall`) |
| `LRAS` / `LRAG` | side[, 0/1/2] | ACK / setting | Responsive Air |

**Lights and footwarming**

| Key | Args | Response | App call |
|---|---|---|---|
| `UBLS` | level, timer (min; app default 120) | ACK | `SetFuzionUnderBedLightIntensityCall` (`a0`) |
| `UBLG` | — | level timer | |
| `UBAS` / `UBAG` | enable, level (`off` is sent as `low`) | ACK / enable level | Auto UBL |
| `FWTS` | side, setting, timer (min, 30–600 in the UI) | ACK | `SetFootWarmingStatusCall` (`T`) |
| `FWTG` | side | setting remaining total | |

**Configuration**

| Key | Args | Response | App call |
|---|---|---|---|
| `SYCG` | — | 16 fields: chamberType, pressureControl, articulation, underbedLight, rapidSleep, thermal, rightHeadActuator, rightFootActuator, leftHeadActuator, leftFootActuator, flat/favorite/snore/zeroG/watchTv/read preset flags | Capability discovery |
| `SYAG` | — | MattressSize BaseType ChamberType | |

**Massage:** there is no BamKey. The massage calls don't override `makeFuzionCall`, so they throw `Call.a` ("not yet implemented on fuzion").

### 3.6 What "side" and actuator IDs mean

- `side` is the sleeper side, as a string. `location` is `head` or `foot`.
- No numeric actuator IDs exist at this layer (`Nd.a.a` and `Nd.a.b` map `Bed.Part` and `Bed.Side` straight across).
- `ACTM` returns a fixed order: rightHead, rightFoot, leftHead, leftFoot.
- **Which actuators exist** comes from `SYCG`'s four `*Actuator` flags (`cache/bedconfiguration/b`).
  - "Split" means both sides have at least one actuator.
  - On a split bed there is one capability per side, and each sends its own `side`, **including foot moves** (`FuzionFlexFitCapabilityFactory` = `flexfit/T`).
  - The app never redirects foot commands to the side that owns the foot actuator.
  - On a split-head, shared-foot Fuzion base, read `SYCG` and test whether `left foot` is accepted or redirected.

---

## 4. Safety and timing behavior in the app

### Hold-to-move ("MICROADJUSTMENT" control mode)
`FlexFitViewModel.d0` (onMicroAdjustHold) → `C3222i0` → `doMicroAdjusting` (`C3200c0`) → `FlexFitCapability.n(target, part)`. The target is **100 for UP and 0 for DOWN**. Release (`e0`) cancels the job.

**MCR** (`LegacyFlexFitCapability` = `capability/flexfit/C3948a0.n`):
1. On a 360 base, read pinch state first (`D0` → `GetPinchStateCall`).
2. Send `MicroAdjustFoundationCall` (cmd `0x11`, the 12-byte struct).
3. Wait 333 ms (`D()`).
4. **Movement loop** (`B()`, recovered with `-m fallback`): yield, send **keep-adjusting status** (cmd `0x12`, sub-nibble side+2, `flexfit/q0` → `C2988l`), yield. If still moving, wait 333 ms and repeat; otherwise exit.
   - The foundation probably needs these polls to keep moving (a dead-man pattern). That is inferred from the name "getFoundationStatusFromDeviceAndKeepAdjusting" and the BLE-only policy.
5. When it stops: `y()` re-reads pinch and status to check for obstruction (360 only).
6. **On cancel/release:** "Ble command cancelled." → `z()` → `o0` sends **`StopFoundationMovementCall`** (SE `MFHx`=`"110"`), then a plain status read (`C2991o(0)`).

**Fuzion** (`FuzionFlexFitCapability` = `C3957f.n`, `flexfit/N`):
1. Send `ASTM side part 100|0` once.
2. Poll `ACTM` + `ACTG` about every 333 ms (`flexfit/K`).
3. **Release → `ACHA`.** This halts every actuator, so it also stops the partner's side.
4. A replacing job sends no halt.

### Set-and-go (arrow taps)
`FlexFitAdjustmentIncrement`: SetAndGo = 4 per tap, MicroAdjustment = 1. Targets are clamped to 0..100 (`flexfit/screen/C3213e.f27992a = IntRange(0,100)`, `C3211d.a`).

- **MCR** (`C3948a0.h` / `t()`):
  1. Send SE `MFUx`/`MFFx` `"<pos>_0"` (`C3952c0` → `W`).
  2. Loop: wait **1000 ms**, read status (cmd `0x12`).
  3. Done when `|target − current| < 3`, or when it stops: `x()` waits 500 ms and confirms.
  4. On cancel, `z()` sends the stop.
- **Fuzion:** `ACTS`, then poll `ACTM` + `ACTG` every 1000 ms (`C3957f.h/y`).

### Presets
- **MCR:** `X` (cmd `0x15`), then the same 1000 ms status loop (`C3948a0.p` → `C3956e0`, `t()`).
- **Fuzion:** `ASTP`, then poll `AGCP` every 1000 ms until it is no longer `in_progress`.
- **Partner Snore, both protocols:** the UI sends **SNORE to the other side's capability** (`flexfit/screen/S`: `theOtherSide()`).
- **MCR homing/reset:** "resetFoundationToFlat" sends the Flat preset (`C3948a0`, `resetFoundationToFlat$2`). If status bit5 `needsHoming` is set, recover with Flat.
- **Preset timers:** UI choices 15/30/45/60/120/180 min. Timer 0 means cancel (Fuzion `ACCP`).

### Firmness
`UniversalSleepNumberCapability.performSetSleepNumber` (`capability/sleepnumber/k`):
1. Send the set command (MCR `m0` or Fuzion `PSNS`).
2. **Wait 1000 ms.**
3. Poll "is inflating" until the pump is idle.

- On cancel: MCR `ForceIdleCall` (cmd `0x02`) or Fuzion `PSNI`, wait 500 ms, then refresh (`sleepnumber/o`).
- UI range is 5–100. The step size wasn't found in code; 5 is likely.
- Avoid sending a set to the other side while the pump reports busy (status `[0]≠0`).

### General
- One command in flight. MCR timeout 900 ms × 3 retries; Fuzion 7.0 s / 7.1 s.
- The 12-byte motion struct is pre-filled with `0xFF` ("don't move this axis").
- Footwarming on a single (non-split) bed: the app also sends the same setting to the other side ("single queen logic", `capability/temperature/footwarming/i`).

---

## 5. Discrepancies vs the community docs

### `sleepnumber-ble/docs/PROTOCOL.md`
This doc was written from v5.3.32 plus live captures on an i8/360 FF2.

1. **Side nibble is reversed.** It says `0=left, 1=right`. The app uses **RIGHT=0, LEFT=1** (`Bed.Side.toDeviceValue`, `C2964b.b`). Its captures ("byte 1 = side 0's head") actually show **right** head = byte 1, which matches the app.
2. **Pump status labels are swapped.** It gives `[1]=L_SN, [2]=R_SN`. The app reads `[1]=right`, `[2]=left` (`E.fromMcrReply`, `Ud.b` field names). Only bytes 0–2 are parsed; the reply H9 high nibble is chamber type.
3. **Header field names are wrong.** Bytes 1–2 and 6–7 are the client's source address and 3–4 the bed's destination (`mcr/manager/i`). "Target/sub/echo" is incorrect. Both conventions happened to work for them.
4. **Bind is misdescribed.**
   - The payload is a persisted random 64-bit client ID (`he.b.e`), not a token that contains the bed address.
   - The app takes its own address from `reply.payload[8..9]` and the bed address from `reply.header[1..2]`. It doesn't derive anything from the MAC.
5. **System-status flag bits are swapped.** In byte 3, bit3 = `hasFootWarming` and bit4 = `hasUnderBedLight` (`C2993q`, `device/model/a` constructor order). Their sample `0x15` therefore means dual board + foot control + **under-bed light** (no footwarming), which `device/model/a.b()` classifies as 360 FF2.
6. **Foundation status layout:**
   - Byte 4 is the **left** foot, not a duplicate (it tracks byte 3 only because the foot is shared).
   - Bytes 5–8 are preset-timer remaining, not unused.
   - Actuator status bytes are **9–12** (RH, LH, RF, LF bitfields), not 10–13.
   - Byte 13 is timer presets; byte 14 is the current preset per side (low nibble right, high nibble left).
   - Byte 0 bits: `0x40` = configured, `0x20` = needs homing.
7. **SET-position bytes 1 and 3** are "apply" flags (`00`) next to the 1-byte targets, not high bytes. Bytes 4–11 carry massage, preset timer and preset ID.
8. **Outlet read (cmd 20):** bytes 1–2 are the **timer** (LE), not brightness (`C2987k`).
9. **Under-bed light write** is foundation cmd `0x13` with outlet 3 `[on, timer]`, or intensity cmd `0x24` `[FF, right, left]`. Node `0x92` cmds 18/19 are kids/smart-outlet calls, not UBL.
10. **Node `0x51` is SLEEP_EXPERT, not a stub.** The app addresses it as `0x52` with key/value commands `0x1B`/`0x1D` (§2.6). The **stop** (`MFHL/MFHR "110"`) and absolute **set-position** (`MFU*/MFF* "<pos>_0"`) go there.
    - The doc also calls `0x72` "device management"; the app calls that node PROXY. `0x32` is Sense & Do and `0x92` is Smart Outlet.
11. **Missing entirely:**
    - The stop command.
    - The keep-adjusting status poll (sub-nibble side+2) used during hold-to-move.
    - SE set-position, auto-UBL `MUAS`/`MUAG`, and ForceIdle (pump cmd `0x02`).
    - The reply NACK nibbles (`0xE`/`0xF`) and the 3× timeout retry.
12. **"Whole-bed preset" behavior** is an observation of the firmware. The app always sends presets with one side. Partner-Snore = SNORE sent to the other side.
13. Consistent with the app: the running-sum checksum, `16 16` sync, cmd values 17/18/21/37/40/41/42/97, and node `0x42` vs `0x02` for byte 5.

### `ha-adjustable-bed/docs/beds/sleep_number.md` and its code
This doc was based on 5.4.11 and is largely consistent with this analysis. It already has right=0/left=1, SE `MFHx "110"` stop, flags `8=warming, 16=light`, the bond-then-Auth order, all-zero = connection limit, and the global `ACHA`.

Points to add or correct:
1. **Protocol choice.** HA selects by advertised service. The app selects by `Bed.Generation`, and only `FUZION` uses Fuzion; 360 beds use MCR. The service check is a reasonable stand-in.
2. **Fuzion notification filter** also accepts the all-zero UUID as a broadcast (`C2592h.p()`), as well as our own session UUID.
3. **Fuzion chunk size** is MTU−4 (MTU−5 on Android 14+) after requesting MTU 517. Writes are WRITE_NO_RESPONSE to `421e00f3`.
4. **`ACTH`** (per-actuator halt) exists in the catalog but the app never sends it. The app's release behavior is `ACHA`.
5. **Fuzion hold-to-move** uses `ASTM` (target 100/0) plus a ~333 ms `ACTM`/`ACTG` poll. Set-and-go uses `ACTS` plus a 1000 ms poll.
6. **MCR hold-to-move** uses cmd `0x11` plus the side+2 keep-adjusting poll every ~333 ms. Set-and-go uses SE `MF[U|F][L|R]`, not cmd `0x11`.

### Split-head / shared-foot (both protocols)
- On MCR, the app's actuator list for SPLIT_HEAD includes LEFT_FOOT when `hasFootControl` is set (`device/model/a.a()`). The app relies on firmware to map either side's foot command onto the single foot motor.
- On Fuzion, the actuator set comes from `SYCG` flags, and foot commands keep the sender's side.
- Neither stack remaps the foot to an "owner" side.

---

## 6. Open items (needs a live bed)

- Confirm the owner's bed advertises MCR (`ffffd1fd…ee0`), as expected for generation "360".
- Whether MCR motion actually stops without the side+2 keep-adjusting polls (dead-man), and how long the timeout is.
- What a left-side foot command does on the owner's SPLIT_HEAD base: moves the shared foot, or is ignored. Static analysis says the app sends it and expects it to move.
- The meaning of the `"110"` stop value and the `"_0"` suffix in set-position. The app sends both verbatim; there is no semantic source.
