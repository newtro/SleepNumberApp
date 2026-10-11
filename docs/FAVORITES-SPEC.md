# Per-side favorites: specification

This feature lets each sleeper save any number of named bed positions and firmness settings,
and recall them with one tap. An Android port should reproduce this document exactly. The iOS
reference implementation is `BedKit/Sources/Common/Favorites.swift` (model, apply sequence,
protocol adapters) and its tests in `BedKit/Tests/FavoritesTests.swift`. Protocol details come
from `docs/PROTOCOL-FROM-APK.md`.

The bed's built-in **Favorite preset** (MCR preset ID 1, Fuzion `favorite`) is unrelated and
stays available as a normal preset button.

## 1. Data model

### 1.1 Favorite

| Field | Type | Rule |
|---|---|---|
| `id` | UUID | Random, assigned on creation, never changes |
| `name` | string | Trimmed of surrounding whitespace, at most 40 characters, `"Favorite"` if empty |
| `head` | int | That sleeper's head position, percent, clamped to 0...100 |
| `foot` | int | The shared foot position, percent, clamped to 0...100 |
| `sleepNumber` | int | That sleeper's firmness, snapped to 5...100 in steps of 5 |
| `createdAt` | timestamp | ISO 8601 (UTC) in the file |

Normalization runs on creation, on every edit, **and when a file is read**, so a hand-edited or
corrupt value can never reach the bed:

- positions: `min(max(v, 0), 100)`
- Sleep Number: clamp to 5...100, then round to the nearest multiple of 5 (`47 → 45`,
  `48 → 50`, `0 → 5`, `103 → 100`).

A favorite belongs to exactly one side (Left or Right). Left/Right mean the sleeper's side as
they lie in bed, after the app's optional "Swap left and right" setting. The wire mapping is
MCR right = 0, left = 1, or Fuzion `left`/`right`.

### 1.2 Storage

One JSON document per bed, keyed by the bed's identifier. iOS uses the CoreBluetooth
peripheral UUID; Android should use the bed's Bluetooth MAC address. iOS stores it at
`Application Support/Favorites/<bedID>.json`, with every character other than letters, digits
and `-` stripped from the file name. Writes are atomic.

```json
{
  "bedID" : "8C0E4A6B-1F2D-4E3C-9A1B-5D6E7F8091A2",
  "left" : [
    {
      "createdAt" : "2026-10-10T21:15:03Z",
      "foot" : 12,
      "head" : 38,
      "id" : "6F9619FF-8B86-D011-B42D-00CF4FC964FF",
      "name" : "Reading",
      "sleepNumber" : 45
    }
  ],
  "right" : [],
  "version" : 1
}
```

- `left` / `right` are ordered arrays; the order is the display order.
- `version` is 1. A missing file means "no favorites" for that bed.
- A file that exists but cannot be read (corrupt JSON, or a `version` higher than supported)
  must **never be overwritten**: rename it to `<name>.unreadable-<timestamp>.json` (timestamp
  ISO 8601 with `:` replaced by `-`), log it, tell the user where it went, and start from an
  empty list.

### 1.3 Operations

- **Save current as favorite**: poll the bed first (pump status and foundation status), then
  open an editor pre-filled with name `"<Side> <n>"` (n = count + 1), `head` = that side's
  head, `foot` = that side's foot reading (falling back to the other side's foot byte), and
  `sleepNumber` = that side's current Sleep Number. The user can change the name and all three
  values (sliders 0-100 for positions, stepper 5-100 step 5 for firmness) before saving.
  Saving appends to the end of that side's list.
- **Edit / rename**: same editor; replaces the entry with the same `id` in place.
- **Delete**: removes by `id`.
- **Reorder**: standard list move (move the items at the given offsets to before the
  destination index).
- **Apply**: an explicit "Apply" tap only. Nothing ever applies a favorite automatically.

### 1.4 When Apply is allowed

Apply is refused, with a message and **without changing any state**, when any of these hold:

- the bed is not connected and ready;
- a press-and-hold move is active, or another favorite is being applied;
- a command the app sent is still in flight (preset, light, firmness set);
- the app's own firmness change is still running (it started one and the pump has not yet
  reported idle);
- the last foundation status said the bed is moving ("wait for the bed to stop, or press STOP").

