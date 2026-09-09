package com.pictionary;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Pictionary Game WebSocket Server
 *
 * Listens on ws://localhost:8081 by default. Handles lobby creation/joining, turn-based
 * drawing, guessing chat with hints, letter-reveal timers, and scoring — entirely separate
 * from the free-draw {@code WhiteboardServer} (which stays on port 8080, untouched).
 *
 * Message protocol (JSON, every message has a "type"). See README.md for full details.
 *
 *   Client -> Server:
 *     CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, LOBBY_UPDATE_CONFIG (host), START_GAME (host),
 *     RESTART_GAME (host), WORD_CHOICE (drawer), DRAW/LINE/CLEAR (drawer), CHAT_GUESS
 *
 *   Server -> Client:
 *     ROOM_STATE, PLAYER_JOINED, PLAYER_LEFT, GAME_STARTED, TURN_START, WORD_OPTIONS (drawer),
 *     YOUR_WORD (drawer), WORD_SELECTED, TIMER_TICK, LETTER_REVEAL, CHAT_MESSAGE, SCORE_UPDATE,
 *     TURN_END, GAME_OVER, ERROR
 */
public class PictionaryServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(PictionaryServer.class);

    private final GameManager gameManager = new GameManager();

    public PictionaryServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        log.info("New connection from {}", socket.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket socket, String message) {
        try {
            gameManager.handleMessage(socket, message);
        } catch (Exception e) {
            log.warn("Failed to handle message from {}: {} — {}", socket.getRemoteSocketAddress(), message, e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        gameManager.onDisconnect(socket);
    }

    @Override
    public void onError(WebSocket socket, Exception ex) {
        log.error("WebSocket error on {}: {}", socket != null ? socket.getRemoteSocketAddress() : "null", ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("Pictionary server started on ws://localhost:{}", getPort());
        log.info("Open pictionary.html in multiple browser tabs to play.");
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        PictionaryServer server = new PictionaryServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutting down pictionary server...");
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }
}
