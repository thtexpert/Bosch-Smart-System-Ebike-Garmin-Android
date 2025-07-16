# BLE Data Transmission Format

## Overview

BLE data is broadcast as a sequence of bytes (8 bits), typically represented using hexadecimal notation. Each byte consists of two hexadecimal characters (each 4 bits). For example: `5F`.

## Sample Message

```
30-05-98-2D-08-FC-01
```

### Breakdown

| Byte(s)   | Meaning                              |
|-----------|---------------------------------------|
| `30`      | Start of message                      |
| `05`      | Total message length                  |
| `98-2D`   | Data identifier (ID)                  |
| `08`      | Message type (`08` = varint?)         |
| `FC-01`   | Data encoded as a varint              |

## Varint Encoding

- Variable-length integers (varints) use the **most significant bit** (MSB) to indicate continuation:
  - If MSB is `1`, another byte follows.
  - If MSB is `0`, this is the last byte of the varint.
- To decode:
  - Strip the MSB from each byte before conversion.
  - Concatenate remaining bits and convert to decimal.

**Example:**

```
FC-01
```

- `0xFC & 0x7F = 0x7C`, `0x01` remains as-is  
- Combine as little endian: `0x01 0x7C` → decimal `252`

---

## Message Types & Examples

### Cadence (divide by 2)

| Message                  | Cadence (rpm) |
|--------------------------|----------------|
| `30-02-98-5A`            | 0              |
| `30-04-98-5A-08-56`      | 86 → 43 rpm    |

### Human Power

| Message                  | Power (watts) |
|--------------------------|----------------|
| `30-04-98-5B-08-4F`      | 79 W           |
| `30-05-98-5B-08-B2-04`   | 562 W          |
| `30-02-98-5B`            | 0 W            |

### Motor Power

| Message                  | Power (watts) |
|--------------------------|----------------|
| `30-04-98-5D-08-5F`      | 95 W           |
| `30-05-98-5D-08-F6-01`   | 246 W          |
| `30-02-98-5D`            | 0 W            |

### Speed

| Message                  | Speed (km/h)  |
|--------------------------|----------------|
| `30-05-98-2D-08-FC-01`   | 252 → 25.2 km/h |

### Battery Percentage

| Message                  | Battery (%)   |
|--------------------------|----------------|
| `30-04-80-88-08-59`      | 89%            |

### Assist Mode

| Message                  | Mode         |
|--------------------------|---------------|
| `30-04-98-09-08-04`      | Mode 4 (Turbo) |
| `30-02-98-09`            | Mode 0 (Off)   |

### Miscellaneous / Frequent Message

| Message                          | Notes                    |
|----------------------------------|---------------------------|
| `30-07-98-08-08-FE-0A-10-01`     | `10-01` appears constant  I think 10 is a data type and 01 is the data here|

### Possibly Motor Torque?

| Message                  | Torque (Nm)             |
|--------------------------|--------------------------|
| `30-04-98-15-08-XX`      | Divide `XX` by 200       |


# How I worked this out...

1. Logged data using [nrf Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp)
2. Python script to strip out data, keeping time and hex codes
3. Pasted into Excel
4. Sorted
5. Looked for patterns
6. Matched up with Strava

<img src="images\decodingBLE_excel.png" alt="drawing" width="800"/>
