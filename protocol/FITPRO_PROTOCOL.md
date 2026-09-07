# The FitPro protocol

An independent interoperability specification for the serial protocol spoken by the FitPro motor
control board ("brainboard") found in some NordicTrack and ProForm treadmills.

> Independent interoperability work, for compatibility with hardware the authors own. Assembled
> from USB traffic analysis and examination of publicly distributed software.
>
> Not affiliated with or endorsed by iFIT Health & Fitness, ICON Health & Fitness, NordicTrack or
> ProForm. Trademarks belong to their owners and are used here only to say which hardware this is.
> Nothing here is copied from anyone's documentation or source.

Anything marked **CONFIRMED** was checked against a real treadmill. Anything that wasn't says so.

Machine-readable field table: [`bitfields.json`](bitfields.json).

## Transport — CONFIRMED on hardware

| | |
|---|---|
| VID / PID | `0x213C` / `0x0002` ("ICON Fitness / ICON Generic HID") |
| Interface | 0, HID class 3 |
| IN endpoint | `0x81`, interrupt, 64 bytes, interval 1 |
| OUT endpoint | `0x02`, interrupt, 64 bytes, interval 1 |
| Usage page | `0xFF00` vendor-defined, **no report IDs** — a raw 64-byte pipe |

Reachable from an ordinary Android app via `UsbManager`, no root needed. Only one process can hold
the interface, so stop the existing console software first.

The board is request/response and sends nothing unsolicited. You subscribe to the fields you want,
then poll for values.

## Frame format

**Request:**

```
[0]      device id
[1]      total length, including checksum
[2]      command id
[3..n-2] payload
[n-1]    checksum
```

**Response** — note the extra status byte, which the request does not have:

```
[0]      device id
[1]      total length
[2]      command id
[3]      command status
[4..n-2] field data, in subscription order, each sized by its converter
[n-1]    checksum
```

**Checksum** — truncated sum of every byte before it:

```kotlin
fun checksum(b: ByteArray): Byte {
    var sum = 0
    for (i in 0 until (b[1].toInt() and 0xFF) - 1) sum += b[i]
    return sum.toByte()
}
```

## Commands — CONFIRMED

Command identifiers. Each of these was arrived at twice by separate routes, and they agree.

| Command | ID | | Command | ID |
|---|---|---|---|---|
| `None` | 0 | | `SupportedDevices` | 128 |
| `PortalDevListen` | 1 | | `DeviceInfo` | 129 |
| **`ReadWriteData`** | **2** | | `SystemInfo` | 130 |
| `Test` | 3 | | `TaskInfo` | 131 |
| `Connect` | 4 | | `VersionInfo` | 132 |
| `Disconnect` | 5 | | `ModeHistory` | 134 |
| `Calibrate` | 6 | | `SupportedCommands` | 136 |
| ⚠️ `Update` | 9 | | `ReadConfig` | 137 |
| ⚠️ `EnterBootloader` | 56 | | `VerifySecurity` | 144 |
| `SetTestingKey` | 112 | | `ProtocolData` | 145 |
| `SetTestingTach` | 113 | | `SpeedGradeLimit` | 146 |
| `Raw` | 255 | | `SerialNumber` | 149 |

**`ReadWriteData = 2` is the workhorse** — reads and writes field values.

⚠️ **`Update = 9` and `EnterBootloader = 56` are firmware operations. Never send them.**
`02 04 09 0F` — which is the simplest well-formed frame anybody experimenting is likely to
construct by accident — is a brainboard reset.

## Device IDs

Individual metrics are addressable as devices in their own right:

| Device | ID | | Device | ID |
|---|---|---|---|---|
| `NONE` | 0 | | `AUDIO` | 64 |
| `MULTIPLE_DEVICES` | 1 | | `SPEED` | 65 |
| **`MAIN`** | **2** | | `GRADE` | 66 |
| `PORTAL` | 3 | | `WATTS` | 67 |
| **`TREADMILL`** | **4** | | `PULSE` | 70 |
| `INCLINE_TRAINER` | 5 | | `KEY_PRESS` | 71 |
| `ELLIPTICAL` | 6 | | `FAN` | 74 |
| `ROWER` | 20 | | `SAFETY` | 75 |
| `HEART_RATE_MONITOR` | 55 | | `MODE` | 76 |
| | | | `DISTANCE` | 77 |
| | | | `WORKOUT_CONTROL` | 81 |

## Value encodings — CONFIRMED

**Speed** (`SpeedConverter`, 2 bytes) — **unsigned** UInt16 LE, hundredths:

