# FitPro protocol — NordicTrack C1750 / ICON "Brainboard"

Reverse engineered 2026-07-28. **Essentially complete** — the protocol was recovered from ICON's
own .NET implementation, not guessed.

## Sources

| Source | Gave us |
|---|---|
| `android/usbprobe` (our Kotlin probe) | Transport: endpoints, packet size, request/response behaviour |
| `com.ifit.eru` v2.0.5 (jadx, `re/src/`) | Frame layout, checksum, device IDs, `Update` opcode |
| **`com.ifit.standalone` v2.6.81** (Xamarin, `re/cs/`) | **Command enum, all 99 fields, value encodings** |

The iFit app is Xamarin/.NET. Its assemblies live in `assemblies/assemblies.blob`, unpacked by
`re/extract_assemblies.py`, then decompiled with `ilspycmd` into `re/cs/`. **The .NET code is
completely unobfuscated** — real namespaces (`Sindarin.FitPro1.Bits`), real type and member names.

Machine-readable field table: `re/bitfields.json`.

## Transport — CONFIRMED on hardware

| | |
|---|---|
| VID / PID | `0x213C` / `0x0002` ("ICON Fitness / ICON Generic HID") |
| Interface | 0, HID class 3 |
| IN endpoint | `0x81`, interrupt, 64 bytes, interval 1 |
| OUT endpoint | `0x02`, interrupt, 64 bytes, interval 1 |
| Usage page | `0xFF00` vendor-defined, **no report IDs** — a raw 64-byte pipe |

Accessible to an ordinary Android app via `UsbManager` — **no root**. Verified: permission
granted, interface claimed. `com.ifit.eru` must be disabled first (only one holder).

iFit's own I/O (`Sindarin.Usb.Android.UsbConsoleConnection`): `DoBulkTransferWrite` /
`DoBulkTransferRead`. The board is **request/response — it sends nothing unsolicited**, which
matches `SubscribeCommand` / `CreateSubscribeCommands` in the app: you subscribe to fields, then
poll for updates.

## Frame format

**Request** (`r3/a.java` in eru):

```
[0]      device id
[1]      total length, including checksum
[2]      command id
[3..n-2] payload
[n-1]    checksum
```

**Response** (`EquipmentUtil.CleanResponse`) — note the extra status byte:

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

`Sindarin.FitPro1.Commands.Command`. Values match the opcode table independently recovered from
eru, which is a good cross-check.

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
`02 04 09 0F` (the "simplest" well-formed frame) is a brainboard reset — eru logs it as
*"Sent brainboard reset command"*.

## Device IDs

`com.ifit.shire.fitpro.equipment.dataobjects.Device`. Metrics are addressable as devices:

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

From `Sindarin.FitPro1.Bits.Converters`.

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
| 12 | `WorkoutMode` | Mode | writable | |
| 13 | `Calories` | Calories | read | |
| 16 | `ActualKph` | Speed | read | **what the belt is really doing** |
| 17 | `ActualIncline` | Grade | read | **actual incline** |
| 20 | `CurrentTime` | Int | read | elapsed |
| 27 / 28 | `MaxGrade` / `MinGrade` | Grade | read | **machine reports its own limits** |
| 30 / 31 | `MaxKph` / `MinKph` | Speed | read | **ditto** |
| 67 / 68 | `BeltTotalTime` / `BeltTotalMeters` | Int | read | lifetime totals |
| 96 | `StartRequested` | Bool | read | |
| 98 | `FanState` | FanState | writable | |

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
