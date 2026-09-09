# Doodle Room
A collaborate whiteboard made to draw with friends. This started as a free-draw whiteboard, and now also has a full skribbl.io-style **Pictionary game mode** — join a lobby with a room code, take turns drawing a chosen word while everyone else guesses in a chat, with close-guess hints and progressive letter reveals.

On load, `index.html` asks which mode you want: **Free Draw** (the original whiteboard) or **Play Pictionary** (`pictionary.html`).

## How it works

The project has two independent modes, each with its own frontend page and backend server/port:

| Mode | Frontend | Backend | Port |
| --- | --- | --- | --- |
| Free Draw | `index.html` | `WhiteboardServer` (`src/main/java/com/whiteboard/`) | 8080 |
| Pictionary | `pictionary.html` | `PictionaryServer` (`src/main/java/com/pictionary/`) | 8081 |

Both are pure HTML/CSS/JavaScript on the frontend (no build step) talking to a small Java WebSocket server that acts as the message switchboard/game authority.

```
Browser A ──┐
            ├──▶  WhiteboardServer (port 8080)   ──▶ broadcasts draw events to all peers
Browser B ──┘

Browser A ──┐
            ├──▶  PictionaryServer (port 8081)   ──▶ per-room lobby, turns, chat/guesses, scoring
Browser B ──┘
```

## Requirements

- **JDK 17+** — check with `java -version`
- **Maven 3.6+** — check with `mvn -v`
- A modern browser

## Quick start

### Free Draw

```bash
chmod +x start-server.sh
./start-server.sh
```

Then open `index.html`, pick **Free Draw** — and open it in a second tab or window to test the collaboration.

Other ways to run it:

```bash
./start-server.sh 9090           # custom port
SKIP_BUILD=1 ./start-server.sh   # skip rebuild, run existing jar
```

If you'd rather drive Maven yourself:

```bash
mvn clean package
java -jar target/collaborative-whiteboard-1.0-SNAPSHOT.jar
```

You should see `Whiteboard server started on ws://localhost:8080` in the terminal.

### Pictionary

Both modes share one Maven build, so building either one builds both:

```bash
chmod +x start-pictionary-server.sh
./start-pictionary-server.sh
```

Then open `pictionary.html` (or go through `index.html` → **Play Pictionary**) in 2+ tabs: one creates a room and shares the room code, the others join with it. The host configures rounds / draw time / word bank in the lobby, then starts the game.

```bash
./start-pictionary-server.sh 9091           # custom port
SKIP_BUILD=1 ./start-pictionary-server.sh   # skip rebuild, run existing jar
```

Or manually, since it's the same jar as the whiteboard server, just a different main class:

```bash
mvn clean package
java -cp target/collaborative-whiteboard-1.0-SNAPSHOT.jar com.pictionary.PictionaryServer
```

You should see `Pictionary server started on ws://localhost:8081` in the terminal.

## Project layout

```
whiteboard/
├── pom.xml                          # Maven config: dependencies, build settings (shared by both modes)
├── index.html                       # Free Draw frontend + mode-select landing screen
├── pictionary.html                  # Pictionary game frontend
├── start-server.sh                  # Build + run helper — Free Draw (port 8080)
├── start-pictionary-server.sh       # Build + run helper — Pictionary (port 8081)
├── src/main/java/com/whiteboard/
│   ├── WhiteboardServer.java        # Entry point + WebSocket lifecycle handlers
│   ├── RoomManager.java             # Broadcast logic, session map, history buffer
│   ├── UserSession.java             # Per-user state (id, name, assigned color)
│   └── DrawEvent.java               # Message data container (serialized as JSON)
├── src/main/java/com/pictionary/
│   ├── PictionaryServer.java        # Entry point + WebSocket lifecycle handlers
│   ├── GameManager.java             # Room-code registry; routes sockets to their GameRoom
│   ├── GameRoom.java                # Per-lobby state machine: turns, timers, guessing, scoring
│   ├── Player.java                  # Per-player state (id, name, color, score, host flag)
│   └── WordBank.java                # Built-in categorized word lists + word-option picker
└── target/                          # Maven build output (gitignored)
```

## Message protocol

### Free Draw (`WhiteboardServer`, port 8080)

Clients and the server exchange JSON messages over the WebSocket. Every message has a `type`:

