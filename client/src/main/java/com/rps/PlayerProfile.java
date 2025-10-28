package com.rps;

public class PlayerProfile {

    private int id;
    private String name;
    private String token;
    public enum PlayerStatus {
        CONNECTED,
        AUTHENTICATED,
        IN_LOBBY,
        PLAYING,
    }

    PlayerStatus status;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public PlayerStatus getStatus() {
        return status;
    }

    public void setStatus(PlayerStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "PlayerProfile{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", token='" + token + '\'' +
                ", status=" + status +
                '}';
    }

    public PlayerProfile(String name) {
        this.name = name;
    }
}
