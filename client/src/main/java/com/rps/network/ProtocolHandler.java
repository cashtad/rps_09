package com.rps.network;

import java.util.ArrayList;
import java.util.List;

public class ProtocolHandler {
    private final NetworkManager net;
    private final EventBus eventBus;
    private String token;

    // Временные данные для сбора списка комнат
    private final List<String> tempRooms = new ArrayList<>();
    private int expectedRooms = 0;

    public ProtocolHandler(NetworkManager net, EventBus eventBus) {
        this.net = net;
        this.eventBus = eventBus;
        this.net.setOnMessageReceived(this::handleMessage);
        setupInternalHandlers();
    }

    /**
     * Настраиваем внутренние обработчики для команд,
     * требующих промежуточной логики (например, сбор списка комнат)
     */
    private void setupInternalHandlers() {
        // Обработка ROOM_LIST - начало сбора комнат
        eventBus.subscribe("ROOM_LIST", event -> {
            expectedRooms = Integer.parseInt(event.getPart(1));
            tempRooms.clear();

            // Если комнат 0, сразу публикуем событие с пустым списком
            if (expectedRooms == 0) {
                eventBus.publish(new ServerEvent("ROOMS_LOADED",
                        new String[]{"ROOMS_LOADED"}, "ROOMS_LOADED"));
            }
        });

        // Обработка ROOM - добавление комнаты в список
        eventBus.subscribe("ROOM", event -> {
            tempRooms.add(event.getFullMessage().substring(5));

            // Когда собрали все комнаты, публикуем событие
            if (tempRooms.size() == expectedRooms) {
                // Создаем специальное событие с полным списком комнат
                String roomsData = String.join("|", tempRooms);
                eventBus.publish(new ServerEvent("ROOMS_LOADED",
                        new String[]{"ROOMS_LOADED", roomsData}, roomsData));
            }
        });

        // Обработка ROOM_CREATED - автоматический запрос списка комнат
        eventBus.subscribe("ROOM_CREATED", event -> {
            requestRooms();
        });
    }

    /**
     * Основной обработчик входящих сообщений
     */
    private void handleMessage(String msg) {
        System.out.println("SERVER: " + msg);

        String[] parts = msg.split(" ", 2); // Разделяем только на команду и остальное
        String command = parts[0];

        // Парсим для детального доступа
        String[] allParts = msg.split(" ");

        // Создаем событие и публикуем в шину
        ServerEvent event = new ServerEvent(command, allParts, msg);
        eventBus.publish(event);

        // Сохраняем токен при WELCOME
        if ("WELCOME".equals(command) && allParts.length > 1) {
            token = allParts[1];
        }
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

    public void leaveRoom() {
        net.send("LEAVE");
    }

    public String getToken() {
        return token;
    }

    public EventBus getEventBus() {
        return eventBus;
    }
}