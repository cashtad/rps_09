package com.rps;

import com.rps.network.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Main JavaFX entry point for the Rock-Paper-Scissors client.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Application lifecycle (start/stop).</li>
 *     <li>Initialization of networking components.</li>
 *     <li>Event bindings between server events and UI modules.</li>
 * </ul>
 */
public class MainApp extends Application {

    /** Handles low-level TCP networking and timeouts. */
    private NetworkManager networkManager;

    /** Encapsulates text protocol, sends commands and parses responses. */
    private ProtocolHandler protocolHandler;

    /** Event bus used to deliver parsed server messages to listeners. */
    private EventBus eventBus;

    /** Controls automatic and manual reconnection procedures. */
    private ReconnectionManager reconnectionManager;

    /** Primary JavaFX stage used by the whole application. */
    private Stage primaryStage;

    /** Player profile for the currently connected user. */
    private PlayerProfile playerProfile;

    /** Last known host used for connections. */
    private String currentHost = "0.0.0.0";

    /** Last known port used for connections. */
    private int currentPort = 2500;

    /** Flag that reflects logical connection state for UI. */
    private boolean isConnected = false;

    /** Global label shown on every scene to display connection status. */
    private final Label globalConnectionStatusLabel = createConnectionStatusLabel();

    /** UI module responsible for connection/login and reconnection screens. */
    private ConnectionUi connectionUi;

    /** UI module responsible for rooms list and room creation dialog. */
    private RoomsUi roomsUi;

    /** UI module responsible for lobby representation. */
    private LobbyUi lobbyUi;

    /** UI module responsible for the game scene (rounds, timer, moves). */
    private GameUi gameUi;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Initialize components that are shared across the whole client.
        networkManager = new NetworkManager();
        eventBus = EventBus.createJavaFxBus();
        protocolHandler = new ProtocolHandler(networkManager, eventBus);
        reconnectionManager = new ReconnectionManager(networkManager, protocolHandler, eventBus);

        // Instantiate UI helpers that encapsulate scene construction.
        connectionUi = new ConnectionUi();
        roomsUi = new RoomsUi();
        lobbyUi = new LobbyUi();
        gameUi = new GameUi();

        // Configure reconnection callbacks first to properly react on disconnects.
        setupReconnectionHandlers();

        // Configure event bus handlers before any UI actions trigger network calls.
        setupEventHandlers();