```csharp
write: BitConverter.GetBytes((int)(kph * 100.0)).Take(2)
read:  BitConverter.ToUInt16(bytes, 0) / 100.0
```

**Grade** (`GradeConverter`, 2 bytes) — **signed** Int16 LE, hundredths of a percent:

```csharp
write: BitConverter.GetBytes((int)(pct * 100.0)).Take(2)
read:  (double)(short)BitConverter.ToUInt16(bytes, 0) / 100.0
```

⚠️ The read paths differ — speed unsigned, grade **signed**. Grade must be signed for the −3.0%
range; getting this wrong yields ~655% instead of −3%.

Other converters in the same namespace: `ByteConverter`, `ShortConverter`, `IntConverter`,
`BoolConverter`, `DoubleConverter`, `StringConverter`, `PulseConverter`, `CaloriesConverter`,
`BurnRateConverter`, `ModeConverter`, `FanStateConverter`, `GearConverter`, `KeyObjConverter`,
`ResistanceConverter`, `VerticalMeterConverter`, `AudioSourceConverter`, `IntervalConverter`.

## Fields (BitField)

99 fields — full table in `re/bitfields.json`. The ones that matter:

| ID | Field | Converter | Access | Notes |
|---|---|---|---|---|
| 0 | `Kph` | Speed | **writable** | **target speed** |
| 1 | `Grade` | Grade | **writable** | **target incline %** |
| 6 | `Distance` | Int | read | |
| 7 | `KeyObject` | KeyObj | read | **physical button presses** |
| 8 | `FanSpeed` | Byte | writable | |
| 10 | `Pulse` | Pulse | writable | heart rate |
| 12 | `WorkoutMode` | Mode | writable | state machine — see **Sleep** below |
| 13 | `Calories` | Calories | read | |
| 16 | `ActualKph` | Speed | read | **what the belt is really doing** |
| 17 | `ActualIncline` | Grade | read | **actual incline** |
| 20 | `CurrentTime` | Int | read | elapsed |
| 27 / 28 | `MaxGrade` / `MinGrade` | Grade | read | **machine reports its own limits** |
| 30 / 31 | `MaxKph` / `MinKph` | Speed | read | **ditto** |
| 34 | `IdleTimeout` | Short | writable | seconds; **reads 120 on this machine** |
| 67 / 68 | `BeltTotalTime` / `BeltTotalMeters` | Int | read | lifetime totals |
| 95 | `IdleModeLockout` | Bool | writable | **reads 0 on this machine** |
| 96 | `StartRequested` | Bool | read | |
| 98 | `FanState` | FanState | writable | |
| 107 | `SleepTimerState` | Bool | writable | **not supported on this machine** |

Target vs actual is a real distinction: write `Kph` (0), read `ActualKph` (16) to see the belt
respond. Query fields 27/28/30/31 at startup rather than hardcoding limits — but still clamp
independently (guide says 0–19.0 km/h, −3.0% to +12.0%).

## `ReadWriteData` payload — CONFIRMED

From `ReadWriteDataCmd.RequestContentBytes()` and `CommandBase.GetRequestBytes()`.

Content is **two sections, write first then read**:

```
content = writeSection ++ readSection

section(fields):
    if empty:                       one byte 0x00
    else:
        numBytes = (maxFieldId / 8) + 1
        emit numBytes
        for i in 0 .. numBytes-1:
            emit bitmask of fields where (fieldId / 8 == i)     // bit = 1 << (id % 8)
        if this is the write section:
            emit each field's encoded value, ascending field id
```

Outer frame (`CommandBase`): `Length = contentLength + 4`, i.e. device + length + command +
content + checksum. `MaxMsgLength = 64`.

**Response parsing** (`SetResponseBytes`): skip the first **4** bytes (device, length, command,
status), then read each subscribed field in **ascending field-id order**, each consuming exactly
`converter.Size` bytes.

Ordering matters in both directions: `ReadComms` and `WriteComms` are sorted ascending by field
id, and writes are filtered to non-read-only fields.

Timing: `ReadDelayMs = 80`, `ResponseTimeoutMs = 1000`.

### Worked example — read ActualKph (16) + ActualIncline (17)

```
write section: 00
read section:  03 00 00 03      numBytes=3; byte2 bits 0,1 → fields 16,17
content:       00 03 00 00 03   (5 bytes)
length:        5 + 4 = 9
frame:         04 09 02 00 03 00 00 03 15
checksum:      (04+09+02+00+03+00+00+03) & 0xFF = 0x15
```

