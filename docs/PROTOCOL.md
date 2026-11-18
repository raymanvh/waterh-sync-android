# WaterH bottle Bluetooth protocol

Worked out from the official WaterH Android app 3.5.1 (`com.waterh.ble.*`). Not
official documentation; treat as best understanding.

## GATT

| Role | Service | Characteristic |
|---|---|---|
| Commands (write, with response) | `0000ffe5-…` | `0000ffe9-…` |
| Replies (notify) | `0000ffe0-…` | `0000ffe4-…` |
| Firmware update | `00010203-0405-0607-0809-0a0b0c0d1912` | `…2b12` |

No bonding or encryption. Numbers are big-endian. The app enables notifications
by writing the notify characteristic's first descriptor.

## App's sync sequence

1. Connect, enable notifications.
2. `47540001ff` – request status. Boost reply starts `52 50 00 27`, then a run
   of fields laid out as `[length][tag][value…]` (length counts the tag byte):

   | Tag | Value |
   |---|---|
   | `02` | battery % |
   | `03` | bottle clock `yy mm dd hh mi ss` |
   | `04` | bottle's own total for today, mL (`be16`) |
   | `05` | daily goal stored on the bottle, mL (`be16`) |
   | `10` | firmware (`be16` → `a.b.c`) + 2 unknown bytes |
   | `17` | charging (1 or 2 = charging) |
   | `26` | reminder settings (6 bytes) |
   | `2a` | unknown |

   Vita reply starts `52 50 00 31` (different layout, not decoded).
3. `505400140305` + goal (4 hex) + `0703` + clock (`yy mm dd hh mi ss`) + `0726` + reminder (12 hex)
   – sets goal, clock and reminder. Reply starts `52 50 00 0f` and echoes the
   goal and reminder. WaterH Sync sends the bottle's own goal and reminder
   back so only the clock changes. Commands use the same `[length][tag][value]`
   layout after a 4-byte header.
4. `4754000106` – request stored drinks. Replies:
   - `52 50 .. .. .. 06 00` no records, `… 06 01` records follow.
   - First data packet `50 54 LL LL nn 06` + records; total records = `be16(LL LL) / 13`.
   - Follow-on packets `nn 06` + records.
   - Record (13 bytes): `yy mm dd hh mi ss`, mL `be16`, TDS `be16`, temperature,
     unknown, flag (0 = drink). Bytes 0–11 as uppercase hex are what the app
     uploads as `ble_data`.
5. App uploads drinks to `POST /account/{id}/log`.
6. Only if the upload succeeds or returns 409 (duplicate): `525000040306` +
   (records × 13 as 4 hex) – **clears** those records from the bottle. The
   bottle answers `52 50 00 03 02 06 00` (no records left). WaterH Sync, like
   the official app, sends this only after the upload is accepted.

Other commands seen: `505400040305`+goal (update goal), `505400080726`+reminder,
`505400040304`+amount (today's total for the bottle's display),
`50540003021c01` registration, `50540003021c05` clear offline data,
`5054000302f400` exit factory mode.

Live reports start `52 54`; byte 5 = 1 temperature, 2 battery, 23 charging,
8 volume, 40 TDS; value from byte 6.
