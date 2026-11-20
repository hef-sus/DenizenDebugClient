# Denizen Debug (dzdebug)

A client-side Fabric mod that shows **Denizen script errors from your server in-game**, so you don't have to
watch the server console while you work on scripts.

## Features

- **Error box**: a chat-style box in the bottom-right corner that lists the latest errors (newest at the bottom).
  It appears when an error arrives, fades out after about 10 seconds, and leaves a small badge while errors are
  unread. Open chat (`T`) to see it at full size, and click it to open the console.
- **Console screen**: a full-screen console with
  - a script sidebar (click a script to filter its errors),
  - smooth scrolling and a "Latest" jump button,
  - click-to-select lines, and a flash on fresh errors,
  - **Pause / Resume**, **Dedup** (merges identical errors into one line with a count), **Copy** (all or the
    selected lines) and **Clear**.
- **Unseen tab**: errors the server stored while you are not online.
- **Alert sounds**: choose the sound and volume, and decide whether repeated errors make a sound.
- **Server log button**: asks the server to upload its log and gives you the link (see below).


## Controls

| Action | How |
| --- | --- |
| Open the console | `F4` (rebindable in Controls), or `/dzerr` |
| Open the console from the error box | Open chat and click the box |
| Clear the console | `Clear` button, or `/dzerr clear` |
| Settings | `Settings` button in the console, or Mod Menu's configure button |

## Requirements
- **Server**: Denizen reflect, DenizenM, PacketEvents
- **Client**: 26.3


## Console buttons

- **Pause / Resume**: freezes the view while you read. New errors are counted on the button.
- **Dedup**: on by default.
- **Copy**: copies the selected errors, or all of them if none are selected.
- **Reload**: runs `/ex reload` on the server.
- **Clear log** (Unseen tab): runs `/cleardenizenlogs` on the server and empties the list.
- **Server log**: runs `/serverlastestlog` on the server. When the server answers with a chat message containing
  `Log uploaded: <url>`, the link is captured (the message is hidden from chat) and an **Open log** button appears.
  The link is copied to your clipboard and opened through the game's link confirmation.

## Settings

Saved in `config/dzdebug.json`:

| Option | Default | Description |
| --- | --- | --- |
| `soundEnabled` | `true` | Play a sound when an error arrives |
| `soundIndex` | `0` | Which sound (Bell, Pling, Bit, Chime, Bass, Villager, Anvil) |
| `volume` | `0.75` | Sound volume (0.05 to 1.0) |
| `soundOnRepeats` | `false` | Also play the sound for errors repeated within a minute |

## Requirements

- Fabric Loader and Fabric API
- [Mod Menu](https://modrinth.com/mod/modmenu) (optional, adds the configure button)

## Server side

The mod only displays what the server sends, so your server needs a custom Denizen script, which can be found along side the mod

### Custom packets (preferred)

All packets use the `dzdebug` namespace.

| Channel | Direction | Contents |
| --- | --- | --- |
| `dzdebug:error` | server to client | string `script` (max 1024), string `line` (max 64), string `message` (max 65535), VarInt `count` |
| `dzdebug:unseen` | server to client | boolean `reset`, then a list (max 64) of `{ string script, string line, string message, VarInt count, long lastSeenMillis }` |
| `dzdebug:hello` | client to server | empty; sent when the player joins so the server knows to send its unseen log |

The unseen list may be split over several packets. The first packet has `reset = true`.

### Chat fallback

If you can't send custom packets, send a chat message in this format. The client captures it and hides it, so
it never shows in chat:

```
[[DZERR]]script[[|]]line[[|]]message
```

### Server log link

For the **Server log** button, add a server command named `serverlastestlog` that uploads the log (for example
to [mclo.gs](https://mclo.gs)) and then sends the player a chat message containing:

```
Log uploaded: https://mclo.gs/xxxxxxx
```

### Other server commands used

- `/ex reload` (the Reload button)
- `/cleardenizenlogs` (the Clear log button)

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. Mod Menu is only needed at compile time and the mod runs without it.

## Troubleshooting

- **Nothing shows up**: check that the server is actually sending `dzdebug:error` packets or the chat fallback
  format, and that the mod is installed on your client.
- **Unseen tab is empty**: the server must send `dzdebug:unseen` after receiving `dzdebug:hello`.
- **Server log button does nothing**: check that your server has a `serverlastestlog` command and that you have
  permission to run it. The status line at the bottom of the console shows what the client is waiting for.
