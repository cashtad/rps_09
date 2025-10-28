package com.rps;

import com.rps.network.NetworkManager;
import com.rps.network.ProtocolHandler;
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

    private Stage primaryStage;
    private Label statusLabel;
    private Button listRoomsButton;
    private TextField nameField;
    private PlayerProfile playerProfile;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        networkManager = new NetworkManager();
        protocolHandler = new ProtocolHandler(networkManager);

        // ==================== UI SETUP ====================
        VBox loginLayout = new VBox(10);
        loginLayout.setStyle("-fx-padding: 20;");

        nameField = new TextField();
        nameField.setPromptText("Введите имя");

        Button connectButton = new Button("Подключиться");

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

        listRoomsButton = new Button("Список комнат");
        listRoomsButton.setDisable(true); // изначально неактивна

        loginLayout.getChildren().addAll(nameField, connectButton, statusLabel, listRoomsButton);

        Scene loginScene = new Scene(loginLayout, 300, 200);
        stage.setTitle("RPS Client");
        stage.setScene(loginScene);
        stage.show();

        // ==================== PROTOCOL HANDLER ====================
        protocolHandler.setOnWelcome(token -> {
            // Показываем зелёную надпись и активируем кнопку
            playerProfile = new PlayerProfile(nameField.getText());
            playerProfile.setToken(token);
            playerProfile.setStatus(PlayerProfile.PlayerStatus.CONNECTED);
            statusLabel.setText("Подключено как " + token);
            listRoomsButton.setDisable(false);
        });

        protocolHandler.setOnRoomList(roomList -> {
            // Открываем новую сцену со списком комнат
            Platform.runLater(() -> showRoomsScene(roomList));
        });

        protocolHandler.setOnRoomJoined(roomId -> {
            // Открываем новую сцену со списком комнат
            playerProfile.setStatus(PlayerProfile.PlayerStatus.IN_LOBBY);
            Platform.runLater(() -> showLobbyScene(roomId, playerProfile.getName()));
        });

        // ==================== BUTTON ACTIONS ====================
        connectButton.setOnAction(e -> connectToServer());
        listRoomsButton.setOnAction(e -> requestRoomList());
    }

    private void connectToServer() {
        String nickname = nameField.getText().trim();
        if (nickname.isEmpty()) return;

        try {
            networkManager.connect("0.0.0.0", 2500); // укажи свой хост и порт
            protocolHandler.sendHello(nickname);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void requestRoomList() {
        protocolHandler.requestRooms();
    }

    private void showRoomsScene(List<String> roomsRaw) {
        VBox roomsLayout = new VBox(10);
        roomsLayout.setStyle("-fx-padding: 20;");

        Label title = new Label("Доступные комнаты:");
        // Преобразуем строки ROOM <id> <name> в RoomItem
        List<GameRoom> roomItems = new ArrayList<>();
        for (String raw : roomsRaw) {
            String[] parts = raw.split(" ");
            if (parts.length >= 4) {
                roomItems.add(new GameRoom(Integer.parseInt(parts[0]), parts[1], Integer.parseInt(parts[2].split("/")[0]), parts[3])); // int id, String name, int currentPlayers, String status
            }
        }

        ListView<GameRoom> listView = new ListView<>();
        listView.getItems().addAll(roomItems);

        // Используем кастомные ячейки
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Button joinButton = new Button("Подключиться");
            private final HBox hbox = new HBox(10);

            {
                joinButton.setOnAction(e -> {
                    GameRoom item = getItem();
                    if (item != null) {
                        protocolHandler.joinRoom(String.valueOf(item.id)); // JOIN <room_id>

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

        // Кнопка создания новой комнаты
        Button createRoomButton = new Button("Создать комнату");
        createRoomButton.setOnAction(e -> showCreateRoomDialog());

        roomsLayout.getChildren().addAll(title, listView, createRoomButton);

        Scene roomsScene = new Scene(roomsLayout, 400, 400);
        primaryStage.setScene(roomsScene);
    }

    private void showCreateRoomDialog() {
        // Новое окно (Stage) как диалог
        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Создать комнату");

        VBox dialogLayout = new VBox(10);
        dialogLayout.setStyle("-fx-padding: 20;");

        Label prompt = new Label("Введите название комнаты:");
        TextField roomNameField = new TextField();

        // Кнопки
        Button cancelButton = new Button("Отмена");
        Button confirmButton = new Button("Подтвердить");

        cancelButton.setOnAction(e -> dialog.close()); // просто закрываем окно
        confirmButton.setOnAction(e -> {
            String roomName = roomNameField.getText().trim();
            if (!roomName.isEmpty()) {
                protocolHandler.createRoom(roomName); // отправляем CREATE <room_name>
                dialog.close();
            }
        });

        // Размещаем кнопки горизонтально
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
            // Возврат к сцене со списком комнат
            protocolHandler.requestRooms(); // обновим список комнат
        });
        lobbyLayout.setTop(backButton);
        BorderPane.setMargin(backButton, new Insets(0, 0, 10, 0));

        // ======= Слева: твой игрок =======
        VBox playerBox = new VBox(10);
        playerBox.setStyle("-fx-border-color: black; -fx-padding: 10;");
        Label playerLabel = new Label("Вы: " + playerName);
        Label playerStatusLabel = new Label("Статус: Не готов");
        Button readyButton = new Button("Готов");
        readyButton.setDisable(true); // пока неактивна
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

        // ======= Пример: добавляем обработку сообщений от сервера =======
        protocolHandler.setUiUpdater(msg -> {
            String[] parts = msg.split(" ");
            String cmd = parts[1];

            switch (cmd) {
                case "PLAYER_JOINED" -> {
                    String opponentName = parts[2];
                    opponentLabel.setText("Противник: " + opponentName);
                    opponentStatusLabel.setText("Статус: Не готов");
                }
                case "PLAYER_READY" -> {
                    String readyPlayer = parts[2];
                    if (!readyPlayer.equals(playerName)) {
                        opponentStatusLabel.setText("Статус: Готов");
                    } else {
                        playerStatusLabel.setText("Статус: Готов");
                        readyButton.setDisable(true);
                    }
                }
                case "PLAYER_UNREADY" -> {
                    String unreadyPlayer = parts[2];
                    if (!unreadyPlayer.equals(playerName)) {
                        opponentStatusLabel.setText("Статус: Не готов");
                    } else {
                        playerStatusLabel.setText("Статус: Не готов");
                        readyButton.setDisable(false);
                    }
                }
                case "GAME_START" -> {
                    // Здесь можно открывать сцену игры
                }
            }
        });

        // ======= Кнопка готов =======
        readyButton.setDisable(false); // активируем
        readyButton.setOnAction(e -> {
            protocolHandler.markReady(); // отправляем READY на сервер
            readyButton.setDisable(true);
            playerStatusLabel.setText("Статус: Готов");
        });
    }


    @Override
    public void stop() throws Exception {
        super.stop();
        networkManager.disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
