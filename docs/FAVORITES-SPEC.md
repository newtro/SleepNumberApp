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
- `version` is 1. A reader must reject a file whose version is higher than it supports. An
  unreadable or missing file means "no favorites" for that bed.

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

### 2.3 Cancellation (STOP or Cancel)

The STOP button stays available throughout. Pressing it or the progress card's Cancel:

- stops the sequence immediately; no further step starts;
- during a move step sends STOP(side) for the favorite's side;
- during the firmness step, if the set was already sent, sends PUMP-IDLE;
- the global STOP additionally runs the normal stop-everything sequence (both sides' stop keys,
  pump idle) and invalidates every queued request, so a request the sequence had already queued
  can never reach the bed after STOP (iOS: the session "stop generation" captured when the apply
  starts; any request carrying an older generation is dropped).

Any step failure (timeout, stopped short, NACK, actuator fault, needs-homing) ends the sequence
and is shown to the user. Steps that already completed are not undone.

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

## 4. Test vectors to reproduce

From `BedKit/Tests/FavoritesTests.swift`:

- `head 140, foot -12, sleepNumber 47` → `100, 0, 45`; empty name → `"Favorite"`.
- A file containing `head 250, foot -3, sleepNumber 42` loads as `100, 0, 40`.
- Reorder `[A, B, C]`, move offset 2 to 0 → `[C, A, B]`; then move offset 0 to 3 → `[A, B, C]`.
- Start head 0, foot 50; apply head 40, foot 20, SN 65 → commands in order: move head 40,
  move foot 20, Sleep Number 65.
- Values already within tolerance (head 41 vs 40, same foot and SN) → no commands sent.
- Cancel during the head move → commands: move head, STOP; foot and firmness never sent.
- Cancel after the firmness set → the last command is PUMP-IDLE.
- STOP pressed before a queued SET-POSITION is sent → the SET-POSITION never reaches the wire.
