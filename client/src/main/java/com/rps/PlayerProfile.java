package com.rps;

import java.util.logging.Logger;

/**
 * Holds local player identity and session state.
 * <p>
 * Used by client to correlate server messages with local user.
 */
public class PlayerProfile {

    private static final Logger LOG = Logger.getLogger(PlayerProfile.class.getName());


    /** Internal numeric identifier (optional). */
    private int id;

    /** Player nickname as sent in HELLO command. */
    private String name;

    /** Session token assigned by server. */
    private String token;

    /**
     * High-level player status on client side.
     */
    public enum PlayerStatus {
        /** Connected but not authenticated/associated yet. */
        CONNECTED,
        /** Successfully authenticated (if supported by server). */
        AUTHENTICATED,
        /** Player is inside lobby/room. */
        IN_LOBBY,
        /** Player is inside lobby/room and ready to play. */
        READY,
        /** Player is in active game session. */
        PLAYING,
    }

    /** Current status assigned by client. */
    private PlayerStatus status;

    /**
     * Constructs player profile with nickname.
     *
     * @param name player nickname string.
     */
    public PlayerProfile(String name) {
        this.name = name;
    }

    /**
     * Constructs player profile with default values.
     *
     */
    public PlayerProfile() {}

    /**
     * Returns internal id.
     *
     * @return numeric identifier.
     */
    public int getId() {
        return id;
    }

    /**
     * Sets internal id.
     *
     * @param id new identifier value.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * Returns player nickname.
     *
     * @return non-null name string.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets player nickname.
     *
     * @param name new nickname string.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns session token assigned by server.
     *
     * @return token string or null if not yet set.
     */
    public String getToken() {
        return token;
    }

    /**
     * Sets new session token.
     *
     * @param token token string received from server.
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Returns current client-side player status.
     *
     * @return {@link PlayerStatus} value.
     */
    public PlayerStatus getStatus() {
        return status;
    }

    /**
     * Updates current player status.
     *
     * @param status new {@link PlayerStatus} value.
     */
    public void setStatus(PlayerStatus status) {

        this.status = status;
        LOG.info("Player status changed to " + status);
    }

    /**
     * Returns human-readable representation of player profile for debugging.
     *
     * @return string with main fields.
     */
    @Override
    public String toString() {
        return "PlayerProfile{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", token='" + token + '\'' +
                ", status=" + status +
                '}';
    }
}
