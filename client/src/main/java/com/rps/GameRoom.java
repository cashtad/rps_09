package com.rps;

/**
 * Immutable snapshot of a game room as reported by the server.
 * <p>
 * Used only on client side UI to render room list.
 */
public class GameRoom {

    /** Unique numeric identifier assigned by server. */
    private int id;

    /** Human-readable room name. */
    private String name;

    /** Current high-level room status such as OPEN/CLOSED/RUNNING. */
    private String status;

    /** Number of players currently present in the room. */
    private int currentPlayers;

    /** Maximum number of allowed players in a room. */
    private static final int MAX_PLAYERS = 2;

    /**
     * Constructs game room representation.
     *
     * @param id             room identifier from server.
     * @param name           room name.
     * @param currentPlayers current number of players.
     * @param status         textual room status.
     */
    GameRoom(int id, String name, int currentPlayers, String status) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.currentPlayers = currentPlayers;
    }

    /**
     * Returns default room string presentation used in list cells.
     *
     * @return formatted string with id, name, players and status.
     */
    @Override
    public String toString() {
        return id + " " + name + " " + currentPlayers + "/" + MAX_PLAYERS + " " + status;
    }

    /**
     * Returns room identifier.
     *
     * @return integer id.
     */
    public int getId() {
        return id;
    }

    /**
     * Updates room identifier (used only inside UI logic).
     *
     * @param id new integer id value.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * Returns room name.
     *
     * @return room name string.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets new room name for this instance.
     *
     * @param name new room name string.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns current room status text.
     *
     * @return status string.
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets room status text.
     *
     * @param status new status string.
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns number of players currently in the room.
     *
     * @return current players count.
     */
    public int getCurrentPlayers() {
        return currentPlayers;
    }

    /**
     * Sets current players count.
     *
     * @param currentPlayers new players count.
     */
    public void setCurrentPlayers(int currentPlayers) {
        this.currentPlayers = currentPlayers;
    }

    /**
     * Returns maximum allowed players for a room.
     *
     * @return constant integer value, typically 2.
     */
    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }

}
