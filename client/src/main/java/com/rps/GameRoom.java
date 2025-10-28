package com.rps;

public class GameRoom {
    int id;
    String name;
    String status;
    int currentPlayers;
    static final int maxPlayers = 2;

    GameRoom(int id, String name, int currentPlayers, String status) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.currentPlayers = currentPlayers;
    }

    @Override
    public String toString() {
        return id + " " + name + " " + currentPlayers + "/" + maxPlayers + " " + status; // отображение по умолчанию
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCurrentPlayers() {
        return currentPlayers;
    }

    public void setCurrentPlayers(int currentPlayers) {
        this.currentPlayers = currentPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

}