When Apply starts, queued firmness requests for either side are dropped (the favorite sets this
side's firmness itself) and their pending target values are cleared. While a favorite runs,
polling, holds, presets, the light, and firmness Set are all refused the same way.

## 2. Apply sequence

Steps always run in this order, one at a time:

1. **Head** of the favorite's side to `head`
2. **Foot** to `foot`, sent with the favorite's side (the official app sends foot commands with
   the sleeper's own side; on a split-head base the firmware drives the one shared foot motor)
3. **Sleep Number** of the favorite's side to `sleepNumber`

Constants, taken from the official app's set-and-go and firmness flows:

| Name | Value |
|---|---|
| Move poll interval | 1000 ms |
| Stopped-confirmation delay | 500 ms |
| Target tolerance | reached when `abs(target - current) < 3` |
| Pump start delay | 1000 ms after the set is acknowledged |
| Pump poll interval | 1000 ms |
| Move safety timeout (app-side bound) | 90 s per axis |
| Pump safety timeout (app-side bound) | 240 s for the wait-for-idle phase, and again for the adjustment |

### 2.1 Move step (head or foot)

```
read position P and moving flag M for (side, axis)
if |P - target| < 3 and not M:  report "already there", step done
send SET-POSITION(side, axis, target)
loop:
    wait 1000 ms
    read (P, M); report progress P
    if |P - target| < 3: step done
    if not M:
        wait 500 ms; read (P, M); report progress P
        if |P - target| < 3: step done
        if not M: FAIL "<axis> stopped at P%, target T%"     (remaining steps do not run)
    if 90 s elapsed since the SET: send STOP(side); FAIL timeout
```

### 2.2 Firmness step

```
read pump (SN of side, busy)
if busy:                                  # the pump serves one side at a time
    report "waiting for pump"
    poll every 1000 ms until not busy (fail after 240 s)
if SN == target: report "already there", step done
send SET-SLEEP-NUMBER(side, target)
wait 1000 ms
loop:
    read pump; report progress SN
    if not busy: step done
    if 240 s elapsed: FAIL timeout
    wait 1000 ms
```

### 2.3 Ending a step early: cancellation, errors, stops

**Which stop is sent.** Once a move step has sent its SET-POSITION, any way out of that step
other than reaching the target sends **STOP(side) confirmed**:

- cancellation (STOP button, the progress card's Cancel, the app going to the background, or
  background time expiring);
- any error: move timeout, stopped short, a status read failing, an actuator fault,
  needs-homing, a NACK.

Once the firmness step has *started sending* SET-SLEEP-NUMBER (marked before the request is
sent, so a cancel while the set is in flight counts), any way out other than "pump idle" sends
**PUMP-IDLE**. Before a SET is sent, ending the step sends nothing.

**STOP(side) confirmed** = up to 3 attempts of: send the side's stop key immediately (bypassing
the request queue) and again through the queue; wait 300 ms; read foundation status; finish as
soon as the status says not moving. Fuzion: `ACHA` immediately, `ACHA` through the queue until
`PASS`, wait 300 ms, `ACTM`; repeat while anything moves (up to 3).

**The stop always runs to completion.** It runs in a fresh task that does not inherit the
cancellation of the apply, so a cancelled apply still gets its queued and confirmed stop frames
and status reads. (iOS: `FavoriteApplier.uncancelled`.)

**Nothing queued survives STOP.** The global STOP bumps the session's stop generation. Every
request the apply issues carries the generation captured when the apply began, and a request
with an older generation is dropped before it reaches the wire. Stop frames and pump-idle are
exempt, so overlapping stops never cancel each other. The global STOP also runs the normal
stop-everything sequence (both sides confirmed, pump idle).

**Backgrounding.** When the app goes to the background the running favorite is cancelled (with
the stop above), the same as a held move. The apply also runs under an OS background task whose
expiry handler cancels it, so the OS never suspends the app mid-move without a stop.

**Reporting.** Any failure ends the sequence. Steps already completed are not undone. The user
sees "<name> did not finish: <reason>. The bed was told to stop." A cancellation is not shown
as an error.

While a favorite is being applied, polling, hold-to-move, presets and firmness changes are
blocked, with a message pointing at STOP. Progress is shown as "Step i of 3: <status>" with live
positions and Sleep Number.

## 3. Wire commands

### 3.1 MCR (owner's bed)