        // Show initial login/connection scene.
        primaryStage.setTitle("RPS Client");
        primaryStage.setScene(connectionUi.buildLoginScene());
        updateConnectionStatus(false);
        primaryStage.show();
    }

    /**
     * Configures callbacks for soft/hard timeouts and auto/manual reconnection.
     * Input: none. Output: none. Side-effect: sets handlers on {@link NetworkManager}
     * and {@link ReconnectionManager}.
     */
    private void setupReconnectionHandlers() {
        networkManager.setOnDisconnected(() -> {
            Platform.runLater(() -> updateConnectionStatus(false));

            if (playerProfile != null && playerProfile.getToken() != null) {
                reconnectionManager.startAutoReconnect(playerProfile.getToken());
            }
        });

        networkManager.setOnSoftTimeout(() -> {
            if (playerProfile == null || playerProfile.getToken() == null || reconnectionManager.isReconnecting()) {
                return;
            }
            Platform.runLater(() -> updateConnectionStatus(false));
            reconnectionManager.startAutoReconnect(playerProfile.getToken());
        });

        networkManager.setOnHardTimeout(() -> {
            reconnectionManager.abortAutoReconnect();
            networkManager.disconnect();
            Platform.runLater(() -> {
                updateConnectionStatus(false);
                primaryStage.setScene(connectionUi.buildManualReconnectScene());
            });
        });

        reconnectionManager.setOnAutoReconnectFailed(() ->
                Platform.runLater(() -> primaryStage.setScene(connectionUi.buildManualReconnectScene())));

        reconnectionManager.setOnReconnectSuccess(state -> Platform.runLater(() -> {
            updateConnectionStatus(true);
            handleReconnectState(state);
            showAlert("Reconnected", "Connection restored!");
        }));

        reconnectionManager.setConnectionInfo(currentHost, currentPort);
    }

    /**
     * Sets up all event bus subscriptions for server commands.
     * <p>
     * Input: none. Output: none. Side-effect: registers handlers on {@link EventBus}.
     */
    private void setupEventHandlers() {
        eventBus.setOnTooManyInvalid(() -> {
            Platform.runLater(() -> {
                networkManager.disconnect();
                showAlert("Disconnected", "Too many invalid messages received. Connection closed.");
                updateConnectionStatus(false);
                primaryStage.setScene(connectionUi.buildManualReconnectScene());
            });

        });

        // Optional logging of every event may be enabled here if required.
        eventBus.subscribeAll(event -> {

        });

        // Welcome message means connection established and token received.
        eventBus.subscribe("WELCOME", event -> {
            String token = event.getPart(1);
            playerProfile = new PlayerProfile(connectionUi.getEnteredName());
            playerProfile.setToken(token);
            playerProfile.setStatus(PlayerProfile.PlayerStatus.CONNECTED);

            connectionUi.onConnected(token);
            updateConnectionStatus(true);
        });

        // Server finished sending a rooms list.
        eventBus.subscribe("ROOMS_LOADED", event -> {
            String roomsData = event.getPart(1);
            List<String> roomList = roomsData != null && !roomsData.isEmpty()
                    ? List.of(roomsData.split("\\|"))
                    : new ArrayList<>();
            primaryStage.setScene(roomsUi.buildRoomsScene(roomList));
            updateConnectionStatus(isConnected);
        });

        // Client joined a room and should see lobby scene.
        eventBus.subscribe("ROOM_JOINED", event -> {
            String roomId = event.getPart(1);
            if (playerProfile != null) {
                playerProfile.setStatus(PlayerProfile.PlayerStatus.IN_LOBBY);
            }
            primaryStage.setScene(lobbyUi.buildLobbyScene(roomId, playerProfile != null ? playerProfile.getName() : ""));
            updateConnectionStatus(isConnected);
        });

        // Error message from server.
        eventBus.subscribe("ERR", event -> {
            String errorCode = event.getPart(1);
            String errorMsg = event.getPartsCount() > 2 ? event.getPart(2) : "Unknown error";
            if ("107".equals(errorCode)) {
                networkManager.disconnect();
            }
            showAlert("Error", "Error " + errorCode + ": " + errorMsg);
        });

        // Generic confirmation handler.
        eventBus.subscribe("OK", this::handleConfirmation);

        // Lobby-related events.
        eventBus.subscribe("OPPONENT_INFO", lobbyUi::handleOpponentInfo);
        eventBus.subscribe("PLAYER_JOINED", lobbyUi::handlePlayerJoined);
        eventBus.subscribe("PLAYER_READY", lobbyUi::handlePlayerReady);
        eventBus.subscribe("PLAYER_UNREADY", lobbyUi::handlePlayerUnready);
        eventBus.subscribe("PLAYER_LEFT", lobbyUi::handlePlayerLeft);

        // Game start / game scene.
        eventBus.subscribe("GAME_START", gameUi::showGameScene);

        // Game round events.
        eventBus.subscribe("ROUND_START", gameUi::handleRoundStart);
        eventBus.subscribe("ROUND_RESULT", gameUi::handleRoundResult);
        eventBus.subscribe("GAME_END", gameUi::handleGameEnd);

        // Game pause / resume events.
        eventBus.subscribe("GAME_PAUSED", event -> Platform.runLater(() -> {
            gameUi.stopTimer();
            gameUi.disableMoveButtons();
            gameUi.setGameStatusText("Game paused - opponent disconnected");
            showAlert("Game Paused", "Opponent has disconnected. Waiting for reconnection...");
        }));

        eventBus.subscribe("GAME_RESUMED", event -> {
            int roundNumber = Integer.parseInt(event.getPart(1));
            int score1 = Integer.parseInt(event.getPart(2));
            int score2 = Integer.parseInt(event.getPart(3));
            char performedMove = event.getParts().length >= 5 ? event.getPart(4).charAt(0) : 'X';

            Platform.runLater(() -> {
                gameUi.updateScores(score1, score2);
                if (performedMove == 'X') {
                    gameUi.enableMoveButtons();
                    gameUi.setGameStatusText("Game resumed - Make your move!");
                } else {
                    gameUi.disableMoveButtons();
                    gameUi.setGameStatusText("Game resumed - Waiting for opponent...");
                }
                gameUi.startTimer(10);
                showAlert("Game Resumed", "Continue playing!");
            });
        });

        // Move accepted by server.
        eventBus.subscribe("MOVE_ACCEPTED", event ->
                Platform.runLater(() -> {
                    gameUi.disableMoveButtons();
                    gameUi.setGameStatusText("Waiting for opponent...");
                }));
    }

    /**
     * Handles protocol confirmation responses from the server.
     *
     * @param event server event that contains confirmation details. Input: parsed server message.
     */
    private void handleConfirmation(ServerEvent event) {
        String confirmedCommand = event.getPart(1);
        switch (confirmedCommand) {
            case "you_are_ready" -> lobbyUi.onPlayerReadyConfirmed();
            case "left_room" -> lobbyUi.onRoomLeave();
            default -> {
                // No-op for unknown confirmations.
            }
        }
    }

    /**
     * Handles a parsed reconnection state string returned by {@link ReconnectionManager}.
     * <p>
     * Input: state string like "GAME ..." or "LOBBY ...". Output: none.
     * Side-effect: navigates to appropriate scene and restores local UI state.
     *
     * @param state textual description of server-side state after reconnection.
     */
    private void handleReconnectState(String state) {
        if (state.startsWith("GAME")) {
            String[] parts = state.split(" ");
            if (parts.length >= 4) {
                int score1 = Integer.parseInt(parts[1]);
                int score2 = Integer.parseInt(parts[2]);
                int round = Integer.parseInt(parts[3]);
                char performedMove = parts.length >= 5 ? parts[4].charAt(0) : 'X';

                primaryStage.setScene(gameUi.buildGameScene());
                gameUi.updateScores(score1, score2);

                if (performedMove != 'X') {
                    gameUi.disableMoveButtons();
                    gameUi.setGameStatusText("Reconnected! Round " + round + " - Waiting for opponent...");
                } else {
                    gameUi.enableMoveButtons();
                    gameUi.setGameStatusText("Reconnected! Round " + round + " - Make your move!");
                    gameUi.startTimer(10);
                }
            }
        } else if (state.startsWith("LOBBY")) {
            String[] parts = state.split(" ");
            if (playerProfile != null) {
                primaryStage.setScene(lobbyUi.buildLobbyScene("reconnected", playerProfile.getName()));

                if (parts.length >= 2 && !"NONE".equals(parts[1])) {
                    String opponentNick = parts[1];
                    String opponentStatus = parts.length >= 3 ? parts[2] : "NOT_READY";
                    lobbyUi.updateOpponentInfo(opponentNick, opponentStatus);
                }
            }
        } else {
            protocolHandler.requestRooms();
        }
    }

    /**
     * Attempts to establish connection to server using values entered in login UI.
     * <p>
     * Input: user-entered nickname, host and port. Output: none.
     * Side-effect: opens TCP connection and sends HELLO command.
     */
    private void connectToServer() {
        String nickname = connectionUi.getEnteredName().trim();
        if (nickname.isEmpty()) {
            showAlert("Error", "Enter your name!");
            return;
        }
        if (nickname.split(" ").length > 1) {
            showAlert("Error", "Name cannot contain spaces!");
            return;
        }
        if (nickname.length() > 32) {
            showAlert("Error", "Name too long! Max 32 characters.");
            return;
        }

        String host = connectionUi.getEnteredHost().trim();
        if (host.isEmpty()) {
            showAlert("Error", "Enter server IP!");
            return;
        }
        String[] hostParts = host.split("\\.");
        if (hostParts.length != 4) {
            showAlert("Error", "Invalid IP address format!");
            return;
        }
        for (String part : hostParts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                showAlert("Error", "Invalid IP address format!");
                return;
            }
        }

        int port;
        try {
            port = Integer.parseInt(connectionUi.getEnteredPort().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showAlert("Error", "Invalid port number! Use 1-65535");
            return;
        }

        currentHost = host;
        currentPort = port;
        reconnectionManager.setConnectionInfo(currentHost, currentPort);

        try {
            networkManager.connect(currentHost, currentPort);
            protocolHandler.sendHello(nickname);
            updateConnectionStatus(true);
        } catch (Exception ex) {
            showAlert("Connection error", ex.getMessage());
            updateConnectionStatus(false);
        }
    }

    /**
     * Displays a simple information alert dialog.
     *
     * @param title   window title string. Input: non-null string.
     * @param message message body string. Input: non-null string.
     */
    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Creates a label instance that will be used to display connection status.
     */
    private Label createConnectionStatusLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5;");
        label.setAlignment(Pos.CENTER_RIGHT);
        return label;
    }

    /**
     * Updates the connection status caption and style across currently active scene.
     */
    private void updateConnectionStatus(boolean connected) {
        this.isConnected = connected;
        Platform.runLater(() -> {
            if (connected) {
                globalConnectionStatusLabel.setText("Connected");
                globalConnectionStatusLabel.setStyle(
                        "-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5; -fx-text-fill: green;"
                );
            } else {
                globalConnectionStatusLabel.setText("Not connected");
                globalConnectionStatusLabel.setStyle(
                        "-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5; -fx-text-fill: red;"
                );
            }
        });
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        gameUi.stopTimer();
        eventBus.clear();
        networkManager.disconnect();
        reconnectionManager.shutdown();
    }

    /**
     * Standard Java main entry for launching JavaFX application.
     *
     * @param args command line arguments passed to JavaFX launcher.
     */
    public static void main(String[] args) {
        launch(args);
    }

    // ========================================================================
    // Inner UI helper classes
    // ========================================================================

    /**
     * Encapsulates login/connect UI and manual reconnect screen.
     */
    private final class ConnectionUi {

        private TextField nameField;
        private TextField hostField;
        private TextField portField;
        private Button connectButton;
        private Button listRoomsButton;
        private Label statusLabel;

        /**
         * Builds the initial login scene with fields for nickname, host and port.
         *
         * @return fully configured {@link Scene} instance.
         */
        Scene buildLoginScene() {
            VBox layout = new VBox(10);
            layout.setStyle("-fx-padding: 20;");
            layout.setAlignment(Pos.CENTER);

            layout.getChildren().add(globalConnectionStatusLabel);

            Label titleLabel = new Label("Connect to Server");
            titleLabel.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");

            nameField = new TextField();
            nameField.setPromptText("Enter your name");

            hostField = new TextField("10.0.2.2");
            hostField.setPromptText("Server IP");

            portField = new TextField("2500");
            portField.setPromptText("Server Port");

            connectButton = new Button("Connect");
            connectButton.setPrefWidth(200);

            statusLabel = new Label();
            statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

            listRoomsButton = new Button("List of rooms");
            listRoomsButton.setDisable(true);

            connectButton.setOnAction(e -> connectToServer());
            listRoomsButton.setOnAction(e -> protocolHandler.requestRooms());

            layout.getChildren().addAll(
                    titleLabel,
                    new Label("Name:"), nameField,
                    new Label("Server IP:"), hostField,
                    new Label("Port:"), portField,
                    connectButton,
                    statusLabel,
                    listRoomsButton
            );

            return new Scene(layout, 350, 400);
        }

        /**
         * Builds a manual reconnect scene that is shown when automatic reconnect fails.
         *
         * @return new {@link Scene} representing manual reconnect UI.
         */
        Scene buildManualReconnectScene() {
            VBox layout = new VBox(20);
            layout.setAlignment(Pos.CENTER);
            layout.setStyle("-fx-padding: 40;");

            layout.getChildren().add(globalConnectionStatusLabel);
            updateConnectionStatus(false);

            Label titleLabel = new Label("Connection Lost");
            titleLabel.setStyle("-fx-font-size: 24; -fx-font-weight: bold;");

            Label messageLabel = new Label("Automatic reconnection failed.\nPlease try to reconnect manually.");
            messageLabel.setStyle("-fx-font-size: 14; -fx-text-alignment: center;");
            messageLabel.setWrapText(true);

            Label serverInfoLabel = new Label("Server: " + currentHost + ":" + currentPort);
            serverInfoLabel.setStyle("-fx-font-size: 12; -fx-text-fill: gray;");

            Button resetButton = new Button("Start Over");
            resetButton.setStyle("-fx-font-size: 16; -fx-min-width: 150;");
            resetButton.setOnAction(e -> resetToLogin());

            layout.getChildren().addAll(titleLabel, messageLabel, serverInfoLabel, resetButton);
            return new Scene(layout, 400, 350);
        }

        /**
         * Handles successful connection and token arrival.
         *
         * @param token authentication token provided by the server.
         */
        void onConnected(String token) {
            statusLabel.setText("Connected as " + token);
            connectButton.setDisable(true);
            listRoomsButton.setDisable(false);
        }

        /**
         * Resets state back to the login scene while keeping last host/port values.
         */
        private void resetToLogin() {
            playerProfile = null;
            if (nameField != null) {
                nameField.clear();
            }
            if (connectButton != null) {
                connectButton.setDisable(false);
            }
            if (listRoomsButton != null) {
                listRoomsButton.setDisable(true);
            }
            if (statusLabel != null) {
                statusLabel.setText("");
            }
            primaryStage.setScene(buildLoginScene());
        }

        String getEnteredName() {
            return nameField != null ? nameField.getText() : "";
        }

        String getEnteredHost() {
            return hostField != null ? hostField.getText() : "";
        }

        String getEnteredPort() {
            return portField != null ? portField.getText() : "";
        }
    }

    /**
     * Encapsulates list-of-rooms scene and room creation dialog.
     */
    private final class RoomsUi {

        Scene buildRoomsScene(List<String> roomsRaw) {
            VBox layout = new VBox(10);
            layout.setStyle("-fx-padding: 20;");

            layout.getChildren().add(globalConnectionStatusLabel);

            Label title = new Label("List of rooms:");

            List<GameRoom> roomItems = new ArrayList<>();
            for (String raw : roomsRaw) {
                String[] parts = raw.split(" ");
                if (parts.length >= 4) {
                    roomItems.add(new GameRoom(
                            Integer.parseInt(parts[0]),
                            parts[1],
                            Integer.parseInt(parts[2].split("/")[0]),
                            parts[3]
                    ));
                }
            }

            ListView<GameRoom> listView = new ListView<>();
            listView.getItems().addAll(roomItems);

            listView.setCellFactory(lv -> new ListCell<>() {
                private final Button joinButton = new Button("Join");
                private final HBox hbox = new HBox(10);

                {
                    joinButton.setOnAction(e -> {
                        GameRoom item = getItem();
                        if (item != null) {
                            protocolHandler.joinRoom(String.valueOf(item.getId()));
                        }
                    });
                }

                @Override
                protected void updateItem(GameRoom item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        Label nameLabel = new Label(item.toString());
                        hbox.getChildren().setAll(nameLabel);
                        if ("OPEN".equals(item.getStatus())) {
                            hbox.getChildren().setAll(nameLabel, joinButton);
                        }
                        setGraphic(hbox);
                    }
                }
            });

            Button createRoomButton = new Button("Create room");
            createRoomButton.setOnAction(e -> showCreateRoomDialog());

            Button refreshButton = new Button("Refresh");
            refreshButton.setOnAction(e -> protocolHandler.requestRooms());

            HBox buttons = new HBox(10, createRoomButton, refreshButton);
            layout.getChildren().addAll(title, listView, buttons);

            return new Scene(layout, 400, 400);
        }

        /**
         * Shows a simple modal dialog that allows the user to create a room.
         * <p>
         * Input: none. Output: none. Side-effect: issues CREATE command on success.
         */
        private void showCreateRoomDialog() {
            javafx.stage.Stage dialog = new javafx.stage.Stage();
            dialog.initOwner(primaryStage);
            dialog.setTitle("Create room");

            VBox dialogLayout = new VBox(10);
            dialogLayout.setStyle("-fx-padding: 20;");

            Label prompt = new Label("Enter room name:");
            TextField roomNameField = new TextField();

            Button cancelButton = new Button("Cancel");
            Button confirmButton = new Button("Create");

            cancelButton.setOnAction(e -> dialog.close());
            confirmButton.setOnAction(e -> {
                String roomName = roomNameField.getText().trim();
                if (roomName.isEmpty()) {
                    showAlert("Error", "Enter room name!");
                    return;
                }
                if (roomName.split(" ").length > 1) {
                    showAlert("Error", "Name cannot contain spaces!");
                    return;
                }
                if (roomName.length() > 32) {
                    showAlert("Error", "Name too long! Max 32 characters.");
                    return;
                }

                protocolHandler.createRoom(roomName);
                dialog.close();
            });

            HBox buttons = new HBox(10, cancelButton, confirmButton);
            dialogLayout.getChildren().addAll(prompt, roomNameField, buttons);

            Scene dialogScene = new Scene(dialogLayout, 250, 150);
            dialog.setScene(dialogScene);
            dialog.show();
        }
    }

    /**
     * Encapsulates lobby UI: player/opponent info and ready state.
     */
    private final class LobbyUi {

        private Label opponentLabel;
        private Label opponentStatusLabel;
        private Label playerStatusLabel;
        private Button readyButton;

        /**
         * Builds the lobby scene for a given room and local player.
         *
         * @param roomId     room identifier string or description.
         * @param playerName local player's nickname.
         * @return new {@link Scene} instance representing lobby layout.
         */
        Scene buildLobbyScene(String roomId, String playerName) {
            BorderPane lobbyLayout = new BorderPane();
            lobbyLayout.setStyle("-fx-padding: 20;");

            VBox topBox = new VBox(5);

            // общий лейбл
            topBox.getChildren().add(globalConnectionStatusLabel);

            Button backButton = new Button("Back");
            backButton.setOnAction(e -> {
                protocolHandler.leaveRoom();
            });

            topBox.getChildren().add(backButton);
            BorderPane.setMargin(topBox, new Insets(0, 0, 10, 0));
            lobbyLayout.setTop(topBox);

            VBox playerBox = new VBox(10);
            playerBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
            Label playerLabel = new Label("You: " + playerName);
            playerStatusLabel = new Label("Status: Not ready");
            readyButton = new Button("Ready");
            playerBox.getChildren().addAll(playerLabel, playerStatusLabel, readyButton);

            VBox opponentBox = new VBox(10);
            opponentBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
            opponentLabel = new Label("Enemy: -");
            opponentStatusLabel = new Label("Status: -");
            opponentBox.getChildren().addAll(opponentLabel, opponentStatusLabel);

            lobbyLayout.setLeft(playerBox);
            lobbyLayout.setRight(opponentBox);

            readyButton.setOnAction(e -> protocolHandler.markReady());
            protocolHandler.requestOpponentInfo();

            return new Scene(lobbyLayout, 500, 300);
        }

        void onRoomLeave() {
            opponentLabel = null;
            opponentStatusLabel = null;
            playerStatusLabel = null;
            readyButton = null;
            protocolHandler.requestRooms();
        }

        /**
         * Called by confirmation handler when server acknowledges that current player is ready.
         */
        void onPlayerReadyConfirmed() {
            if (readyButton != null) {
                readyButton.setDisable(true);
            }
            if (playerStatusLabel != null) {
                playerStatusLabel.setText("Status: Ready");
            }
        }

        /**
         * Handles server notification with opponent information.
         *
         * @param event event with command {@code OPPONENT_INFO}.
         */
        void handleOpponentInfo(ServerEvent event) {
            String opponentName = event.getPart(1);
            if (opponentLabel == null) {
                return;
            }

            if ("NONE".equals(opponentName)) {
                opponentLabel.setText("Enemy: -");
                opponentStatusLabel.setText("Status: -");
            } else {
                String status = event.getPart(2);
                opponentLabel.setText("Enemy: " + opponentName);
                opponentStatusLabel.setText("Status: " + ("READY".equals(status) ? "Ready" : "Not ready"));
            }
        }

        /**
         * Handles notification that another player joined the lobby.
         *
         * @param event event with command {@code PLAYER_JOINED}.
         */
        void handlePlayerJoined(ServerEvent event) {
            String opponentName = event.getPart(1);
            if (opponentLabel != null && playerProfile != null) {
                assert opponentName != null;
                if (!opponentName.equals(playerProfile.getName())) {
                    opponentLabel.setText("Enemy: " + opponentName);
                    opponentStatusLabel.setText("Status: Not ready");
                }
            }
        }

        /**
         * Handles notification that some player became ready.
         *
         * @param event event with command {@code PLAYER_READY}.
         */
        void handlePlayerReady(ServerEvent event) {
            String readyPlayer = event.getPart(1);
            if (playerProfile != null && Objects.equals(readyPlayer, playerProfile.getName())) {
                if (playerStatusLabel != null) {
                    playerStatusLabel.setText("Status: Ready");
                }
                if (readyButton != null) {
                    readyButton.setDisable(true);
                }
            } else if (opponentStatusLabel != null) {
                opponentStatusLabel.setText("Status: Ready");
            }
        }

        /**
         * Handles notification that some player became not ready.
         *
         * @param event event with command {@code PLAYER_UNREADY}.
         */
        void handlePlayerUnready(ServerEvent event) {
            String unreadyPlayer = event.getPart(1);
            if (playerProfile != null && Objects.equals(unreadyPlayer, playerProfile.getName())) {
                if (playerStatusLabel != null) {
                    playerStatusLabel.setText("Status: Not ready");
                }
                if (readyButton != null) {
                    readyButton.setDisable(false);
                }
            } else if (opponentStatusLabel != null) {
                opponentStatusLabel.setText("Status: Not ready");
            }
        }

        /**
         * Handles notification that opponent left the room.
         *
         * @param event event with command {@code PLAYER_LEFT}.
         */
        void handlePlayerLeft(ServerEvent event) {
            if (opponentLabel != null) {
                opponentLabel.setText("Enemy: -");
                opponentStatusLabel.setText("Status: -");
            }
        }

        /**
         * Updates opponent block based on reconnection state.
         *
         * @param opponentName   nickname of opponent.
         * @param opponentStatus opponent status string like READY/NOT_READY.
         */
        void updateOpponentInfo(String opponentName, String opponentStatus) {
            if (opponentLabel != null) {
                opponentLabel.setText("Enemy: " + opponentName);
            }
            if (opponentStatusLabel != null) {
                opponentStatusLabel.setText("Status: " + ("READY".equals(opponentStatus) ? "Ready" : "Not ready"));
            }
        }
    }

    /**
     * Encapsulates the game scene, score, timer and move buttons.
     */
    private final class GameUi {

        private Label playerScoreLabel;
        private Label opponentScoreLabel;
        private Label timerLabel;
        private Label gameStatusLabel;
        private Label resultLabel;
        private Button rockButton;
        private Button paperButton;
        private Button scissorsButton;
        private Timeline gameTimer;
        private int remainingTime;

        /**
         * Builds a new game scene and initializes all UI elements for a match.
         *
         * @return {@link Scene} representing game view.
         */
        Scene buildGameScene() {
            BorderPane gameLayout = new BorderPane();
            gameLayout.setStyle("-fx-padding: 20;");

            VBox topContainer = new VBox(10);
            topContainer.setAlignment(Pos.CENTER);

            // общий лейбл
            topContainer.getChildren().add(globalConnectionStatusLabel);

            HBox scoreBox = new HBox(50);
            scoreBox.setAlignment(Pos.CENTER);
            scoreBox.setStyle("-fx-padding: 10;");

            playerScoreLabel = new Label("You: 0");
            playerScoreLabel.setStyle("-fx-font-size: 20; -fx-font-weight: bold;");

            opponentScoreLabel = new Label("Opponent: 0");
            opponentScoreLabel.setStyle("-fx-font-size: 20; -fx-font-weight: bold;");

            scoreBox.getChildren().addAll(playerScoreLabel, opponentScoreLabel);

            timerLabel = new Label("Time: 30");
            timerLabel.setStyle("-fx-font-size: 18; -fx-padding: 10;");
            timerLabel.setAlignment(Pos.CENTER);

            topContainer.getChildren().addAll(scoreBox, timerLabel);
            gameLayout.setTop(topContainer);

            VBox labelContainer = new VBox(10);
            labelContainer.setAlignment(Pos.CENTER);
            labelContainer.setStyle("-fx-padding: 20; -fx-font-size: 16");

            gameStatusLabel = new Label("Waiting for round to start...");
            gameStatusLabel.setAlignment(Pos.CENTER);
            gameStatusLabel.setWrapText(true);

            resultLabel = new Label("");
            resultLabel.setAlignment(Pos.CENTER);
            resultLabel.setWrapText(true);

            labelContainer.getChildren().addAll(gameStatusLabel, resultLabel);
            gameLayout.setCenter(labelContainer);

            HBox buttonBox = new HBox(20);
            buttonBox.setAlignment(Pos.CENTER);
            buttonBox.setStyle("-fx-padding: 20;");

            rockButton = new Button("Rock");
            rockButton.setStyle("-fx-font-size: 16; -fx-min-width: 100; -fx-min-height: 50;");
            rockButton.setOnAction(e -> makeMove("R"));

            paperButton = new Button("Paper");
            paperButton.setStyle("-fx-font-size: 16; -fx-min-width: 100; -fx-min-height: 50;");
            paperButton.setOnAction(e -> makeMove("P"));

            scissorsButton = new Button("Scissors");
            scissorsButton.setStyle("-fx-font-size: 16; -fx-min-width: 100; -fx-min-height: 50;");
            scissorsButton.setOnAction(e -> makeMove("S"));

            buttonBox.getChildren().addAll(rockButton, paperButton, scissorsButton);
            gameLayout.setBottom(buttonBox);

            disableMoveButtons();
            return new Scene(gameLayout, 600, 500);
        }

        /**
         * Shows the game scene in primary stage and initializes it when server sends GAME_START.
         *
         * @param event server event with command {@code GAME_START}.
         */
        void showGameScene(ServerEvent event) {
            primaryStage.setScene(buildGameScene());
            updateConnectionStatus(isConnected);
        }

        /**
         * Handles start of a game round.
         *
         * @param event event with command {@code ROUND_START}.
         */
        void handleRoundStart(ServerEvent event) {
            int roundNumber = Integer.parseInt(event.getPart(1));
            Platform.runLater(() -> {
                enableMoveButtons();
                setGameStatusText("Round " + roundNumber + " - Make your move!");
                startTimer(10);
            });
        }

        /**
         * Handles result of a round and updates scores and summary text.
         *
         * @param event event with command {@code ROUND_RESULT}.
         */
        void handleRoundResult(ServerEvent event) {
            String winner = event.getPart(1);
            char movePlayers = event.getPart(2).charAt(0);
            char moveOpponents = event.getPart(3).charAt(0);
            int scorePlayers = Integer.parseInt(event.getPart(4));
            int scoreOpponents = Integer.parseInt(event.getPart(5));

            Platform.runLater(() -> {
                stopTimer();
                updateScores(scorePlayers, scoreOpponents);

                String moveStr1 = getMoveString(movePlayers);
                String moveStr2 = getMoveString(moveOpponents);

                String resultText;
                if ("DRAW".equals(winner)) {
                    resultText = "Draw! You: " + moveStr1 + " vs " + moveStr2;
                } else if ("TIMEOUT".equals(winner)) {
                    resultText = "Timeout! You: " + moveStr1 + " vs " + moveStr2;
                } else if (playerProfile != null && winner.equals(playerProfile.getName())) {
                    resultText = "You win! You: " + moveStr1 + " vs " + moveStr2;
                } else {
                    resultText = "You lose! You: " + moveStr1 + " vs " + moveStr2;
                }

                setResultText(resultText);
            });
        }

        /**
         * Handles game end and returns user back to rooms list after showing message.
         *
         * @param event event with command {@code GAME_END}.
         */
        void handleGameEnd(ServerEvent event) {
            String winner = event.getPart(1);
            Platform.runLater(() -> {
                stopTimer();
                if ("opponent_left".equals(winner)) {
                    disableMoveButtons();
                    setGameStatusText("Game ended - opponent left the game.");
                    showAlert("Game Ended", "Opponent has left the game. You win by default!");
                } else {
                    setGameStatusText("Game ended!");
                    String message = (playerProfile != null && winner.equals(playerProfile.getName()))
                            ? "Congratulations! You won the game!"
                            : "Game Over! " + winner + " won!";
                    showAlert("Game Finished", message);
                }
                protocolHandler.requestRooms();
            });
        }

        /**
         * Sends a move to server and disables UI buttons to prevent double send.
         *
         * @param move move code: "R", "P" or "S".
         */
        private void makeMove(String move) {
            protocolHandler.sendMove(move);
            disableMoveButtons();
        }

        /**
         * Enables move buttons if they exist.
         */
        void enableMoveButtons() {
            if (rockButton != null) {
                rockButton.setDisable(false);
            }
            if (paperButton != null) {
                paperButton.setDisable(false);
            }
            if (scissorsButton != null) {
                scissorsButton.setDisable(false);
            }
        }

        /**
         * Disables move buttons if they exist.
         */
        void disableMoveButtons() {
            if (rockButton != null) {
                rockButton.setDisable(true);
            }
            if (paperButton != null) {
                paperButton.setDisable(true);
            }
            if (scissorsButton != null) {
                scissorsButton.setDisable(true);
            }
        }

        /**
         * Updates both player and opponent score labels.
         *
         * @param playerScore   new score for local player.
         * @param opponentScore new score for opponent.
         */
        void updateScores(int playerScore, int opponentScore) {
            if (playerScoreLabel != null) {
                playerScoreLabel.setText("You: " + playerScore);
            }
            if (opponentScoreLabel != null) {
                opponentScoreLabel.setText("Opponent: " + opponentScore);
            }
        }

        /**
         * Starts countdown timer for a given number of seconds.
         *
         * @param seconds countdown length in seconds.
         */
        void startTimer(int seconds) {
            stopTimer();
            remainingTime = seconds;
            if (timerLabel != null) {
                timerLabel.setText("Time: " + remainingTime);
            }

            gameTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                remainingTime--;
                if (timerLabel != null) {
                    timerLabel.setText("Time: " + remainingTime);
                }
                if (remainingTime <= 0) {
                    stopTimer();
                }
            }));
            gameTimer.setCycleCount(Timeline.INDEFINITE);
            gameTimer.play();
        }

        /**
         * Stops running game timer if present.
         */
        void stopTimer() {
            if (gameTimer != null) {
                gameTimer.stop();
            }
        }

        /**
         * Creates human readable label for a move character.
         *
         * @param move single character code 'R','P','S','X'.
         * @return move name string.
         */
        private String getMoveString(char move) {
            return switch (move) {
                case 'R' -> "Rock";
                case 'P' -> "Paper";
                case 'S' -> "Scissors";
                case 'X' -> "None";
                default -> "Unknown";
            };
        }

        /**
         * Sets the text in the result label.
         *
         * @param text descriptive result string.
         */
        void setResultText(String text) {
            if (resultLabel != null) {
                if (text.contains("win")) {
                    resultLabel.setStyle("-fx-text-fill: green; -fx-font-size: 16;");
                } else if (text.contains("lose")) {
                    resultLabel.setStyle("-fx-text-fill: red; -fx-font-size: 16;");
                } else {
                    resultLabel.setStyle("-fx-text-fill: black; -fx-font-size: 16;");
                }
                resultLabel.setText(text);
            }
        }

        void setGameStatusText(String text) {
            if (gameStatusLabel != null) {
                gameStatusLabel.setText(text);
            }
        }
    }
}
