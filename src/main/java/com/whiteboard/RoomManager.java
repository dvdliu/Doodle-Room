package com.whiteboard;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages connected users and broadcasts drawing events. Thread-safe — uses
 * ConcurrentHashMap for session storage.
 */
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final Gson gson = new Gson();

    // socket → session lookup (O(1) broadcast per message)
    private final Map<WebSocket, UserSession> sessions = new ConcurrentHashMap<>();

    // Replay buffer: last N events so new joiners see recent history
    private static final int HISTORY_LIMIT = 500;
    private final Deque<DrawEvent> history = new ArrayDeque<>(HISTORY_LIMIT);

    /**
     * Register a new user connection. Sends them the current history,
     * then announces their arrival to all other connected peers.
     */
    public void onConnect(WebSocket socket, String userId, String username) {
        UserSession session = new UserSession(userId, socket, username);
        sessions.put(socket, session);
        log.info("User connected: {} ({}), total={}", username, userId, sessions.size());

        // Send history replay to the new joiner
        sendHistoryTo(session);

        // Broadcast JOIN event to all peers (including new user so they see the user list)
        DrawEvent join = new DrawEvent(DrawEvent.Type.USER_JOIN, userId);
        join.setUsername(username);
        join.setColor(session.getColor());
        broadcastAll(join);
    }

    /**
     * Handle an incoming draw event. Stamps the user color, stores in history,
     * and broadcasts to all OTHER connected peers.
     */
    public void onDraw(WebSocket socket, DrawEvent event) {
        UserSession session = sessions.get(socket);
        if (session == null) return;

        // Stamp authoritative color from server-side session (clients can't spoof it)
        event.setColor(session.getColor());
        event.setUserId(session.getUserId());
        event.setUsername(session.getUsername());

        // Store in history (skip cursor events — no need to replay those)
        if (event.getType() != DrawEvent.Type.CURSOR) {
            synchronized (history) {
                if (history.size() >= HISTORY_LIMIT) history.pollFirst();
                history.addLast(event);
            }

            // CLEAR resets history too
            if (event.getType() == DrawEvent.Type.CLEAR) {
                synchronized (history) { history.clear(); }
            }
        }

        // Update cursor position
        if (event.getType() == DrawEvent.Type.CURSOR) {
            session.setCursorX(event.getX());
            session.setCursorY(event.getY());
        }

        // Relay to all OTHER peers
        broadcastExcept(socket, event);
    }

    /**
     * Remove a session and notify remaining peers.
     */
    public void onDisconnect(WebSocket socket) {
        UserSession session = sessions.remove(socket);
        if (session == null) return;

        log.info("User disconnected: {} ({}), remaining={}", session.getUsername(), session.getUserId(), sessions.size());

        DrawEvent leave = new DrawEvent(DrawEvent.Type.USER_LEAVE, session.getUserId());
        leave.setUsername(session.getUsername());
        broadcastAll(leave);
    }

    /**
     * Replay recent history to a newly connected session.
     */
    private void sendHistoryTo(UserSession session) {
        synchronized (history) {
            for (DrawEvent event : history) {
                session.sendMessage(gson.toJson(event));
            }
        }
    }

    /** Broadcast to everyone including sender. */
    private void broadcastAll(DrawEvent event) {
        String json = gson.toJson(event);
        sessions.values().forEach(s -> s.sendMessage(json));
    }

    /** Broadcast to everyone except the originating socket. */
    private void broadcastExcept(WebSocket origin, DrawEvent event) {
        String json = gson.toJson(event);
        sessions.forEach((socket, session) -> {
            if (!socket.equals(origin)) {
                session.sendMessage(json);
            }
        });
    }

    public int getConnectedCount() {
        return sessions.size();
    }

    public Collection<UserSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }
}