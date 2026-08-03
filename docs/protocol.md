# Ember BLE protocol notes

These notes describe the Ember Ceramic Mug observed on August 3, 2026. The mug exposed the primary service `fc543622-236c-4c94-8fa9-944a3e5353fa` and Nordic's legacy DFU service.

All Ember characteristic UUIDs use `fc54XXXX-236c-4c94-8fa9-944a3e5353fa`.

| ID | Meaning | Properties | Encoding |
|---:|---|---|---|
| `0001` | Mug name | Read/write | UTF-8 text |
| `0002` | Current temperature | Read | UInt16 LE × 0.01 °C |
| `0003` | Target temperature | Read/write | UInt16 LE × 0.01 °C; `0` disables heating |
| `0004` | Unit | Read/write | `0` Celsius, `1` Fahrenheit |
| `0005` | Liquid level | Read | UInt8; observed `0` empty and `30` full |
| `0006` | Date/time/timezone | Read/write | Four-byte time plus signed timezone byte |
| `0007` | Battery | Read | Percent, charger flag, battery temperature |
| `0008` | Liquid state | Read | UInt8 state enum |
| `000c` | Firmware/hardware | Read | UInt16 LE firmware, UInt16 LE hardware |
| `000d` | Mug identity | Read | Device identifier/serial payload |
| `000e` | DSK | Read after encrypted bond | Setup/auth payload |
| `000f` | UDSK | Read/write after encrypted bond | Setup/auth payload |
| `0010` | Control register address | Read/write | Device-specific |
| `0011` | Control register data | Read/write | Device-specific |
| `0012` | Push event | Notify | One-byte event identifier |
| `0013` | Statistics | Notify | Undocumented telemetry |
| `0014` | LED color | Read/write | Four-byte RGBA-like payload |

Liquid states observed/documented:

- `0`: standby
- `1`: empty
- `2`: filling
- `3`: cold, temperature control off
- `4`: cooling
- `5`: heating
- `6`: at target
- `7`: warm, temperature control off

Push events cause the client to re-read state:

- `1`–`3`: battery/charger
- `4`: target temperature
- `5`: current temperature
- `6`: authorization missing
- `7`: liquid level
- `8`: liquid state

## Connection findings

- A stale/unpaired direct GATT attempt returned Android status `133`.
- Putting the mug in blue pairing mode allowed a successful direct connection.
- Android bonding used LE Legacy/Just Works and established an encrypted 16-byte key.
- DSK/UDSK reads failed before bonding and succeeded after bonding.
- The selected mug already contained a non-zero 20-byte UDSK, so it did not need to be reset or reinitialized.
- Multiple mugs advertise the same local name. The app must use an explicit picker/RSSI and then persist the bonded address instead of connecting to an arbitrary name match.
- A bonded mug can be connectable by its saved address without appearing in a short advertisement scan. Device selection must therefore merge bonded/saved devices with live scan results rather than treating an empty scan as proof that no mug exists.

## Prior research

The UUID/formats were cross-checked against these MIT-licensed reverse-engineering projects before being validated on the physical mug:

- [orlopau/ember-mug](https://github.com/orlopau/ember-mug)
- [sopelj/python-ember-mug](https://github.com/sopelj/python-ember-mug)
