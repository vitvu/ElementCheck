# ElementCheck

On-device Android UI inspector. An accessibility service that captures the screen together with the full view hierarchy and serves a web inspector on port `8765`.

- Native Java, no third-party dependencies (~140 KB APK)
- Android 6.0 – 17 (API 23–37)
- Floating overlay: capture, collapse, close
- Web inspector: screenshot with element bounds, properties, hierarchy tree grouped by window, XPath search, copy XML
- Capture manager in the app and on the web: open, rename, download, delete
- Control mode over WebSocket: live screen, tap / long press / swipe, text input, system buttons

## Build

```bash
./gradlew assembleRelease
```

## Usage

1. Install the APK and enable **ElementCheck** in *Settings → Accessibility*.
   On Android 13+, sideloaded apps may need *App info → ⋮ → Allow restricted settings* first.
2. Open `http://<device-ip>:8765`, or over USB:

```bash
adb forward tcp:8765 tcp:8765
```

Android 10 and below require a one-time screen capture grant from the app.

## API

| Method | Endpoint | |
|---|---|---|
| GET | `/api/info` | Device info |
| GET | `/api/snapshot?scale=0.6&q=80` | Screenshot + hierarchy (JSON) |
| GET | `/api/screen.jpg` | Image of the last snapshot |
| POST | `/api/capture` | Capture and save on device |
| GET | `/api/captures` | List saved captures |
| GET | `/api/captures/{id}/tree.json` | Saved hierarchy |
| GET | `/api/captures/{id}/screen.jpg` | Saved screenshot |
| DELETE | `/api/captures/{id}` | Delete capture |
| POST | `/api/captures/{id}/meta.json` | Rename: `{"name":"..."}` |
| GET | `/api/captures/{id}/thumb.jpg` | Thumbnail |
| POST | `/api/gesture` | `{"type":"tap\|long\|swipe","x":0,"y":0,"x2":0,"y2":0,"duration":0}` |
| POST | `/api/global` | `{"action":"back\|home\|recents"}` |

### WebSocket `/ws`

Text frames are JSON requests with an optional `id`, answered by `{"t":"ok"|"err","id":...}`.

| Message | |
|---|---|
| `{"t":"snap","scale":0.6,"q":80}` | Binary image frame, then `{"t":"snap","data":{...}}` |
| `{"t":"gesture",...}` | Same fields as `/api/gesture` |
| `{"t":"global","action":"back"}` | `back`, `home`, `recents`, `notifications` |
| `{"t":"text","text":"..."}` / `{"t":"text","enter":true}` | Type into the focused field |
| `{"t":"stream","on":true,"scale":0.5}` | Screen stream; reply `{"t":"ack"}` after each frame |

Binary frames: first byte `1` = snapshot image, `2` = stream frame, followed by JPEG data.

The server has no authentication. Use it on trusted networks or via `adb forward`.