Response: `[dev][len][02][status][kph:2][grade:2][checksum]` — speed unsigned, grade signed.

## VALIDATED ON HARDWARE — 2026-07-28

The spike (`android/stride`) built a frame from this spec and the board answered:

```
sent:  04 09 02 00 03 00 00 01 13      read ActualKph from device 4
reply: 04 07 02 02 00 00 0F
       │  │  │  │  └──┴─ ActualKph = 0.00 km/h (belt stopped)
       │  │  │  └─ status
       │  │  └─ ReadWriteData
       │  └─ length 7
       └─ device TREADMILL
checksum 0x0F = (04+07+02+02+00+00) & 0xFF ✓
```

Limits read back from the machine: **speed 1.6–19.0 km/h, grade −3.0 to +12.0 %** — matching the
published spec for this model, which independently confirms the decoding. Note `MinKph = 1.6`,
not 0: the belt has a minimum moving speed.

**Resolved:** device id for treadmill fields is **`TREADMILL` (4)**, not `MAIN` (2). No `Connect`
(4) handshake was needed — `ReadWriteData` answers immediately on a freshly claimed interface.

## Writes — VALIDATED, with an interlock

Writes work, but **the belt is gated on console state**:

> A `KPH` (field 0) write is accepted and silently ignored while `WorkoutMode` (field 12) is
> `Idle` (1). Write `WorkoutMode = Running` (2) first, then `KPH`, and the belt spins up after a
> short ramp.

`GRADE` (field 1) has **no** such interlock. That asymmetry is how the gate was found: incline
responded, the belt didn't.

Verified end-to-end on hardware: `WorkoutMode=Running` → `Kph=1.6` → belt moves →
`Kph=0` + `WorkoutMode=Idle` → belt stops.

Practical consequence for any implementation: **track console state and surface it.** A speed
write that vanishes without error is otherwise indistinguishable from a broken transport — read
field 12 alongside the telemetry so the failure mode is visible.

## Security — CONFIRMED on hardware, 2026-08-07

**The board locks itself, and a locked board refuses everything — reads included.** This is the
one thing most likely to make an otherwise correct implementation work for months and then stop,
so it is worth reading before anything else here.

A locked board answers every `ReadWriteData` with a five-byte frame carrying **status 8,
`SecurityBlock`**:

```
04 05 02 08 13        device, length 5, ReadWriteData, SecurityBlock, checksum
```

That status is **not a failure, and has nothing to do with the safety key.** It means
*authenticate*. ICON's own console treats it as routine housekeeping: on `SecurityBlock` it logs
"Unlocking again" and re-runs its unlock, and it unlocks again on any transition into
`ConsoleState.Locked`.

### The unlock

Send `VerifySecurity` (144) with a 36-byte payload — a 32-byte hash, then
`8 × masterLibraryVersion` as a little-endian `int32`. The reply's first data byte is an unlock
key; status `Done` (2) means unlocked.

The hash is built from numbers the board will tell you. Data begins at offset 4 in every response,
after device / length / command / status, and every multi-byte value is little-endian:

| Value | Command | Where |
|---|---|---|
| `softwareVersion` | `DeviceInfo` (129), no content | byte 4 |
| `serialNumber` | `DeviceInfo` (129) | `uint32` at 6 |
| `model` | `SystemInfo` (130), content `00 00` | `uint32` at 7 |
| `partNumber` | `SystemInfo` (130) | `uint32` at 11 |
| `masterLibraryVersion` | `VersionInfo` (132), content `00 00` | byte 4 |

```
hash[32]
for b in 0..31:
    hash[b] = (b + 1) & 0xFF
    if (serialNumber >> b) & 1:
        p = b < 16 ? ((partNumber << 16) | (partNumber >>> 16)) >>> b
                   : partNumber >>> b
        hash[b] ^= p & 0xFF
    else:
        hash[b] ^= (hash[b] * (b + model)) & 0xFF
```

All arithmetic is byte-truncating; the intermediate values overflow deliberately.

Two details that are easy to miss:

- **Only unlock above software version 75.** Below that the board has no security to satisfy and
  does not support the command.
- One production run reports a part number that does not match its own hash. ICON's code carries
  the fix-up verbatim and so should yours: if `partNumber == 370357` and `model == 39915`, use
  `374677`.

### Why this matters more than it looks