| Type         | Sent by | Purpose                                  |
| ------------ | ------- | ---------------------------------------- |
| `USER_JOIN`  | client  | Initial handshake with username          |
| `USER_LEAVE` | server  | Announces a user disconnected            |
| `DRAW`       | both    | A single point in a freehand stroke      |
| `LINE`       | both    | A straight line segment                  |
| `CLEAR`      | both    | Clears the entire canvas                 |
| `CURSOR`     | both    | Broadcasts a user's cursor position      |

Example draw event:

```json
{
  "type": "DRAW",
  "userId": "user_a3f9",
  "username": "Alice",
  "color": "#7F77DD",
  "brushSize": 4,
  "x": 120.5,
  "y": 88.0,
  "startStroke": true
}
```

The server stamps each event with the user's server-assigned color before re-broadcasting it (so clients can't spoof colors). New connections receive a replay of the last 500 events so the board looks consistent on join.

### Pictionary (`PictionaryServer`, port 8081)

Every room runs a state machine: `LOBBY → PICKING_WORD → DRAWING → TURN_END → (next turn or) GAME_OVER`.

Client → server:

| Type                  | Sent by         | Purpose                                              |
| --------------------- | --------------- | ----------------------------------------------------- |
| `CREATE_ROOM`         | anyone          | Create a new lobby; sender becomes host              |
| `JOIN_ROOM`           | anyone          | Join an existing lobby by room code                  |
| `LEAVE_ROOM`          | anyone          | Leave the current lobby                               |
| `LOBBY_UPDATE_CONFIG` | host, in LOBBY  | Set rounds / draw time / word categories / custom words |
| `START_GAME`          | host, in LOBBY  | Shuffle turn order and begin round 1                  |
| `RESTART_GAME`        | host, after GAME_OVER | Reset scores and return to the lobby            |
| `WORD_CHOICE`         | current drawer  | Pick one of the 3 offered words                       |
| `DRAW` / `LINE` / `CLEAR` | current drawer | Same shape as the whiteboard's draw events, relayed to the room |
| `CHAT_GUESS`          | anyone          | A chat message; evaluated as a guess if you're an active guesser |

Server → client:

| Type            | Sent to        | Purpose                                                        |
| --------------- | -------------- | ---------------------------------------------------------------- |
| `YOU_ARE`       | one connection | Tells a newly-joined socket its own player id                   |
| `ROOM_STATE`    | one or all     | Full snapshot: room code, players, config, phase, current turn  |
| `PLAYER_JOINED` / `PLAYER_LEFT` | all | A player connected/disconnected                            |
| `GAME_STARTED`  | all            | The lobby just started a game                                    |
| `TURN_START`    | all            | Who's drawing this turn, and the round number                    |
| `WORD_OPTIONS`  | drawer only    | The 3 words to choose from                                        |
| `YOUR_WORD`     | drawer only    | The word they picked                                              |
| `WORD_SELECTED` | all            | The masked word (`"_ _ _"`) and draw time for this turn          |
| `TIMER_TICK`    | all            | Seconds remaining, once per second                                |
| `LETTER_REVEAL` | all            | An updated masked word with one more letter revealed              |
| `CHAT_MESSAGE`  | all            | `kind: "normal" \| "close" \| "correct"` — see below              |
| `SCORE_UPDATE`  | all            | Latest scores after a correct guess                               |
| `TURN_END`      | all            | Reveals the real word; reason is `timeout`, `all_guessed`, or `drawer_left` |
| `GAME_OVER`     | all            | Final scores, sorted highest first                                 |
| `ERROR`         | one connection | A rejected action (wrong turn, room not found, etc.)               |

**Guess evaluation** (`CHAT_MESSAGE.kind`): an exact case-insensitive match is `correct` (hides the raw guess, shows "🎉 X guessed it!" instead, and awards points); a guess exactly one Levenshtein edit away from the word is `close` (shown with a 🔥 "so close!" tag); everything else is plain `normal` chat.

**Scoring**: a correct guess earns `max(20, 100 - 20×priorCorrectGuessers) + min(30, secondsLeft/2)` points (faster and earlier guesses score more); the drawer earns a flat +10 per correct guesser.

**Letter reveals**: up to `min(5, letterCount/2)` random letters are revealed at evenly spaced points across the configured draw time.

## Dependencies

Pulled in by Maven from `pom.xml`:

- [`Java-WebSocket`](https://github.com/TooTallNate/Java-WebSocket) — WebSocket server implementation
- [`Gson`](https://github.com/google/gson) — JSON serialization
- `slf4j-simple` — logging

