# Protocol notes

What Bed Control sends, why, and which details are still unverified on the owner's bed
(Sleep Number 360 p6, Split E King, FlexFit 2; BLE name `64:db:a0:9a:32:d3`, which is the
MAC-as-name pattern of the older MCR/BAM module).

Sources, in order of weight:

1. **sleepnumber-ble** (JonGilmore): live tests on an i8 / 360 FlexFit 2 with BAM firmware
   0.4.1d9, plus Android HCI captures of the official app. `docs/PROTOCOL.md`.
2. **BedRemote**: a Swift MCR app driven on a real bed. Side numbering and the motion
   start/stop sequence come from its traces of the official app.
3. **ha-adjustable-bed** (kristofferR): static analysis of SleepIQ 5.4.11, both protocols.
   Not hardware-validated by its author.

Where live evidence and static analysis disagree, the app follows live evidence and the
conflict is listed under "Unverified" below.

## Protocol detection

After connecting, the app discovers both services and picks the protocol from whichever
exists: MCR `ffffd1fd-388d-938b-344a-939d1f6efee0` or Fuzion
`09d23fae-90e6-44c2-95b6-0b3d0f1abf25`. Scan candidates: either service UUID in the
advertisement, a name starting "Smart bed", a MAC-style name (Select Comfort OUI `64:DB:A0`
ranks highest and suggests MCR), or manufacturer data with company ID `0x534E`.

## MCR (BAM)

- Notify `…fee1`, write `…fee2`. Writes go in 20-byte chunks (the bed stays at MTU 23),
  write-without-response when the characteristic offers it, with CoreBluetooth flow
  control. Whole frames are written atomically so chunks of two frames never interleave.
- Frame: `16 16` + `[cmd, tgt_hi, tgt_lo, sub_hi, sub_lo, status, echo_hi, echo_lo, func,
  side<<4|len]` + payload (0-15 bytes) + checksum (`s+=b; r+=s`, `r & 0xFFFF`, big-endian)
  over header + payload. cmd and status both carry the node: pump `0x02`, foundation
  `0x42`, Sleep Expert `0x52`, device management `0x72`. Replies clear the low bit
  (`0x01`, `0x41`, …) and set `0x80` in func.
- Reassembly: notifications are a byte stream; the parser resyncs on `16 16`, uses the
  length nibble, and verifies the checksum (dropping one byte and rescanning on failure).
- Bind: pump func 0, zero addresses, 8 zero bytes (`16 16 02 00 00 00 00 02 00 00 00 08 00×8
  00 86`). The bed address for every later `sub` field is the reply's `target`; fallback
  reply payload bytes 8-9, then the last two MAC octets from the name. Target/echo stay 0.
- Reply matching: one request in flight; match on response bit, node high nibble and
  function code. Timeout 1.2 s, 2 retries. The bed never pushes state, so the app polls
  (5 s idle, 2 s while moving or pumping).
- Pump: read func 18 side `0xF` → `[active, SN side0, SN side1, pumping0, pumping1]`. Set:
  pump ForceIdle (func 2), then func 17 side `[0, value]`, value 5-100 step 5. Only one side
  adjusts at a time: the controller queues the other side until both pumping flags are
  clear and at least 6 s have passed since the last set (a new set interrupts the running
  one).
- Foundation status: func 18 (15 bytes). Byte 0 flags (bit 0 moving, bit 5 needs homing,
  bit 6 configured), bytes 1-4 head side0, head side1, foot side0, foot side1, bytes 9-12
  per-actuator flags, byte 14 presets (low nibble side 0, high nibble side 1). Plain read uses
  selector 0; during a hold the app uses selector side+2 ("continued adjustment").
- Presets: func 21 side `[preset, 0]`; 1 Favorite, 2 Read, 3 TV, 4 Flat, 5 Zero G, 6 Snore.
- Under-bed light: write func 19 outlet 3 `[on, 0, 0]`; read func 20 outlet 3 → `[on, …]`.
- Foundation SET (func 17) safety: the payload is always the full 12 bytes, exactly one
  axis set (`[pos, 0]` at offset 0 for head or 2 for foot, pos 0-100) and every other byte
  `0xFF`. `MCRRequest` can only be built through vetted builders, both the builder and the
  wire encoder validate the rule, and the session re-decodes and validates every frame's raw
  bytes before writing. Unit tests cover all 202 legal targets and the illegal shapes.
- Press and hold: read position, send a target at most 10 points away, poll with the
  continued-adjustment read every 333 ms, and send the next 10-point target as each one is
  reached. If the app, radio or phone dies, the bed stops within one increment. Hard limits:
  45 s per hold, abort after 4 s without position change. Release (finger up, gesture
  cancelled, view gone, app backgrounded) always sends the stop sequence.
- Stop: Sleep Expert short write (`0x52` func 27) `MFHR110` (wire side 0) / `MFHL110`
  (side 1), the app's STOP, then foundation ForceIdle (`0x42` func 2). The big STOP button
  sends both sides plus pump ForceIdle. Stop frames bypass the request queue.