Unlocking is not first-run setup. **The board re-locks on its own, across power cycles and idle
days.** An implementation that unlocks once will appear to work for as long as some earlier
console's unlock is still current, and will then present as hardware failure: a UI that can
navigate but cannot act, a physical START that beeps and does nothing, a belt that will not move.
Because reads are refused too, telemetry stops — and a display driven by successful frames freezes
mid-render, so every button looks dead as well.

**Unlock at startup before reading anything, and again on every `SecurityBlock`.** Rate-limit the
retry rather than answering at poll frequency.

### One frame, one refusal

Related, and the same class of surprise: **the board rejects the entire frame if any single field
in it is invalid.** `WorkoutMode` is the usual culprit, being a state machine — a transition it
will not accept from its current state fails the `KPH` write travelling alongside it, with no
indication which field was at fault. Send mode changes in their own frame. That also gets the
ordering right for the interlock above: mode first, then the speed it enables.

## `WorkoutMode` (field 12) — the values

The state machine the belt interlock and the safety key both live in. Taken from ICON's own
enumeration; the ones marked have been seen on hardware.

| | | | | | |
|---|---|---|---|---|---|
| 0 | `Unknown` | | 9 | `Demo` | |
| 1 | `Idle` | ✓ | 10 | `WarmUp` | |
| 2 | `Running` | ✓ | 11 | `CoolDown` | |
| 3 | `Pause` | ✓ | **12** | **`Sleep`** | see below |
| 4 | `Results` | | 13 | `Resume` | |
| 5 | `Debug` | | 14 | `Locked` | |
| 6 | `Log` | | 20 | `PauseOverride` | |
| 7 | `Maintenance` | | | | |
| 8 | `Dmk` | ✓ | | | |

`Dmk` (8) is the safety key being out, and it is reported in bursts rather than held — latch it
or you will miss it.

Note the asymmetry in ICON's own mapping: it *writes* 12 to put a machine to sleep, but maps a
12 it *reads* back to "unknown". Do not copy that. 12 is the one value an implementation most
needs to recognise, for the reason below.

## Sleep — the machine has its own, and it is not the console's

**The board goes dormant on a timer of its own, and a dormant board is not a broken one.** This
is the second thing (after the lock) most likely to make a working implementation look like dead
hardware, and it presents almost identically: the Android side is fine, the display draws, every
button answers, and the belt will not move for anything.

Three writable fields describe it. Read back from the machine here (NordicTrack, FitPro1,
master library version 84) on 2026-09-03:

| Field | ID | Read back |
|---|---|---|
| `IdleTimeout` | 34 | `120` — seconds |
| `IdleModeLockout` | 95 | `0` |
| `SleepTimerState` | 107 | **unsupported** — the board answers a 5-byte frame with no data |

Read them one per frame. The board rejects a whole frame over a single field it does not
support, so asking for all three together gets you nothing rather than the two that exist.

ICON's console sets `IdleModeLockout` from the machine's own shape at startup —
`!supportsRequireStartRequested || !isBeltBasedMachine`, which is `false` on a treadmill with a
physical START — and sets it `true` again whenever an aerobic machine leaves a workout state.

### Waking it

Write `WorkoutMode = Idle` (1), **in a frame of its own**. That is the transition ICON's console
makes to bring a board out of any non-idle state, and idle is where the belt interlock expects to
start from — so the same write both wakes the machine and leaves it ready.

Two failure shapes, and they want different answers:

* **The board answers, reporting mode 12.** It is asleep and saying so. The mode write is all
  that is needed.
* **The board answers nothing at all.** It may be asleep at a deeper level, or it may have left
  the USB bus — that has been seen, and nothing but a fresh `openDevice` fixes it. Look for the
  device again before concluding anything, and do it on a timer rather than at poll rate.

Worth separating the two halves in any console that has a screen of its own: waking a panel is
one gesture, and waking a machine spins a motor controller up in whatever room it is standing in.
ICON's own console asked before doing the second, which is the right instinct.

## Remaining unknowns

1. Whether FitPro**2** framing differs from FitPro**1**; the console answers FitPro1 framing, so
   this may not matter.
2. `KeyObject` (7, 14 bytes) decoding — the physical-button payload structure.
3. Whether `StartRequested` (96) / `RequireStartRequested` (108) gate anything we haven't hit —
   not needed for the verified sequence above.

## Safety

- The **physical safety key** is the stop of record. `SAFETY` (75) is readable — surface it, and
  refuse to write speed when the key is out.
- Clamp every write to the machine's reported limits, and rate-limit changes.
- Never send `Update` (9) or `EnterBootloader` (56).