Addressing, framing, checksum, reply matching and retries are as in `PROTOCOL-FROM-APK.md` §2
(client address in header bytes 1-2 and 6-7, bed address in 3-4).

| Operation | Frame |
|---|---|
| SET-POSITION head | Sleep Expert short write: node `0x52`, cmd `0x1B`, sub 0, payload = ASCII key `MFUR` (right) or `MFUL` (left) followed by ASCII `"<pos>_0"` |
| SET-POSITION foot | same with key `MFFR` / `MFFL` |
| Read position | foundation status: node `0x42`, cmd `0x12`, sub 0. Head = byte 1 (right) / 2 (left); foot = byte 3 (right) / 4 (left); moving = byte 0 bit 0 or any of bytes 9-12 bit 0. Fail if byte 0 bit 5 (needs homing) or the axis's actuator byte reports a fault (bits 1-4, 6) |
| STOP(side) | Sleep Expert short write `MFHR` / `MFHL` + `"110"` |
| SET-SLEEP-NUMBER | pump: node `0x02`, cmd `0x11`, sub = side, payload `[0x00, SN]` |
| Read pump | node `0x02`, cmd `0x12`, sub 0 → `[busy, SN right, SN left]` |
| PUMP-IDLE | node `0x02`, cmd `0x02`, sub 0 |

Example (client `0xA1B2`, bed `0x32D3`): right head to 35 →
`16 16 52 a1 b2 32 d3 52 a1 b2 1b 08 4d 46 55 52 33 35 5f 30 49 02`.

The value has no leading zeros or padding: `0_0`, `7_0`, `35_0`, `100_0`.

### 3.2 Fuzion

| Operation | BamKey |
|---|---|
| SET-POSITION | `ACTS <side> <head\|foot> <pos>` |
| Read position | `ACTM` (moving flags: right head, right foot, left head, left foot) and `ACTG <side> <head\|foot>` |
| STOP(side) | `ACHA` (global: also stops the other side) |
| SET-SLEEP-NUMBER | `PSNS <side> <SN>` |
| Read pump | `SNCG <side>` → `adjusting ambient user`; busy = `adjusting == 1`, SN = `user` |
| PUMP-IDLE | `PSNI` |

## 4. Open items

Verified 2026-10-10: saving and applying favorites works on the owner's 360 p6 / FlexFit 2 (MCR).
The items below are about cases that test didn't cover.

- **Foot side for favorites.** The apply sends the foot with the favorite's own side. The main
  screen's foot control has an "As Left / As Right" picker (`footSide`) that favorites ignore.
  On a split-head base the firmware is expected to drive the shared foot either way; if a real
  bed only accepts one side, favorites should use `footSide`. Untested on hardware.
- **Fuzion pump rule.** On Fuzion, `busy` comes from `SNCG` of the favorite's side only, and a
  single-chamber bed maps both sides to `right`. Whether the other side's adjustment shows as
  busy there is unverified (the owner's bed is MCR).

## 5. Test vectors to reproduce

From `BedKit/Tests/FavoritesTests.swift`:

- `head 140, foot -12, sleepNumber 47` → `100, 0, 45`; empty name → `"Favorite"`.
- A file containing `head 250, foot -3, sleepNumber 42` loads as `100, 0, 40`.
- Reorder `[A, B, C]`, move offset 2 to 0 → `[C, A, B]`; then move offset 0 to 3 → `[A, B, C]`.
- Start head 0, foot 50; apply head 40, foot 20, SN 65 → commands in order: move head 40,
  move foot 20, Sleep Number 65.
- Values already within tolerance (head 41 vs 40, same foot and SN) → no commands sent.
- Cancel during the head move → commands: move head, STOP; foot and firmness never sent; the
  STOP ran in a task that was not cancelled.
- Actuator fault on a status read after the head SET → move head, STOP; error reported.
- Head stops short at 30 (target 60) → move head, STOP; error "head stopped at 30%".
- Cancel after the firmness set → the last command is PUMP-IDLE.
- Cancel while the firmness set itself is still in flight → set, PUMP-IDLE.
- MCR confirmed stop where the first status read says moving and the second says stopped →
  stop key sent 4 times (immediate + queued, twice), 2 status reads.
- STOP pressed before a queued SET-POSITION is sent → the SET-POSITION never reaches the wire.