- The shared foot is commanded with wire side 0 and read from foot byte 0.

## Fuzion

- Service `09d23fae…`; Auth `8d4675a5-b5fa-42b2-b587-0ee71c46b709`; BamKey
  `421e00f3-ae76-4c49-ab6e-39e4df4a5333`; bulk `0ec9a5a3-8ac3-4582-92f3-1666421f323d`.
- Order: discover → read Auth (iOS pairs here if the bed demands encryption; the user may
  need to press the bed's pair button) → validate → subscribe BamKey (and bulk) → `SYCG`.
  Auth must be 16 bytes; all-zero = connection limit (close the Sleep Number app), UUID 1
  or a short value = rejected. ATT insufficient authentication/encryption maps to "press the
  pair button"; CoreBluetooth "peer removed pairing information" maps to "forget the device
  in iOS Settings".
- Frame: `"fUzIoN"` + uint32 LE total length + UTF-8 text + CRC-32 (zlib) LE over everything
  before it. Text is `KEY args…`, with a trailing space when there are no arguments.
- Response: a BamKey notification equal to our session UUID (or all zeros) means "response
  ready"; the app then reads BamKey until one complete blob arrives (7.128 s timeout). The
  BLE layer tells notifications from read results on that characteristic by that rule.
  `PASS:`/`PASS:ACK` carry space-separated values; `FAIL:0` unknown key, `FAIL:1` device
  timeout, `FAIL:2` error.
- Commands: `SNCG side`, `PSNS side value`, `PSNI`, `ACSP side preset 0`, `ACTS side
  actuator pos`, `ACTG side actuator`, `ACHA` (global halt), `UBLG`, `UBLS level timer`,
  `SYCG`. Hold uses the same 10-point incremental `ACTS` scheme, release sends `ACHA`.

## Unverified

These are implemented with the best-supported choice and need checking on the real bed
(the Diagnostics screen logs every frame for this).

1. **MCR side numbering.** BedRemote and the task brief say wire side 0 is the sleeper's
   left. ha-adjustable-bed (static) calls side 0 "right", and a sleepnumber-ble capture note
   says the user's "left → flat" was sent with side 1. The difference may just be viewpoint
   (lying in bed vs. standing at the foot). Default: side 0 = Left; Settings has "Swap left
   and right".
2. **Pump status byte order.** Live implementations read payload byte 1 as side 0's Sleep
   Number; ha-adjustable-bed maps byte 1 to side 1. App follows the live readings. If
   firmness numbers appear on the wrong side but movement is right, this is why.
3. **Motion stop sequence.** `MFHx110` is the app's STOP per the 5.4.11 analysis; foundation
   ForceIdle is from BedRemote's traces and is excluded by ha-adjustable-bed as unproven. The
   app sends both. Neither has been observed stopping this particular bed yet.
4. **Held-movement behaviour of func 17.** Whether the bed keeps moving to a func 17 target
   without continued-adjustment reads is unknown; the app sends them regardless and keeps
   targets within 10 points. BedRemote also sends a func 25 "motor start" `[01 01 FF]` before
   the target; that is not part of the 5.4.11 path and the app does not send it. If holds do
   not move the bed, this is the first thing to try.
5. **Shared foot side.** The foot is commanded on wire side 0 (sleepnumber-ble example).
   Untested whether side 1 also works or is required on this base.
6. **Bind addressing.** Target/echo = 0 and `sub` = the reply's target is live-tested by two
   implementations. ha-adjustable-bed puts the reply's payload bytes 8-9 in target/echo
   instead; sleepnumber-ble reports the bed accepts either.
7. **Foundation status flags.** Per-actuator flag bytes 9-12 (ha-adjustable-bed) versus 10-13
   (sleepnumber-ble observation). Movement is taken from byte 0 bit 0 OR bytes 9-12 bit 0.
8. **Presets on FlexFit 2.** ha-adjustable-bed's model table gives FF2 only Favorite, Flat,
   Zero G and Snore; sleepnumber-ble's FF2 table lists Read and TV as working. All six are
   shown.
9. **System status feature bits** (func 37 byte 3) are decoded differently by the two
   references (bit 8 = light vs. warming). Shown in Diagnostics only; nothing is gated on it.
10. **Manufacturer ID `0x534E`** for scan matching comes from the task brief; neither
    reference names the company ID.
11. **Fuzion as a whole** is implemented from ha-adjustable-bed's static analysis only (no
    hardware validation in any reference). Which Fuzion side moves a shared foot is taken
    from `SYCG`'s foot-actuator flags (left preferred).
12. **Fuzion urgent halt.** `ACHA` is written outside the request queue so STOP never waits;
    its "response ready" hint can be consumed by a command still in flight. Commands in
    flight during STOP are cancelled first, so in practice this only affects logging.
