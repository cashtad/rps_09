package com.rps.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public final class ProtocolHandler {
    private static final Logger LOG = Logger.getLogger(ProtocolHandler.class.getName());

    private final NetworkManager networkManager;
    private final EventBus eventBus;
    private final RoomListAssembler roomListAssembler = new RoomListAssembler();

    private volatile String token;

    public ProtocolHandler(NetworkManager networkManager, EventBus eventBus) {
        this.networkManager = Objects.requireNonNull(networkManager, "networkManager");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.networkManager.setOnMessageReceived(this::handleIncomingMessage);
        registerInternalHandlers();
    }

    private void registerInternalHandlers() {
        eventBus.subscribe("ROOM_LIST", roomListAssembler::handleHeader);
        eventBus.subscribe("ROOM", roomListAssembler::handleRoom);
        eventBus.subscribe("ROOM_CREATED", event -> requestRooms());
        eventBus.subscribe("PING", event -> respondPing());
    }

    private void handleIncomingMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        if (!"PING".equals(rawMessage)) {
            LOG.info(() -> "SERVER: " + rawMessage);
        }
        List<String> tokens = MessageTokenizer.tokenize(rawMessage);
        if (tokens.isEmpty()) {
            return;
        }
        String command = tokens.get(0);
        ServerEvent event = new ServerEvent(command, tokens, rawMessage);
        eventBus.publish(event);
        if ("WELCOME".equals(command) && tokens.size() > 1) {
            token = tokens.get(1);
        }
    }

    public void sendHello(String nickname) {
        networkManager.send("HELLO " + nickname);
    }

    public void requestRooms() {
        networkManager.send("LIST");
    }

    public void createRoom(String name) {
        networkManager.send("CREATE " + name);
    }

    public void joinRoom(String id) {
        networkManager.send("JOIN " + id);
    }

    public void markReady() {
        networkManager.send("READY");
    }

    public void sendMove(String move) {
        networkManager.send("MOVE " + move);
    }

    public void leaveRoom() {
        networkManager.send("LEAVE");
    }

    public void requestOpponentInfo() {
        networkManager.send("GET_OPPONENT");
    }

    public void respondPing() {
        networkManager.send("PONG");
    }

    public void sendReconnect(String reconnectToken) {
        networkManager.send("RECONNECT " + reconnectToken);
    }

    public String getToken() {
        return token;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    private final class RoomListAssembler {
        private final List<String> rooms = new ArrayList<>();
        private int expected = 0;

        void handleHeader(ServerEvent event) {
            synchronized (rooms) {
                expected = parseCount(event.getPart(1));
                rooms.clear();
                if (expected == 0) {
                    publishRooms(List.of());
                }
            }
        }

        void handleRoom(ServerEvent event) {
            synchronized (rooms) {
                if (expected == 0) {
                    return;
                }
                rooms.add(extractRoomPayload(event.getFullMessage()));
                if (rooms.size() >= expected) {
                    publishRooms(new ArrayList<>(rooms));
                    rooms.clear();
                    expected = 0;
                }
            }
        }

        private void publishRooms(List<String> snapshot) {
            String roomsData = String.join("|", snapshot);
            List<String> payload = new ArrayList<>();
            payload.add("ROOMS_LOADED");
            if (!roomsData.isEmpty()) {
                payload.add(roomsData);
            }
            String fullMessage = payload.size() > 1 ? "ROOMS_LOADED " + roomsData : "ROOMS_LOADED";
            eventBus.publish(new ServerEvent("ROOMS_LOADED", payload, fullMessage));
        }

        private int parseCount(String raw) {
            try {
                return raw != null ? Integer.parseInt(raw) : 0;
            } catch (NumberFormatException ex) {
                LOG.warning(() -> "Invalid ROOM_LIST count: " + raw);
                return 0;
            }
        }

        private String extractRoomPayload(String fullMessage) {
            return fullMessage.length() > 5 ? fullMessage.substring(5) : "";
        }
    }

    private static final class MessageTokenizer {
        private MessageTokenizer() {
        }

        static List<String> tokenize(String raw) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    current.append(raw.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (Character.isWhitespace(c) && !inQuotes) {
                    flush(current, tokens);
                } else {
                    current.append(c);
                }
            }
            flush(current, tokens);
            return tokens;
        }

        private static void flush(StringBuilder current, List<String> tokens) {
            if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
    }
}
