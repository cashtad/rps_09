package com.rps.network;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ProtocolHandler {
    private final NetworkManager net;
    private String token;
    private Consumer<String> uiUpdater; // updates UI
    private Consumer<String> onWelcome;          // вызывается при WELCOME
    private Consumer<List<String>> onRoomList;   // вызывается при ROOM_LIST/ROOM
    private Consumer<String> onRoomJoined;
    private final List<String> tempRooms = new ArrayList<>();
    private int expectedRooms = 0;


    public ProtocolHandler(NetworkManager net) {
        this.net = net;
        this.net.setOnMessageReceived(this::handleMessage);
    }

    public void setUiUpdater(Consumer<String> uiUpdater) {
        this.uiUpdater = uiUpdater;
    }

    private void handleMessage(String msg) {
        System.out.println("SERVER: " + msg);
        String[] parts = msg.split(" ");
        String cmd = parts[0];

        switch (cmd) {
            case "WELCOME" -> {
                token = parts[1];
                if (onWelcome != null)
                    Platform.runLater(() -> onWelcome.accept(parts[1]));
            }
            case "ROOM_LIST" -> {
                expectedRooms = Integer.parseInt(parts[1]);
                tempRooms.clear();
                if (expectedRooms == 0 && onRoomList != null) {
                    Platform.runLater(() -> onRoomList.accept(tempRooms));
                }
            }
            case "ROOM" -> {
                tempRooms.add(msg.substring(5));
                if (tempRooms.size() == expectedRooms && onRoomList != null) {
                    Platform.runLater(() -> onRoomList.accept(new ArrayList<>(tempRooms)));
                }
            }
            case "ROOM_CREATED" -> {
                requestRooms();
            }
            case "ROOM_JOINED" -> {
                int room_id = Integer.parseInt(parts[1]);
                if (onRoomJoined != null) {
                    Platform.runLater(() -> onRoomJoined.accept(parts[1]));
                }
            }
            case "GAME_START" -> updateUi("Game started!");
            case "ROUND_START" -> updateUi("Round " + parts[1] + " started!");
            case "ROUND_RESULT" -> updateUi("Round result: " + msg);
            case "GAME_END" -> updateUi("Winner: " + parts[1]);
            case "ERR" -> updateUi("Error " + parts[1] + ": " + parts[2]);
            default -> updateUi("Server: " + msg);
        }
    }

    private void updateUi(String text) {
        if (uiUpdater != null)
            Platform.runLater(() -> uiUpdater.accept(text));
    }

    // =================== Client Commands ===================

    public void sendHello(String nickname) {
        net.send("HELLO " + nickname);
    }

    public void requestRooms() {
        net.send("LIST");
    }

    public void createRoom(String name) {
        net.send("CREATE " + name);
    }

    public void joinRoom(String id) {
        net.send("JOIN " + id);
    }

    public void markReady() {
        net.send("READY");
    }

    public void sendMove(String move) {
        net.send("MOVE " + move);
    }

    public void setOnWelcome(Consumer<String> handler) {
        this.onWelcome = handler;
    }

    public void setOnRoomList(Consumer<List<String>> handler) {
        this.onRoomList = handler;
    }

    public void setOnRoomJoined(Consumer<String> handler) {
        this.onRoomJoined = handler;
    }
}
