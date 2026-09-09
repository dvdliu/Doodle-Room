package com.pictionary;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One Pictionary lobby/game. Owns the player list, lobby config, turn rotation,
 * word selection, drawing relay, chat/guess evaluation, hint scheduling, and scoring.
 *
 * Phase flow: LOBBY -&gt; PICKING_WORD -&gt; DRAWING -&gt; TURN_END -&gt; (next turn) ... -&gt; GAME_OVER.
 *
 * All state-mutating entry points are {@code synchronized} on the room instance so that
 * incoming WebSocket messages (handled on I/O threads) and scheduled timer callbacks
 * (handled on the shared scheduler) never race. Every scheduled callback also captures
 * a "turnToken" snapshot and re-checks it before acting, as a defense-in-depth against
 * a callback firing after its turn has already ended.
 */
public class GameRoom {

    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);

    private static final String[] PLAYER_COLORS = {
        "#7F77DD", "#1D9E75", "#D85A30", "#D4537E", "#378ADD", "#639922", "#BA7517", "#E24B4A"
    };

    private static final int WORD_PICK_SECONDS = 12;
    private static final int TURN_END_DELAY_MS = 4000;
    private static final int STROKE_HISTORY_LIMIT = 800;
    private static final Random RNG = new Random();

    public enum Phase { LOBBY, PICKING_WORD, DRAWING, TURN_END, GAME_OVER }

    private interface Filler { void fill(JsonObject o); }

    private final String roomCode;
    private final ScheduledExecutorService scheduler;

    private final Map<WebSocket, Player> players = new LinkedHashMap<>();
    private int colorIndex = 0;

    // ── Config (host-editable while in LOBBY) ──
    private int rounds = 3;
    private int drawSeconds = 80;
    private Set<String> categories = new LinkedHashSet<>(WordBank.ALL_CATEGORIES);
    private List<String> customWords = new ArrayList<>();

    // ── Game state ──
    private Phase phase = Phase.LOBBY;
    private List<String> turnOrder = new ArrayList<>();
    private int roundIndex = 0;
    private int turnIndex = -1;
    private String currentDrawerId;
    private String currentWord;
    private boolean[] revealed;
    private List<String> wordOptions = new ArrayList<>();
    private final Set<String> usedWords = new HashSet<>();
    private int secondsLeft;
    private int turnToken = 0;
    private final List<ScheduledFuture<?>> pendingFutures = new ArrayList<>();
    private final List<JsonObject> strokeHistory = new ArrayList<>();

    public GameRoom(String roomCode, ScheduledExecutorService scheduler) {
        this.roomCode = roomCode;
        this.scheduler = scheduler;
    }

    public String getRoomCode() { return roomCode; }

    public synchronized boolean isEmpty() { return players.isEmpty(); }

    public synchronized void shutdown() { cancelPendingFutures(); }

    // ── Connection lifecycle ──

    public synchronized Player addPlayer(WebSocket socket, String requestedName) {
        String name = (requestedName == null || requestedName.isBlank()) ? "Player" : requestedName.trim();
        if (name.length() > 20) name = name.substring(0, 20);

        String id = "p_" + UUID.randomUUID().toString().substring(0, 8);
        String color = PLAYER_COLORS[colorIndex % PLAYER_COLORS.length];
        colorIndex++;

        Player player = new Player(id, socket, name, color);
        boolean first = players.isEmpty();
        players.put(socket, player);
        if (first) player.setHost(true);

        // Tell the new connection which player id is theirs before anything else,
        // since ROOM_STATE's player list has no other way to say "this row is you".
        player.send(msg("YOU_ARE", o -> o.addProperty("id", player.getId())).toString());
        sendRoomStateTo(player);
        replayStrokesTo(player);
        broadcastExcept(player, msg("PLAYER_JOINED", o -> {
            o.addProperty("id", player.getId());
            o.addProperty("name", player.getName());
            o.addProperty("color", player.getColor());
        }));
        broadcastRoomState();
        log.info("Room {}: {} joined ({} total)", roomCode, player.getName(), players.size());
        return player;
    }

    public synchronized void removePlayer(WebSocket socket) {
        Player player = players.remove(socket);
        if (player == null) return;

        boolean wasHost = player.isHost();
        boolean wasDrawer = player.getId().equals(currentDrawerId);

        if (wasHost && !players.isEmpty()) {
            players.values().iterator().next().setHost(true);
        }

        broadcast(msg("PLAYER_LEFT", o -> {
            o.addProperty("id", player.getId());
            o.addProperty("name", player.getName());
        }));

        if (phase != Phase.LOBBY && phase != Phase.GAME_OVER) {
            if (players.size() < 2) {
                endGame();
            } else if (wasDrawer) {
                endTurn("drawer_left");
            }
        }

        broadcastRoomState();
        log.info("Room {}: {} left ({} remaining)", roomCode, player.getName(), players.size());
    }

    // ── Message dispatch ──

    public void handleMessage(WebSocket socket, JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        Player sender;
        synchronized (this) { sender = players.get(socket); }
        if (sender == null) return;

        switch (type) {
            case "LOBBY_UPDATE_CONFIG": handleUpdateConfig(sender, json); break;
            case "START_GAME": handleStartGame(sender); break;
            case "RESTART_GAME": handleRestartGame(sender); break;
            case "WORD_CHOICE": handleWordChoice(sender, json); break;
            case "DRAW": case "LINE": case "CLEAR": handleDrawEvent(sender, json); break;
            case "CHAT_GUESS": handleChatGuess(sender, json); break;
            case "LEAVE_ROOM": break; // handled by GameManager (socket bookkeeping)
            default: log.warn("Room {}: unknown message type '{}'", roomCode, type);
        }
    }

    // ── Lobby config & game start ──

    private synchronized void handleUpdateConfig(Player sender, JsonObject json) {
        if (!sender.isHost() || phase != Phase.LOBBY) {
            sendError(sender, "Only the host can change settings before the game starts.");
            return;
        }
        if (json.has("rounds")) rounds = clamp(json.get("rounds").getAsInt(), 1, 10);
        if (json.has("drawSeconds")) drawSeconds = clamp(json.get("drawSeconds").getAsInt(), 30, 240);
        if (json.has("categories")) {
            Set<String> cats = new LinkedHashSet<>();
            for (var el : json.getAsJsonArray("categories")) cats.add(el.getAsString());
            if (!cats.isEmpty()) categories = cats;
        }
        if (json.has("customWords")) {
            List<String> words = new ArrayList<>();
            for (var el : json.getAsJsonArray("customWords")) {
                String w = el.getAsString().trim();
                if (!w.isEmpty() && w.length() <= 24) words.add(w);
            }
            customWords = words;
        }
        broadcastRoomState();
    }

    private synchronized void handleStartGame(Player sender) {
        if (!sender.isHost()) { sendError(sender, "Only the host can start the game."); return; }
        if (phase != Phase.LOBBY) return;
        if (players.size() < 2) { sendError(sender, "Need at least 2 players to start."); return; }

        List<String> ids = new ArrayList<>();
        for (Player p : players.values()) ids.add(p.getId());
        Collections.shuffle(ids, RNG);
        turnOrder = ids;
        roundIndex = 0;
        turnIndex = -1;
        usedWords.clear();
        for (Player p : players.values()) {
            p.setScore(0);
            p.setGuessedThisTurn(false);
        }

        broadcast(msg("GAME_STARTED", null));
        advanceTurn();
    }

    private synchronized void handleRestartGame(Player sender) {
        if (!sender.isHost()) { sendError(sender, "Only the host can restart."); return; }
        if (phase != Phase.GAME_OVER) return;
        cancelPendingFutures();
        phase = Phase.LOBBY;
        for (Player p : players.values()) p.setScore(0);
        broadcastRoomState();
    }

    // ── Turn rotation ──

    private void advanceTurn() {
        cancelPendingFutures();
        turnToken++;

        int attempts = 0;
        while (attempts < turnOrder.size() * 2 + 1) {
            turnIndex++;
            if (turnIndex >= turnOrder.size()) {
                turnIndex = 0;
                roundIndex++;
            }
            if (roundIndex >= rounds) { endGame(); return; }

            Player candidate = findById(turnOrder.get(turnIndex));
            if (candidate != null && candidate.isConnected()) {
                beginPickingPhase(candidate);
                return;
            }
            attempts++;
        }
        endGame(); // nobody left to draw
    }

    private void beginPickingPhase(Player drawer) {
        phase = Phase.PICKING_WORD;
        currentDrawerId = drawer.getId();
        for (Player p : players.values()) p.setGuessedThisTurn(false);
        strokeHistory.clear();

        Set<String> usedLower = new HashSet<>();
        for (String w : usedWords) usedLower.add(w.toLowerCase());
        wordOptions = WordBank.pickOptions(3, categories, customWords, usedLower);
        if (wordOptions.isEmpty()) {
            // Safety net: e.g. the host disabled every category and gave no custom words.
            // Fall back to the full default bank so the turn can never get permanently stuck.
            wordOptions = WordBank.pickOptions(3, WordBank.ALL_CATEGORIES, Collections.emptyList(), Collections.emptySet());
        }

        drawer.send(msg("WORD_OPTIONS", o -> {
            JsonArray arr = new JsonArray();
            for (String w : wordOptions) arr.add(w);
            o.add("words", arr);
            o.addProperty("secondsToChoose", WORD_PICK_SECONDS);
        }).toString());

        broadcast(msg("TURN_START", o -> {
            o.addProperty("drawerId", drawer.getId());
            o.addProperty("drawerName", drawer.getName());
            o.addProperty("round", roundIndex + 1);
            o.addProperty("totalRounds", rounds);
        }));

        final int token = turnToken;
        pendingFutures.add(scheduler.schedule(() -> autoPickWord(token), WORD_PICK_SECONDS, TimeUnit.SECONDS));
    }

    private synchronized void autoPickWord(int token) {
        if (token != turnToken || phase != Phase.PICKING_WORD || wordOptions.isEmpty()) return;
        Player drawer = findById(currentDrawerId);
        if (drawer == null) return;
        onWordChosen(drawer, wordOptions.get(RNG.nextInt(wordOptions.size())));
    }

    private synchronized void handleWordChoice(Player sender, JsonObject json) {
        if (phase != Phase.PICKING_WORD || !sender.getId().equals(currentDrawerId)) return;
        String word = json.has("word") ? json.get("word").getAsString() : null;
        if (word == null || !wordOptions.contains(word)) return;
        onWordChosen(sender, word);
    }

    private void onWordChosen(Player drawer, String word) {
        cancelPendingFutures();
        turnToken++;

        currentWord = word;
        usedWords.add(word.toLowerCase());
        revealed = new boolean[word.length()];
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isLetter(word.charAt(i))) revealed[i] = true;
        }
        secondsLeft = drawSeconds;
        phase = Phase.DRAWING;

        drawer.send(msg("YOUR_WORD", o -> o.addProperty("word", word)).toString());
        broadcast(msg("WORD_SELECTED", o -> {
            o.addProperty("maskedWord", maskWord());
            o.addProperty("drawSeconds", drawSeconds);
        }));

        scheduleTicking();
        scheduleLetterReveals();
    }

    // ── Timers: countdown + progressive letter reveal ──

    private void scheduleTicking() {
        final int token = turnToken;
        pendingFutures.add(scheduler.scheduleAtFixedRate(() -> tick(token), 1, 1, TimeUnit.SECONDS));
    }

    private synchronized void tick(int token) {
        if (token != turnToken || phase != Phase.DRAWING) return;
        secondsLeft--;
        broadcast(msg("TIMER_TICK", o -> o.addProperty("secondsLeft", Math.max(secondsLeft, 0))));
        if (secondsLeft <= 0) endTurn("timeout");
    }

    private void scheduleLetterReveals() {
        int letterPositions = 0;
        for (char c : currentWord.toCharArray()) if (Character.isLetter(c)) letterPositions++;
        int reveals = Math.min(5, letterPositions / 2);
        if (reveals <= 0) return;

        final int token = turnToken;
        for (int i = 1; i <= reveals; i++) {
            long delaySeconds = Math.max(2, Math.round(drawSeconds * (i / (double) (reveals + 1))));
            pendingFutures.add(scheduler.schedule(() -> revealLetter(token), delaySeconds, TimeUnit.SECONDS));
        }
    }

    private synchronized void revealLetter(int token) {
        if (token != turnToken || phase != Phase.DRAWING) return;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < revealed.length; i++) {
            if (!revealed[i] && Character.isLetter(currentWord.charAt(i))) candidates.add(i);
        }
        if (candidates.isEmpty()) return;
        revealed[candidates.get(RNG.nextInt(candidates.size()))] = true;
        broadcast(msg("LETTER_REVEAL", o -> o.addProperty("maskedWord", maskWord())));
    }

    private String maskWord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < currentWord.length(); i++) {
            sb.append(revealed[i] ? currentWord.charAt(i) : '_');
        }
        return sb.toString();
    }

    // ── Drawing relay (drawer only) ──

    private synchronized void handleDrawEvent(Player sender, JsonObject json) {
        if (phase != Phase.DRAWING || !sender.getId().equals(currentDrawerId)) return;

        // Unlike the free-draw whiteboard, we don't need to stamp an authoritative color here:
        // only the current drawer's events are ever accepted (checked above), so there's no
        // identity to spoof — and overwriting it would break the drawer's own color picker for
        // everyone else watching, since the drawer's fixed identity color and their chosen
        // brush color are two different things.
        json.addProperty("userId", sender.getId());

        if ("CLEAR".equals(json.get("type").getAsString())) {
            strokeHistory.clear();
        } else {
            // Safe to store the parsed object as-is (not a copy): GameManager hands us a
            // freshly-parsed JsonObject per message that's never touched again afterward.
            if (strokeHistory.size() >= STROKE_HISTORY_LIMIT) strokeHistory.remove(0);
            strokeHistory.add(json);
        }
        broadcastExcept(sender, json);
    }

    private void replayStrokesTo(Player p) {
        for (JsonObject ev : strokeHistory) p.send(ev.toString());
    }

    // ── Chat & guessing ──

    private synchronized void handleChatGuess(Player sender, JsonObject json) {
        String text = json.has("text") ? json.get("text").getAsString().trim() : "";
        if (text.isEmpty() || text.length() > 200) return;

        boolean isActiveGuesser = phase == Phase.DRAWING
            && !sender.getId().equals(currentDrawerId)
            && !sender.isGuessedThisTurn();

        if (isActiveGuesser) {
            String normalizedGuess = normalize(text);
            String normalizedWord = normalize(currentWord);
            if (normalizedGuess.equals(normalizedWord)) {
                handleCorrectGuess(sender);
                return;
            }
            String kind = levenshtein(normalizedGuess, normalizedWord) == 1 ? "close" : "normal";
            broadcastChat(sender, text, kind, 0);
            return;
        }

        broadcastChat(sender, text, "normal", 0);
    }

    private void handleCorrectGuess(Player sender) {
        sender.setGuessedThisTurn(true);

        int priorCorrectGuessers = 0;
        for (Player p : players.values()) {
            if (!p.getId().equals(currentDrawerId) && p != sender && p.isGuessedThisTurn()) priorCorrectGuessers++;
        }
        // Decaying points by guess order, plus a small bonus for guessing with time to spare.
        int basePoints = Math.max(20, 100 - priorCorrectGuessers * 20);
        int speedBonus = Math.min(30, secondsLeft / 2);
        int points = basePoints + speedBonus;
        sender.setScore(sender.getScore() + points);

        Player drawer = findById(currentDrawerId);
        if (drawer != null) drawer.setScore(drawer.getScore() + 10); // drawer participation bonus

        broadcastChat(sender, null, "correct", points);
        broadcastScores();

        boolean allGuessed = true;
        for (Player p : players.values()) {
            if (!p.getId().equals(currentDrawerId) && !p.isGuessedThisTurn()) { allGuessed = false; break; }
        }
        if (allGuessed) endTurn("all_guessed");
    }

    private void broadcastChat(Player from, String text, String kind, int points) {
        broadcast(msg("CHAT_MESSAGE", o -> {
            o.addProperty("fromId", from.getId());
            o.addProperty("fromName", from.getName());
            o.addProperty("color", from.getColor());
            o.addProperty("kind", kind);
            if (text != null) o.addProperty("text", text);
            if (points > 0) o.addProperty("points", points);
        }));
    }

    private void broadcastScores() {
        broadcast(msg("SCORE_UPDATE", o -> {
            JsonArray arr = new JsonArray();
            for (Player p : players.values()) {
                JsonObject po = new JsonObject();
                po.addProperty("id", p.getId());
                po.addProperty("score", p.getScore());
                arr.add(po);
            }
            o.add("scores", arr);
        }));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** Standard Levenshtein edit distance (insert/delete/substitute), used for "close guess" hints. */
    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[a.length()][b.length()];
    }

    // ── Turn / game end ──

    private void endTurn(String reason) {
        cancelPendingFutures();
        turnToken++;
        phase = Phase.TURN_END;
        String word = currentWord;

        broadcast(msg("TURN_END", o -> {
            o.addProperty("word", word);
            o.addProperty("reason", reason);
        }));
        broadcastScores();

        final int token = turnToken;
        pendingFutures.add(scheduler.schedule(() -> {
            synchronized (GameRoom.this) {
                if (token != turnToken) return;
                advanceTurn();
            }
        }, TURN_END_DELAY_MS, TimeUnit.MILLISECONDS));
    }

    private void endGame() {
        cancelPendingFutures();
        turnToken++;
        phase = Phase.GAME_OVER;

        List<Player> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> b.getScore() - a.getScore());

        broadcast(msg("GAME_OVER", o -> {
            JsonArray arr = new JsonArray();
            for (Player p : ranked) {
                JsonObject po = new JsonObject();
                po.addProperty("id", p.getId());
                po.addProperty("name", p.getName());
                po.addProperty("score", p.getScore());
                arr.add(po);
            }
            o.add("scores", arr);
        }));
    }

    private void cancelPendingFutures() {
        for (ScheduledFuture<?> f : pendingFutures) f.cancel(false);
        pendingFutures.clear();
    }

    // ── State snapshot & broadcast helpers ──

    private JsonObject buildRoomState() {
        JsonArray playerArr = new JsonArray();
        for (Player p : players.values()) {
            JsonObject po = new JsonObject();
            po.addProperty("id", p.getId());
            po.addProperty("name", p.getName());
            po.addProperty("color", p.getColor());
            po.addProperty("isHost", p.isHost());
            po.addProperty("score", p.getScore());
            playerArr.add(po);
        }

        JsonObject cfg = new JsonObject();
        cfg.addProperty("rounds", rounds);
        cfg.addProperty("drawSeconds", drawSeconds);
        JsonArray catArr = new JsonArray();
        for (String c : categories) catArr.add(c);
        cfg.add("categories", catArr);
        JsonArray customArr = new JsonArray();
        for (String w : customWords) customArr.add(w);
        cfg.add("customWords", customArr);

        JsonObject o = new JsonObject();
        o.addProperty("type", "ROOM_STATE");
        o.addProperty("roomCode", roomCode);
        o.addProperty("phase", phase.name());
        o.add("players", playerArr);
        o.add("config", cfg);
        o.addProperty("round", roundIndex + 1);
        o.addProperty("totalRounds", rounds);
        if (phase == Phase.DRAWING || phase == Phase.TURN_END) {
            o.addProperty("drawerId", currentDrawerId);
            o.addProperty("secondsLeft", secondsLeft);
            if (phase == Phase.DRAWING) o.addProperty("maskedWord", maskWord());
        }
        return o;
    }

    private void broadcastRoomState() { broadcast(buildRoomState()); }
    private void sendRoomStateTo(Player p) { p.send(buildRoomState().toString()); }

    private JsonObject msg(String type, Filler filler) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (filler != null) filler.fill(o);
        return o;
    }

    private void broadcast(JsonObject payload) {
        String json = payload.toString();
        for (Player p : players.values()) p.send(json);
    }

    private void broadcastExcept(Player exclude, JsonObject payload) {
        String json = payload.toString();
        for (Player p : players.values()) {
            if (!p.getId().equals(exclude.getId())) p.send(json);
        }
    }

    private void sendError(Player p, String message) {
        p.send(msg("ERROR", o -> o.addProperty("message", message)).toString());
    }

    private Player findById(String id) {
        for (Player p : players.values()) if (p.getId().equals(id)) return p;
        return null;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
