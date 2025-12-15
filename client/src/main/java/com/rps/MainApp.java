package com.rps;

import com.rps.network.*;
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
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class MainApp extends Application {

    private NetworkManager networkManager;
    private ProtocolHandler protocolHandler;
    private EventBus eventBus;
    private ReconnectionManager reconnectionManager;

    private Stage primaryStage;
    private Button connectButton;
    private Label statusLabel;
    private Button listRoomsButton;
    private TextField nameField;
    private TextField hostField;
    private TextField portField;
    private PlayerProfile playerProfile;

    private Label opponentLabel;
    private Label opponentStatusLabel;
    private Label playerStatusLabel;
    private Button readyButton;

    // Game scene elements
    private Label playerScoreLabel;
    private Label opponentScoreLabel;
    private Label timerLabel;
    private Label resultLabel;
    private Button rockButton;
    private Button paperButton;
    private Button scissorsButton;
    private Timeline gameTimer;
    private int remainingTime;

    private String currentHost = "0.0.0.0";
    private int currentPort = 2500;

    private Label connectionStatusLabel;
    private boolean isConnected = false;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Инициализируем компоненты
        networkManager = new NetworkManager();
        eventBus = EventBus.createJavaFxBus();
        protocolHandler = new ProtocolHandler(networkManager, eventBus);
        reconnectionManager = new ReconnectionManager(networkManager, protocolHandler, eventBus);

        // Настраиваем обработчики переподключения
        setupReconnectionHandlers();

        // Настраиваем подписки на события ПЕРЕД показом UI
        setupEventHandlers();

        // ==================== UI SETUP ====================
        VBox loginLayout = new VBox(10);
        loginLayout.setStyle("-fx-padding: 20;");
        loginLayout.setAlignment(Pos.CENTER);

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

        loginLayout.getChildren().addAll(
            titleLabel,
            new Label("Name:"), nameField,
            new Label("Server IP:"), hostField,
            new Label("Port:"), portField,
            connectButton,
            statusLabel,
            listRoomsButton
        );

        Scene loginScene = new Scene(loginLayout, 350, 400);
        stage.setTitle("RPS Client");
        stage.setScene(loginScene);
        stage.show();

        // ==================== BUTTON ACTIONS ====================
        connectButton.setOnAction(e -> {
            connectToServer();
        });
        listRoomsButton.setOnAction(e -> protocolHandler.requestRooms());
    }

    private void setupReconnectionHandlers() {
        networkManager.setOnDisconnected(() -> {
            Platform.runLater(() -> updateConnectionStatus(false));

            if (playerProfile != null && playerProfile.getToken() != null) {
                System.out.println("Connection lost! Starting auto-reconnect...");
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
                showManualReconnectScene();
            });
        });

        // Если автоматическое переподключение не удалось
        reconnectionManager.setOnAutoReconnectFailed(() -> {
            Platform.runLater(this::showManualReconnectScene);
        });

        // При успешном переподключении
        reconnectionManager.setOnReconnectSuccess(state -> {
            Platform.runLater(() -> {
                updateConnectionStatus(true);

                if (state.startsWith("GAME")) {
                    // Парсим игровое состояние: "GAME score1 score2 round"
                    String[] parts = state.split(" ");
                    if (parts.length >= 4) {
                        int score1 = Integer.parseInt(parts[1]);
                        int score2 = Integer.parseInt(parts[2]);
                        int round = Integer.parseInt(parts[3]);
                        char performedMove = parts.length >=5 ? parts[4].charAt(0) : 'X';

                        showGameScene(null);
                        updateScores(score1, score2);
                        if (performedMove != 'X') {
                            disableMoveButtons();
                            resultLabel.setText("Waiting for opponent...");
                        } else {
                            enableMoveButtons();
                            resultLabel.setText("Reconnected! Round " + round + " - Make your move!");
                            startTimer(10);
                        }
                    }
                } else if (state.startsWith("LOBBY")) {
                    // Парсим: "LOBBY opponent_nick status" или "LOBBY NONE"
                    String[] parts = state.split(" ");
                    if (playerProfile != null) {
                        showLobbyScene("reconnected", playerProfile.getName());

                        // Обновляем информацию об оппоненте
                        if (parts.length >= 2 && !"NONE".equals(parts[1])) {
                            String opponentNick = parts[1];
                            String opponentStatus = parts.length >= 3 ? parts[2] : "NOT_READY";

                            if (opponentLabel != null) {
                                opponentLabel.setText("Enemy: " + opponentNick);
                                opponentStatusLabel.setText("Status: " +
                                    ("READY".equals(opponentStatus) ? "Ready" : "Not ready"));
                            }
                        }
                    }
                } else {
                    // Просто подключены
                    protocolHandler.requestRooms();
                }
                showAlert("Reconnected", "Connection restored!");
            });
        });

        reconnectionManager.setConnectionInfo(currentHost, currentPort);
    }

    private void setupEventHandlers() {
        // ========== Логирование всех событий (опционально) ==========
        eventBus.subscribeAll(event -> {
//            System.out.println("📨 Event: " + event.getCommand());
        });

        // ========== WELCOME - успешное подключение ==========
        eventBus.subscribe("WELCOME", event -> {
            String token = event.getPart(1);
            playerProfile = new PlayerProfile(nameField.getText());
            playerProfile.setToken(token);
            playerProfile.setStatus(PlayerProfile.PlayerStatus.CONNECTED);

            statusLabel.setText("Connected as " + token);
            connectButton.setDisable(true);
            listRoomsButton.setDisable(false);

            updateConnectionStatus(true);
        });

        // ========== ROOMS_LOADED - список комнат загружен ==========
        eventBus.subscribe("ROOMS_LOADED", event -> {
            String roomsData = event.getPart(1);
            List<String> roomList = roomsData != null && !roomsData.isEmpty()
                    ? List.of(roomsData.split("\\|"))
                    : new ArrayList<>();
            showRoomsScene(roomList);
        });

        // ========== ROOM_JOINED - успешное подключение к комнате ==========
        eventBus.subscribe("ROOM_JOINED", event -> {
            String roomId = event.getPart(1);
            playerProfile.setStatus(PlayerProfile.PlayerStatus.IN_LOBBY);
            showLobbyScene(roomId, playerProfile.getName());
        });

        // ========== Обработка ошибок ==========
        eventBus.subscribe("ERR", event -> {
            String errorCode = event.getPart(1);
            String errorMsg = event.getPartsCount() > 2 ? event.getPart(2) : "Unknown error";
            if (errorCode.equals("107")) {
                networkManager.disconnect();
            }
            showAlert("Error", "Error " + errorCode + ": " + errorMsg);
        });

        // ========== Обработка подтверждений ==========
        eventBus.subscribe("OK", this::handleConfirmation);

        // ========== События лобби (глобальные подписки) ==========

        // Информация о противнике
        eventBus.subscribe("OPPONENT_INFO", this::handleOpponentInfo);

        // Присоединился игрок
        eventBus.subscribe("PLAYER_JOINED", this::handlePlayerJoined);

        // Игрок готов
        eventBus.subscribe("PLAYER_READY", this::handlePlayerReady);

        // Игрок не готов
        eventBus.subscribe("PLAYER_UNREADY", this::handlePlayerUnready);

        // Игрок покинул комнату
        eventBus.subscribe("PLAYER_LEFT", this::handlePlayerLeft);

        // Начало игры
        eventBus.subscribe("GAME_START", this::showGameScene);

        // ========== События игры ==========

        // Начало раунда
        eventBus.subscribe("ROUND_START", this::handleRoundStart);

        // Результат раунда
        eventBus.subscribe("ROUND_RESULT", this::handleRoundResult);

        // Конец игры
        eventBus.subscribe("GAME_END", this::handleGameEnd);

        // Пауза игры
        eventBus.subscribe("GAME_PAUSED", event -> {
            Platform.runLater(() -> {
                stopTimer();
                disableMoveButtons();
                resultLabel.setText("Game paused - opponent disconnected");
                showAlert("Game Paused", "Opponent has disconnected. Waiting for reconnection...");
            });
        });

        // Возобновление игры
        eventBus.subscribe("GAME_RESUMED", event -> {
            int roundNumber = Integer.parseInt(event.getPart(1));
            int score1 = Integer.parseInt(event.getPart(2));
            int score2 = Integer.parseInt(event.getPart(3));
            char performedMove = event.getPart(4).charAt(0);

            Platform.runLater(() -> {
                updateScores(score1, score2);
                if (performedMove == '\0') {
                    enableMoveButtons();
                    resultLabel.setText("Game resumed - Make your move!");
                } else {
                    disableMoveButtons();
                    resultLabel.setText("Game resumed - Waiting for opponent...");
                }
                startTimer(10);
                showAlert("Game Resumed", "Continue playing!");
            });
        });

        // Ход принят
        eventBus.subscribe("MOVE_ACCEPTED", event -> {
            Platform.runLater(() -> {
                disableMoveButtons();
                resultLabel.setText("Waiting for opponent...");
            });
        });
    }

    private void handleConfirmation(ServerEvent event) {
        String confirmedCommand = event.getPart(1);
        switch (confirmedCommand) {
            case "you_are_ready" -> {
                if (readyButton != null) readyButton.setDisable(true);
                if (playerStatusLabel != null) playerStatusLabel.setText("Status: Ready");
            }
        }
    }


    // ========== Обработчики событий лобби ==========

    private void handleOpponentInfo(ServerEvent event) {
        String opponentName = event.getPart(1);
        if (opponentLabel == null) return;

        if ("NONE".equals(opponentName)) {
            opponentLabel.setText("Enemy: -");
            opponentStatusLabel.setText("Status: -");
        } else {
            String status = event.getPart(2);
            opponentLabel.setText("Enemy: " + opponentName);
            opponentStatusLabel.setText("Status: " + ("READY".equals(status) ? "Ready" : "Not ready"));
        }
    }

    private void handlePlayerJoined(ServerEvent event) {
        String opponentName = event.getPart(1);
        if (opponentLabel != null && !opponentName.equals(playerProfile.getName())) {
            opponentLabel.setText("Enemy: " + opponentName);
            opponentStatusLabel.setText("Status: Not ready");
        }
    }

    private void handlePlayerReady(ServerEvent event) {
        String readyPlayer = event.getPart(1);
        if (readyPlayer.equals(playerProfile.getName())) {
            if (playerStatusLabel != null) playerStatusLabel.setText("Status: Ready");
            if (readyButton != null) readyButton.setDisable(true);
        } else {
            if (opponentStatusLabel != null) opponentStatusLabel.setText("Status: Ready");
        }
    }

    private void handlePlayerUnready(ServerEvent event) {
        String unreadyPlayer = event.getPart(1);
        if (unreadyPlayer.equals(playerProfile.getName())) {
            if (playerStatusLabel != null) playerStatusLabel.setText("Status: Not ready");
            if (readyButton != null) readyButton.setDisable(false);
        } else {
            if (opponentStatusLabel != null) opponentStatusLabel.setText("Status: Not ready");
        }
    }

    private void handlePlayerLeft(ServerEvent event) {
        if (opponentLabel != null) {
            opponentLabel.setText("Enemy: -");
            opponentStatusLabel.setText("Status: -");
        }
    }

    private void connectToServer() {
        String nickname = nameField.getText().trim();
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

        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            showAlert("Error", "Enter server IP!");
            return;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            showAlert("Error", "Invalid IP address format!");
            return;
        }
        for (String part : parts) {
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
            port = Integer.parseInt(portField.getText().trim());
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

    private void showRoomsScene(List<String> roomsRaw) {
        VBox roomsLayout = new VBox(10);
        roomsLayout.setStyle("-fx-padding: 20;");

        // Connection status at the top
        connectionStatusLabel = createConnectionStatusLabel();

        Label title = new Label("List of rooms:");

        // Преобразуем строки в объекты GameRoom
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
                    if (item.getStatus().equals("OPEN")) {
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
        roomsLayout.getChildren().addAll(connectionStatusLabel, title, listView, buttons);

        Scene roomsScene = new Scene(roomsLayout, 400, 400);
        primaryStage.setScene(roomsScene);

        updateConnectionStatus(isConnected);
    }

    private void showCreateRoomDialog() {
        Stage dialog = new Stage();
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

    private void showLobbyScene(String roomId, String playerName) {
        BorderPane lobbyLayout = new BorderPane();
        lobbyLayout.setStyle("-fx-padding: 20;");

        // ======= Connection status and back button =======
        VBox topBox = new VBox(5);
        connectionStatusLabel = createConnectionStatusLabel();

        Button backButton = new Button("Back");
        backButton.setOnAction(e -> {
            protocolHandler.leaveRoom();
            protocolHandler.requestRooms();
        });

        topBox.getChildren().addAll(connectionStatusLabel, backButton);
        lobbyLayout.setTop(topBox);
        BorderPane.setMargin(topBox, new Insets(0, 0, 10, 0));

        // ======= Слева: твой игрок =======
        VBox playerBox = new VBox(10);
        playerBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
        Label playerLabel = new Label("You: " + playerName);
        playerStatusLabel = new Label("Status: Not ready");
        readyButton = new Button("Ready");
        playerBox.getChildren().addAll(playerLabel, playerStatusLabel, readyButton);

        // ======= Справа: противник =======
        VBox opponentBox = new VBox(10);
        opponentBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
        opponentLabel = new Label("Enemy: -");
        opponentStatusLabel = new Label("Status: -");
        opponentBox.getChildren().addAll(opponentLabel, opponentStatusLabel);

        lobbyLayout.setLeft(playerBox);
        lobbyLayout.setRight(opponentBox);

        Scene lobbyScene = new Scene(lobbyLayout, 500, 300);
        primaryStage.setScene(lobbyScene);

        // ======= Кнопка готов =======
        readyButton.setOnAction(e -> {
            protocolHandler.markReady();
        });

        // ======= Запрашиваем информацию о противнике =======
        protocolHandler.requestOpponentInfo();

        updateConnectionStatus(isConnected);
    }

    // ========== Game scene handlers ==========

    private void handleRoundStart(ServerEvent event) {
        int roundNumber = Integer.parseInt(event.getPart(1));
        Platform.runLater(() -> {
            enableMoveButtons();
            resultLabel.setText("Round " + roundNumber + " - Make your move!");
            startTimer(10);
        });
    }

    private void handleRoundResult(ServerEvent event) {
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
            } else if (winner.equals(playerProfile.getName())) {
                resultText = "You win! You: " + moveStr1 + " vs " + moveStr2;
            } else {
                resultText = "You lose! You: " + moveStr1 + " vs " + moveStr2;
            }

            resultLabel.setText(resultText);
        });
    }

    private void handleGameEnd(ServerEvent event) {
        String winner = event.getPart(1);
        Platform.runLater(() -> {
            stopTimer();
            // TODO: здесь нужно расширить обработку. GAME_END opponent_left
            if (winner.equals("opponent_left")) {
                disableMoveButtons();
                resultLabel.setText("Game ended - opponent left the game.");
                showAlert("Game Ended", "Opponent has left the game. You win by default!");
                protocolHandler.requestRooms();
            } else {
                String message = winner.equals(playerProfile.getName())
                        ? "Congratulations! You won the game!"
                        : "Game Over! " + winner + " won!";

                showAlert("Game Finished", message);
                protocolHandler.requestRooms();
            }
        });
    }

    private void showGameScene(ServerEvent event) {
        BorderPane gameLayout = new BorderPane();
        gameLayout.setStyle("-fx-padding: 20;");

        // Top: Connection status, Score display and disconnect button
        VBox topContainer = new VBox(10);
        topContainer.setAlignment(Pos.CENTER);

        connectionStatusLabel = createConnectionStatusLabel();

        HBox scoreBox = new HBox(50);
        scoreBox.setAlignment(Pos.CENTER);
        scoreBox.setStyle("-fx-padding: 10;");

        playerScoreLabel = new Label("You: 0");
        playerScoreLabel.setStyle("-fx-font-size: 20; -fx-font-weight: bold;");

        opponentScoreLabel = new Label("Opponent: 0");
        opponentScoreLabel.setStyle("-fx-font-size: 20; -fx-font-weight: bold;");

        scoreBox.getChildren().addAll(playerScoreLabel, opponentScoreLabel);


        // Timer
        timerLabel = new Label("Time: 30");
        timerLabel.setStyle("-fx-font-size: 18; -fx-padding: 10;");
        timerLabel.setAlignment(Pos.CENTER);

        topContainer.getChildren().addAll(connectionStatusLabel, scoreBox, timerLabel);
        gameLayout.setTop(topContainer);

        // Center: Result display
        resultLabel = new Label("Waiting for round to start...");
        resultLabel.setStyle("-fx-font-size: 16; -fx-padding: 20;");
        resultLabel.setAlignment(Pos.CENTER);
        resultLabel.setWrapText(true);
        gameLayout.setCenter(resultLabel);

        // Bottom: Move buttons
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

        Scene gameScene = new Scene(gameLayout, 600, 500);
        primaryStage.setScene(gameScene);

        disableMoveButtons();
        updateConnectionStatus(isConnected);
    }

    private void makeMove(String move) {
        protocolHandler.sendMove(move);
        disableMoveButtons();
    }

    private void enableMoveButtons() {
        rockButton.setDisable(false);
        paperButton.setDisable(false);
        scissorsButton.setDisable(false);
    }

    private void disableMoveButtons() {
        rockButton.setDisable(true);
        paperButton.setDisable(true);
        scissorsButton.setDisable(true);
    }

    private void updateScores(int playerScore, int opponentScore) {
        playerScoreLabel.setText("You: " + playerScore);
        opponentScoreLabel.setText("Opponent: " + opponentScore);
    }

    private void startTimer(int seconds) {
        stopTimer();
        remainingTime = seconds;
        timerLabel.setText("Time: " + remainingTime);

        gameTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingTime--;
            timerLabel.setText("Time: " + remainingTime);

            if (remainingTime <= 0) {
                stopTimer();
            }
        }));
        gameTimer.setCycleCount(Timeline.INDEFINITE);
        gameTimer.play();
    }

    private void stopTimer() {
        if (gameTimer != null) {
            gameTimer.stop();
        }
    }

    private String getMoveString(char move) {
        switch (move) {
            case 'R': return "Rock";
            case 'P': return "Paper";
            case 'S': return "Scissors";
            case 'X': return "None";
            default: return "Unknown";
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showManualReconnectScene() {
        VBox layout = new VBox(20);
        layout.setAlignment(Pos.CENTER);
        layout.setStyle("-fx-padding: 40;");

        connectionStatusLabel = createConnectionStatusLabel();
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
        resetButton.setOnAction(e -> {
            playerProfile = null;
            nameField.clear();
            connectButton.setDisable(false);
            listRoomsButton.setDisable(true);
            statusLabel.setText("");

            VBox loginLayout = new VBox(10);
            loginLayout.setStyle("-fx-padding: 20;");
            loginLayout.setAlignment(Pos.CENTER);

            Label titleLabelLogin = new Label("Connect to Server");
            titleLabelLogin.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");

            loginLayout.getChildren().addAll(
                titleLabelLogin,
                new Label("Name:"), nameField,
                new Label("Server IP:"), hostField,
                new Label("Port:"), portField,
                statusLabel,
                listRoomsButton
            );

            Scene loginScene = new Scene(loginLayout, 350, 400);
            primaryStage.setScene(loginScene);
        });

        layout.getChildren().addAll(connectionStatusLabel, titleLabel, messageLabel, serverInfoLabel, resetButton);

        Scene scene = new Scene(layout, 400, 350);
        primaryStage.setScene(scene);
    }

    private Label createConnectionStatusLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5;");
        label.setAlignment(Pos.CENTER_RIGHT);
        return label;
    }

    private void updateConnectionStatus(boolean connected) {
        this.isConnected = connected;

        if (connectionStatusLabel != null) {
            Platform.runLater(() -> {
                if (connected) {
                    connectionStatusLabel.setText("● Connected");
                    connectionStatusLabel.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5; -fx-text-fill: green;");
                } else {
                    connectionStatusLabel.setText("● Connection lost, reconnecting...");
                    connectionStatusLabel.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 5; -fx-text-fill: red;");
                }
            });
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        stopTimer();
        eventBus.clear();
        networkManager.disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

