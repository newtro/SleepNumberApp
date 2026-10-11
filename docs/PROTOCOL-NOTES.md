# Protocol notes

What Bed Control sends, why, and which details are still unverified on the owner's bed
(Sleep Number 360 p6, Split E King, FlexFit 2; BLE name `64:db:a0:9a:32:d3`, the MAC-as-name
pattern of the MCR/BAM module).

**The authority is `docs/PROTOCOL-FROM-APK.md`**: a static analysis of the official SleepIQ
5.4.11 Android app taken from the owner's phone. Where the community references disagree with
it, the app follows the APK spec. The community sources (ha-adjustable-bed, sleepnumber-ble,
BedRemote) supplied live captures and test vectors. Nothing here has been confirmed on this
particular bed yet; the Diagnostics screen logs every frame so it can be.

## Protocol detection

After connecting, the app discovers both services and picks MCR `ffffd1fd-…-ee0` or Fuzion
`09d23fae-…`. The official app chooses by bed generation (only `FUZION` uses Fuzion; 360 and
legacy use MCR), and the service is a reliable stand-in. Scan candidates: either service UUID,
a "Smart bed…" name, a MAC-style name (Select Comfort OUI `64:DB:A0` ranks highest and implies
MCR), or manufacturer data with company ID `0x534E`.

## MCR (APK §2)

- Write `…ee2` (with response when the characteristic allows it, as the app does) in 20-byte
  pieces; notify `…ee1`. 128 ms delay after discovery before enabling notifications. Whole
  frames are written atomically so chunks of two frames never interleave.
- Frame: `16 16 | node src_hi src_lo dst_hi dst_lo node src_hi src_lo cmd sub<<4|len | payload
  | ck_hi ck_lo`, checksum `s+=b; r+=s; r & 0xFFFF` over header + payload, big-endian.
