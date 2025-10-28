package com.rps;

import com.rps.network.EventBus;
import com.rps.network.NetworkManager;
import com.rps.network.ProtocolHandler;
import com.rps.network.ServerEvent;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public class MainApp extends Application {

    private NetworkManager networkManager;
    private ProtocolHandler protocolHandler;
    private EventBus eventBus;

    private Stage primaryStage;
    private Label statusLabel;
    private Button listRoomsButton;
    private TextField nameField;
    private PlayerProfile playerProfile;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Инициализируем компоненты
        networkManager = new NetworkManager();
        eventBus = new EventBus();
        protocolHandler = new ProtocolHandler(networkManager, eventBus);

        // Настраиваем подписки на события ПЕРЕД показом UI
        setupEventHandlers();

        // ==================== UI SETUP ====================
        VBox loginLayout = new VBox(10);
        loginLayout.setStyle("-fx-padding: 20;");

        nameField = new TextField();
        nameField.setPromptText("Введите имя");

        Button connectButton = new Button("Подключиться");

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

        listRoomsButton = new Button("Список комнат");
        listRoomsButton.setDisable(true);

        loginLayout.getChildren().addAll(nameField, connectButton, statusLabel, listRoomsButton);

        Scene loginScene = new Scene(loginLayout, 300, 200);
        stage.setTitle("RPS Client");
        stage.setScene(loginScene);
        stage.show();

        // ==================== BUTTON ACTIONS ====================
        connectButton.setOnAction(e -> {
            connectToServer();
        });
        listRoomsButton.setOnAction(e -> protocolHandler.requestRooms());
    }

    /**
     * Настройка всех обработчиков событий от сервера
     */
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

            statusLabel.setText("Подключено как " + token);
            listRoomsButton.setDisable(false);
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
            showAlert("Ошибка", "Error " + errorCode + ": " + errorMsg);
        });
    }

    private void connectToServer() {
        String nickname = nameField.getText().trim();
        if (nickname.isEmpty()) {
            showAlert("Ошибка", "Введите имя!");
            return;
        }

        try {
            networkManager.connect("0.0.0.0", 2500);
            protocolHandler.sendHello(nickname);
        } catch (Exception ex) {
            showAlert("Ошибка подключения", ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void showRoomsScene(List<String> roomsRaw) {
        VBox roomsLayout = new VBox(10);
        roomsLayout.setStyle("-fx-padding: 20;");

        Label title = new Label("Доступные комнаты:");

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
            private final Button joinButton = new Button("Подключиться");
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
                    hbox.getChildren().setAll(nameLabel, joinButton);
                    setGraphic(hbox);
                }
            }
        });

        Button createRoomButton = new Button("Создать комнату");
        createRoomButton.setOnAction(e -> showCreateRoomDialog());

        Button refreshButton = new Button("Обновить");
        refreshButton.setOnAction(e -> protocolHandler.requestRooms());

        HBox buttons = new HBox(10, createRoomButton, refreshButton);
        roomsLayout.getChildren().addAll(title, listView, buttons);

        Scene roomsScene = new Scene(roomsLayout, 400, 400);
        primaryStage.setScene(roomsScene);
    }

    private void showCreateRoomDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Создать комнату");

        VBox dialogLayout = new VBox(10);
        dialogLayout.setStyle("-fx-padding: 20;");

        Label prompt = new Label("Введите название комнаты:");
        TextField roomNameField = new TextField();

        Button cancelButton = new Button("Отмена");
        Button confirmButton = new Button("Подтвердить");

        cancelButton.setOnAction(e -> dialog.close());
        confirmButton.setOnAction(e -> {
            String roomName = roomNameField.getText().trim();
            if (!roomName.isEmpty()) {
                protocolHandler.createRoom(roomName);
                dialog.close();
            }
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

        // ======= Кнопка назад =======
        Button backButton = new Button("Назад");
        backButton.setOnAction(e -> {
            protocolHandler.leaveRoom();
            protocolHandler.requestRooms();
        });
        lobbyLayout.setTop(backButton);
        BorderPane.setMargin(backButton, new Insets(0, 0, 10, 0));

        // ======= Слева: твой игрок =======
        VBox playerBox = new VBox(10);
        playerBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
        Label playerLabel = new Label("Вы: " + playerName);
        Label playerStatusLabel = new Label("Статус: Не готов");
        Button readyButton = new Button("Готов");
        playerBox.getChildren().addAll(playerLabel, playerStatusLabel, readyButton);

        // ======= Справа: противник =======
        VBox opponentBox = new VBox(10);
        opponentBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
        Label opponentLabel = new Label("Противник: -");
        Label opponentStatusLabel = new Label("Статус: -");
        opponentBox.getChildren().addAll(opponentLabel, opponentStatusLabel);

        lobbyLayout.setLeft(playerBox);
        lobbyLayout.setRight(opponentBox);

        Scene lobbyScene = new Scene(lobbyLayout, 500, 300);
        primaryStage.setScene(lobbyScene);

        // ========== Подписки на события лобби ==========

        // Присоединился игрок
        eventBus.subscribe("PLAYER_JOINED", event -> {
            String opponentName = event.getPart(1);
            opponentLabel.setText("Противник: " + opponentName);
            opponentStatusLabel.setText("Статус: Не готов");
        });

        // Игрок готов
        eventBus.subscribe("PLAYER_READY", event -> {
            String readyPlayer = event.getPart(1);
            if (!readyPlayer.equals(playerName)) {
                opponentStatusLabel.setText("Статус: Готов");
            } else {
                playerStatusLabel.setText("Статус: Готов");
                readyButton.setDisable(true);
            }
        });

        // Игрок не готов
        eventBus.subscribe("PLAYER_UNREADY", event -> {
            String unreadyPlayer = event.getPart(1);
            if (!unreadyPlayer.equals(playerName)) {
                opponentStatusLabel.setText("Статус: Не готов");
            } else {
                playerStatusLabel.setText("Статус: Не готов");
                readyButton.setDisable(false);
            }
        });

        // Игрок покинул комнату
        eventBus.subscribe("PLAYER_LEFT", event -> {
            opponentLabel.setText("Противник: -");
            opponentStatusLabel.setText("Статус: -");
        });

        // Начало игры
        eventBus.subscribe("GAME_START", event -> {
            showAlert("Игра началась!", "Удачи!");
            // TODO: showGameScene();
        });

        // ======= Кнопка готов =======
        readyButton.setOnAction(e -> {
            protocolHandler.markReady();
        });
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        eventBus.clear();
        networkManager.disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}