package com.pictionary;

import org.java_websocket.WebSocket;

/**
 * A single connected player within a Pictionary {@link GameRoom}.
 * Tracks identity, connection, score, and per-turn guessing state.
 */
public class Player {

    private final String id;
    private final WebSocket connection;
    private String name;
    private final String color;
    private int score;
    private boolean host;
    private boolean guessedThisTurn;

    public Player(String id, WebSocket connection, String name, String color) {
        this.id = id;
        this.connection = connection;
        this.name = name;
        this.color = color;
    }

    public void send(String json) {
        if (connection != null && connection.isOpen()) {
            connection.send(json);
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    // ── Getters & Setters ──

    public String getId() { return id; }
    public WebSocket getConnection() { return connection; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColor() { return color; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public boolean isHost() { return host; }
    public void setHost(boolean host) { this.host = host; }

    public boolean isGuessedThisTurn() { return guessedThisTurn; }
    public void setGuessedThisTurn(boolean guessedThisTurn) { this.guessedThisTurn = guessedThisTurn; }

    @Override
    public String toString() {
        return "Player{id=" + id + ", name=" + name + ", score=" + score + ", host=" + host + "}";
    }
}