- Bind: pump node, cmd 0, zero addresses, an 8-byte client ID. The app (like the official one)
  generates a random 64-bit ID once and persists it. The reply's header bytes 1-2 are the bed
  address and payload bytes 8-9 our address. Every later request puts ours in bytes 1-2 and
  6-7 and the bed's in 3-4. The zero-payload bind vector `16 16 02 00 00 00 00 02 00 00 00 08
  00×8 00 86` is kept as a unit test of the builder.
- Reply matching (`mcr/manager/a`): response bit, same node family and command, and either a
  zero destination or destination = our address and source = the bed. Sub-nibble `0xE` = bad
  parameter, `0xF` = NACK. One request in flight, 900 ms timeout, 3 retries.
- Sides: **right = 0, left = 1**. Settings has "Swap left and right" in case a sleeper's idea
  of left differs.
- Pump: status cmd `0x12` → `[busy, SN right, SN left]` (busy is pump-wide). Set cmd `0x11`
  `[0, SN]`, 5-100 step 5. As in the app: send the set, wait about 1 s, poll every second until
  idle. A change for the other side waits in a queue until the pump is idle; a new value for
  the side already adjusting sends ForceIdle (cmd `0x02`), waits 500 ms, then the set.
- Foundation status cmd `0x12` (15 bytes): flags (bit0 moving, bit5 needs homing, bit6
  configured), right/left head, right/left foot, preset timers, actuator status bytes 9-12
  (RH, LH, RF, LF; moving/limit/current/movement fault bits), timer presets, current presets.
- Presets cmd `0x15` `[id, 0]`. Offered per the foundation's system status (cmd `0x25`): FF2 →
  Favorite, Flat, Zero G, Snore and Partner Snore (Snore sent to the other side); FF3 adds
  TV and Read. If system status cannot be read, the FF2 set is shown.
- System status flags: bit0 dual board, bit1 massage, bit2 foot control, bit3 foot warming,
  bit4 under-bed light. The light control is hidden only if the bed reports no light.
- Under-bed light: write cmd `0x13` outlet 3 `[on, timer LE]` (timer 0); read cmd `0x14`
  outlet 3 → `[on, timer LE]`.
- Hold to move (the app's micro-adjust): on 360 bases read the pinch sensors first, then cmd
  `0x11` with the 12-byte struct, target 100 (up) or 0 (down), every other byte `0xFF`; then
  every 333 ms the "keep adjusting" status read (sub-nibble side+2). The loop ends on release,
  when the foundation reports it stopped (after a 1 s start grace), on an actuator fault or
  homing flag, or at a 45 s safety deadline. Release always sends the stop and a final read.
- Foundation SET safety: the 12-byte payload must set exactly one axis (`[target 0-100, 00]` at
  offset 0 head or 2 foot) with every other byte `0xFF`. `MCRRequest` can only be built through
  vetted builders, the builder and encoder validate the rule, and the session re-decodes and
  validates the raw bytes of every frame before writing. Unit tests cover all legal targets and
  the illegal shapes.
- Stop: Sleep Expert key write (node `0x52`, cmd `0x1B`) `MFHR` / `MFHL` = `"110"`. Stop frames
  are written immediately (bypassing the request queue), then sent again through the queue so
  the acknowledgement is checked. STOP also invalidates every queued or retrying request, so a
  preset waiting behind a slow poll, or a retry after a lost reply, cannot go out after STOP.
  The big STOP button stops both sides and sends pump ForceIdle. After a stop the app reads
  foundation status and re-sends the stop (up to 3 times) while it still reports motion. Stop
  frames are exempt from the stop generation, so two overlapping stops never cancel each other.
  Disconnecting or switching beds waits (up to 3 s) for the stop sequence before dropping the
  link, and a stalled write triggers a stop before the link is dropped.
- Split head, shared foot: as in the app, foot commands carry the sleeper's own side and the
  firmware drives the single motor. The foot control has an "As Left / As Right" picker.
- Absolute set-position (`MFU*`/`MFF*` = `"<pos>_0"`) has a tested builder but no UI yet.

## Fuzion (APK §3)

- Service `09d23fae…`; Auth `8d4675a5…`; BamKey `421e00f3…` (bulk transfer is not used: the app
  subscribes to it only for file transfers).
- Order: discover → read Auth (iOS pairs here if the bed demands encryption; the user may need
  to press the pair button) → validate → subscribe BamKey → `SYCG`. Auth must be 16 bytes;
  all-zero = connection limit, UUID 1 or short = rejected. ATT insufficient
  authentication/encryption maps to "press the pair button"; "peer removed pairing information"
  maps to "forget the device in iOS Settings".
- Frame: `"fUzIoN"` + uint32 LE total length + UTF-8 `KEY args` (trailing space with no args) +
  CRC-32 LE. Writes are write-without-response in MTU−4 pieces (iOS negotiates the MTU itself;
  the app cannot request 517).
- A BamKey notification equal to our session UUID (or all zeros) means "response ready"; the app
  reads BamKey until one complete blob arrives (7.128 s). Reads abandoned by a timeout have their
  late results discarded so they cannot be handed to the next command.
- Commands: `SNCG`, `PSNS`, `PSNI`, `ASTP side preset` (presets), `ASTM side loc 100|0` (hold),
  `ACTG`, `ACTM`, `ACHA` (release and STOP; global, both sides), `UBLG`, `UBLS`, `SYCG`.
  Hold polls `ACTM` + `ACTG` every 333 ms. Foot commands use the sleeper's own side.
  Release and STOP write `ACHA` immediately, then again through the queue until `PASS`, then
  re-send while `ACTM` reports movement (up to 3 times).

Saved per-side favorites are specified in `docs/FAVORITES-SPEC.md`.

## Unverified

1. **Dead-man behaviour of MCR holds.** The APK spec infers that the foundation keeps moving
   only while the keep-adjusting polls arrive. If not, the bed would run toward 100/0 until the
   stop key arrives. The app always sends the stop on release, on STOP, when backgrounded and
   before disconnecting, but whether the bed stops by itself if the phone dies mid-hold is
   untested. **Test before relying on it:** start a hold, switch the phone to airplane mode,
   and check the bed stops within about a second. If it does not, holds should go back to
   bounded targets (current ±15, extended as each is reached) using the same cmd `0x11`.
2. **`MFHx = "110"` as the stop.** Static analysis only; its meaning is unknown. The earlier
   BedRemote foundation ForceIdle (`0x42` cmd `0x02`) is not sent because the app never uses it.
3. **Left foot on a split-head base.** The app sends foot moves with either side; the spec
   expects the firmware to move the shared motor for both. Untested on this base.
4. **Side labels.** Right = 0 / Left = 1 comes from the app's code. Whether "left" means the
   sleeper's left as lying in bed should be checked once with a short head move; Settings can
   swap it.
5. **Persisted random client ID** in the bind (the app's method). The live community tools sent
   all zeros and reported both work.
6. **Hold start grace.** The app exits its poll loop as soon as status says "not moving"; this
   app waits 1 s first, so a slow motor start is not taken as finished.
7. **Presets.** Taken from system status per the app's model table. Partner Snore is Snore sent
   to the other side.
8. **Manufacturer ID `0x534E`** for scan matching came from the task brief; the APK spec shows
   MCR scanning by service plus SmartPump manufacturer data, and Fuzion manufacturer data with
   company ID `0xFFFF`.
9. **Fuzion as a whole** is static analysis only; no reference has run it against hardware.
10. **Fuzion urgent `ACHA`** is written outside the request queue so STOP never waits; queued
    commands are dropped by the stop generation, but a command already waiting for its
    "response ready" hint could consume ACHA's hint (logging only).
