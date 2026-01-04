package com.rps.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * High-level protocol handler for server communication.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Transforms outgoing operations into text commands.</li>
 *     <li>Parses incoming text lines into {@link ServerEvent}.</li>
 *     <li>Aggregates multi-line responses such as room list.</li>
 * </ul>
 */
public final class ProtocolHandler {
    private static final Logger LOG = Logger.getLogger(ProtocolHandler.class.getName());

    private final NetworkManager networkManager;
    private final EventBus eventBus;
    private final RoomListAssembler roomListAssembler = new RoomListAssembler();


    /**
     * Creates a new protocol handler and attaches it to given network manager and event bus.
     *
     * @param networkManager underlying network manager instance.
     * @param eventBus       event dispatcher for parsed server messages.
     */
    public ProtocolHandler(NetworkManager networkManager, EventBus eventBus) {
        this.networkManager = Objects.requireNonNull(networkManager, "networkManager");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.networkManager.setOnMessageReceived(this::handleIncomingMessage);
        registerInternalHandlers();
    }

    private void registerInternalHandlers() {
        eventBus.subscribe("R_LIST", roomListAssembler::handleHeader); // TODO ???
        eventBus.subscribe("ROOM", roomListAssembler::handleRoom);
        eventBus.subscribe("R_CREATED", event -> requestRooms()); // TODO ???
        eventBus.subscribe("PING", event -> respondPing());
    }

    private void handleIncomingMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        if (!"PING".equals(rawMessage)) {
            LOG.info("SERVER: " + rawMessage);
        }
        List<String> tokens = MessageTokenizer.tokenize(rawMessage);
        if (tokens.isEmpty()) {
            return;
        }
        String command = tokens.get(0);
        ServerEvent event = new ServerEvent(command, tokens, rawMessage);
        eventBus.publish(event);
    }

    /**
     * Sends HELLO command with player's nickname.
     *
     * @param nickname non-null player nickname string.
     */
    public void sendHello(String nickname) {
        networkManager.send("HELLO " + nickname);
    }

    /**
     * Requests list of rooms from server (LIST command).
     */
    public void requestRooms() {
        networkManager.send("LIST");
    }

    /**
     * Sends CREATE command in order to create new room on server.
     *
     * @param name room name without spaces.
     */
    public void createRoom(String name) {
        networkManager.send("CREATE " + name);
    }

    /**
     * Sends JOIN command for given room identifier.
     *
     * @param id textual room identifier.
     */
    public void joinRoom(String id) {
        networkManager.send("JOIN " + id);
    }

    /**
     * Informs server that player is ready in current lobby (READY).
     */
    public void markReady() {
        networkManager.send("READY");
    }

    /**
     * Sends MOVE command with move code.
     *
     * @param move one of "R", "P", "S".
     */
    public void sendMove(String move) {
        networkManager.send("MOVE " + move);
    }

    /**
     * Leaves current room by sending LEAVE command.
     */
    public void leaveRoom() {
        networkManager.send("LEAVE");
    }

    /**
     * Requests opponent info in current lobby (GET_OPPONENT).
     */
    public void requestOpponentInfo() {
        networkManager.send("GET_OPP");
    }

    /**
     * Responds to server ping (PING) with PONG message.
     */
    public void respondPing() {
        networkManager.send("PONG");
    }

    /**
     * Sends RECONNECT command with given token.
     *
     * @param reconnectToken reconnect token assigned earlier.
     */
    public void sendReconnect(String reconnectToken) {
        networkManager.send("RECONNECT " + reconnectToken);
    }

    /**
     * Aggregates multi-line ROOM_LIST/ROOM sequence into single ROOMS_LOADED event.
     */
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
            payload.add("R_LOADED");
            if (!roomsData.isEmpty()) {
                payload.add(roomsData);
            }
            String fullMessage = payload.size() > 1 ? "R_LOADED " + roomsData : "ROOMS_LOADED";
            eventBus.publish(new ServerEvent("R_LOADED", payload, fullMessage));
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

    /**
     * Tokenizes raw text lines into arguments taking quotes and escaping into account.
     */
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
            if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
    }
}
