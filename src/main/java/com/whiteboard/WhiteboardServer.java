package com.whiteboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Collaborative Whiteboard WebSocket server. Listens on ws://localhost:8080; clients
 * connect, send draw events as JSON, and receive broadcasts from peers. See
 * DrawEvent and RoomManager for the message protocol.
 */
public class WhiteboardServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardServer.class);

    private final RoomManager roomManager = new RoomManager();
    private final Gson gson = new Gson();

    public WhiteboardServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        // Defer full registration until the client sends USER_JOIN with a username.
        // Generate a temp ID now; it'll be confirmed on first message.
        socket.setAttachment(UUID.randomUUID().toString());
        log.info("New connection from {}", socket.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket socket, String raw) {
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String typeStr = json.get("type").getAsString();
            DrawEvent.Type type = DrawEvent.Type.valueOf(typeStr);

            if (type == DrawEvent.Type.USER_JOIN) {
                // First message — register the session
                String username = json.has("username")
                    ? json.get("username").getAsString()
                    : "Anonymous";
                String userId = socket.getAttachment();
                roomManager.onConnect(socket, userId, username);
            } else {
                // Regular draw/cursor/clear event
                DrawEvent event = gson.fromJson(raw, DrawEvent.class);
                roomManager.onDraw(socket, event);
            }

        } catch (Exception e) {
            log.warn("Failed to parse message from {}: {} — {}", socket.getRemoteSocketAddress(), raw, e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        roomManager.onDisconnect(socket);
    }

    @Override
    public void onError(WebSocket socket, Exception ex) {
        log.error("WebSocket error on {}: {}", socket != null ? socket.getRemoteSocketAddress() : "null", ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("Whiteboard server started on ws://localhost:{}", getPort());
        log.info("Open frontend/index.html in multiple browser tabs to collaborate.");
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        WhiteboardServer server = new WhiteboardServer(port);
        server.start();

        // Keep alive — Ctrl+C to stop
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutting down whiteboard server...");
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }
}