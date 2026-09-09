package com.pictionary;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Top-level owner of every active Pictionary lobby. Creates/looks up {@link GameRoom}s
 * by room code and routes each socket's messages to the room it's currently in.
 */
public class GameManager {

    private static final Logger log = LoggerFactory.getLogger(GameManager.class);

    // Excludes visually ambiguous characters (0/O, 1/I).
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 5;
    private static final Random RNG = new Random();

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<WebSocket, GameRoom> socketToRoom = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public void handleMessage(WebSocket socket, String raw) {
        JsonObject json;
        try {
            json = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            log.warn("Bad JSON from {}: {}", socket.getRemoteSocketAddress(), raw);
            return;
        }
        String type = json.has("type") ? json.get("type").getAsString() : "";

        switch (type) {
            case "CREATE_ROOM": createRoom(socket, json); break;
            case "JOIN_ROOM": joinRoom(socket, json); break;
            case "LEAVE_ROOM": onDisconnect(socket); break;
            default:
                GameRoom room = socketToRoom.get(socket);
                if (room != null) {
                    room.handleMessage(socket, json);
                } else {
                    sendDirectError(socket, "You're not in a room yet.");
                }
        }
    }

    private void createRoom(WebSocket socket, JsonObject json) {
        String code = generateRoomCode();
        GameRoom room = new GameRoom(code, scheduler);
        rooms.put(code, room);
        socketToRoom.put(socket, room);

        String name = json.has("name") ? json.get("name").getAsString() : "Player";
        room.addPlayer(socket, name);
        log.info("Room {} created", code);
    }

    private void joinRoom(WebSocket socket, JsonObject json) {
        String code = json.has("roomCode") ? json.get("roomCode").getAsString().trim().toUpperCase() : "";
        GameRoom room = rooms.get(code);
        if (room == null) {
            sendDirectError(socket, "Room not found: " + code);
            return;
        }
        String name = json.has("name") ? json.get("name").getAsString() : "Player";
        socketToRoom.put(socket, room);
        room.addPlayer(socket, name);
    }

    /** Called on explicit LEAVE_ROOM as well as socket close. */
    public void onDisconnect(WebSocket socket) {
        GameRoom room = socketToRoom.remove(socket);
        if (room == null) return;

        room.removePlayer(socket);
        if (room.isEmpty()) {
            rooms.remove(room.getRoomCode());
            room.shutdown();
            log.info("Room {} closed (empty)", room.getRoomCode());
        }
    }

    private String generateRoomCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private void sendDirectError(WebSocket socket, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ERROR");
        o.addProperty("message", message);
        if (socket.isOpen()) socket.send(o.toString());
    }
}
