# FlipTunes message protocol

FlipTunes uses the Flipper Zero's standard Bluetooth **serial service**.

| | UUID |
|---|---|
| Service | `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000` |
| RX - phone writes | `19ed82ae-ed21-4c9d-4145-228e62fe0000` |
| TX - Flipper indicates | `19ed82ae-ed21-4c9d-4145-228e61fe0000` |

Every message is one line of ASCII text ending in `\n`. Unknown lines are ignored,
so new message types can be added without breaking older versions.

## Phone -> Flipper

| Line | Meaning |
|---|---|
| `T:<title>` | Song title, max 60 characters |
| `A:<artist>` | Artist, max 40 characters |
| `P:<player>` | Player app name, max 20 characters |
| `S:<playing>,<position>,<duration>` | `1` playing / `0` paused, position and duration in seconds |
| `I:<base64>` | Album cover: 288 bytes = 48x48 pixels, 1 bit each, XBM order (lowest bit = leftmost pixel). `I:-` = no cover |
| `N` | Nothing is playing |
| `M:<period>,<next>` | Tempo lock: a beat every `period` ms, the next one in `next` ms |
| `B` | A single kick, sent only when no steady tempo is found |

Song info is sent when it changes and every 5 seconds while playing (to correct the progress bar).
Everything, including the cover, is resent every 30 seconds and when the Flipper asks (`C:RA`).
Characters outside basic ASCII are transliterated (`é` -> `e`) or replaced with `?`.

## Flipper -> Phone

| Line | Meaning |
|---|---|
| `C:PP` | Play / pause |
| `C:NX` | Next track |
| `C:PV` | Previous track |
| `C:VU` / `C:VD` | Volume up / down |
| `C:SK:<n>` | Seek by `n` seconds (negative = back) |
| `C:RA` | Resend everything (sent when the link starts) |
| `C:BT:1` / `C:BT:0` | Start / stop beat messages (LED pulse setting) |
| `C:BS:<1-3>` | Beat sensitivity: 1 low, 2 medium, 3 high |
