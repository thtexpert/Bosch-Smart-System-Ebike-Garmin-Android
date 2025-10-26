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

### Odometer / total meters driven

| Message                      | Odometer (m)         |
|------------------------------|----------------------|
| `30-07-98-18-08-B3-A1-AA-06` |  13275315  (13275km) |

### Battery State Of Charge

| Message                      | SoC (%)    |
|------------------------------|------------|
| `30-04-80-BC-08-2F`          |  47 %      |

'80-88' and '80CA' also seem to report battery but value is 2% off from KIOX displayed value

### Total Distance / Odometer

| Message                      | Odometer (m) |
|------------------------------|--------------|
| `30-07-98-18-08-F2-CF-AA-06` | 13281266 m   |

### Trip Distance per Assistmode

Series of varint data
varint are concatenated:

| Message                                        | TripDistPerMode (m) | AssistMode |
|------------------------------------------------|---------------------|------------|
| `30-0D-A2-52-0A-09-B2-08-A0-13-F5-10-E4-02-00' |                     |            |
| `                  B2-08                     ' | [0] = 1074 m        |  0  OFF    |
| `                        A0-13               ' | [1] = 2464 m        |  1  ECO    |
| `                              F5-10         ' | [2] = 2165 m        |  2  TOUR+  |
| `                                    E4-02   ' | [3] = 356 m         |  3  SPORT  |
| `                                          00' | [4] = 0 m           |  4  TURBO  |

### Total delivered Battery Energy

to be verified!

| Message                      | Energy (Wh)  |
|------------------------------|--------------|
| `30-06-80-9C-08-E9-EC-03`    | 63081 (Wh)   |


### Total distance per Assistmode

On '10-8C' asome kond of structure is sent, that contains the kilometers total driven in each assist modes.
For every mode a indidivual notification is received. Unfotrunately no pattern found that identifies the mode the data is delivered for.
 
to be verified!

| Message                                                  |     meter    |
|----------------------------------------------------------|--------------|
| '30-10-10-8C-C0-80-55-0A-09-08-9A-D4-B5-02-10-C1-89-02   |              |
| '                              9A-D4-B5-02               |              |
| '30-0F-10-8C-C0-80-5F-0A-08-08-A6-97-C4-01-10-E9-68      |              |
| '                              A6-97-C4-01               |              |
| '30-0B-10-8C-C0-80-5D-0A-04-08-92-DB-03                  |              |
| '                              92-DB-03                  |              |
| '30-10-10-8C-C0-80-5E-0A-09-08-91-AA-F3-01-10-C7-E6-01   |              |
| '                              91-AA-F3                  |              |

30,DistPerMode,192,946063,'300E108CC080530A07088FDF3910B50B
30,DistPerMode,192,5073434,'3010108CC080550A09089AD4B50210C18902
30,DistPerMode,192,3214246,'300F108CC0805F0A0808A697C40110E968
30,DistPerMode,192,60818,'300B108CC0805D0A040892DB03
30,DistPerMode,192,3986705,'3010108CC0805E0A090891AAF30110C7E601


# How I worked this out...

1. Logged data using [nrf Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp)
2. Python script to strip out data, keeping time and hex codes
3. Pasted into Excel
4. Sorted
5. Looked for patterns
6. Matched up with Strava

<img src="images\decodingBLE_excel.png" alt="drawing" width="800"/>
