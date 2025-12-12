package com.rps.network;

import javafx.application.Platform;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ReconnectionManager {
    private final NetworkManager networkManager;
    private final ProtocolHandler protocolHandler;
    private final EventBus eventBus;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> reconnectionTask;
    private volatile boolean isReconnecting = false;
    private volatile boolean reconnectionSuccess = false;
    private int reconnectAttempts = 0;
    private static final int MAX_AUTO_RECONNECT_SECONDS = 15;
    private static final int RECONNECT_INTERVAL_MS = 2000;

    private Runnable onAutoReconnectFailed;
    private Consumer<String> onReconnectSuccess;
    private String lastToken;
    private String host;
    private int port;

    public ReconnectionManager(NetworkManager networkManager, ProtocolHandler protocolHandler, EventBus eventBus) {
        this.networkManager = networkManager;
        this.protocolHandler = protocolHandler;
        this.eventBus = eventBus;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        // Успешное переподключение
        eventBus.subscribe("RECONNECT_OK", event -> {
            reconnectionSuccess = true;
            stopReconnecting();

            String state = event.getPart(1);
            Platform.runLater(() -> {
                if (onReconnectSuccess != null) {
                    if ("GAME".equals(state) && event.getPartsCount() >= 5) {
                        String gameInfo = event.getPart(2) + " " + event.getPart(3) + " " + event.getPart(4);
                        onReconnectSuccess.accept("GAME " + gameInfo);
                    } else if ("LOBBY".equals(state) && event.getPartsCount() >= 3) {
                        // Формат: RECONNECT_OK LOBBY opponent_nick status
                        String opponentNick = event.getPart(2);
                        String opponentStatus = event.getPartsCount() >= 4 ? event.getPart(3) : "NOT_READY";
                        onReconnectSuccess.accept("LOBBY " + opponentNick + " " + opponentStatus);
                    } else if ("LOBBY".equals(state)) {
                        onReconnectSuccess.accept("LOBBY NONE");
                    } else {
                        onReconnectSuccess.accept(state);
                    }
                }
            });
        });

        // Неверный токен
        eventBus.subscribe("ERR", event -> {
            String errorCode = event.getPart(1);
            String errorMsg = event.getPartsCount() > 2 ? event.getPart(2) : "";

            if (isReconnecting && "INVALID_TOKEN".equals(errorMsg)) {
                stopReconnecting();
                Platform.runLater(() -> {
                    if (onAutoReconnectFailed != null) {
                        onAutoReconnectFailed.run();
                    }
                });
            }
        });
    }

    public void startAutoReconnect(String token) {
        if (isReconnecting) return;

        this.lastToken = token;
        this.isReconnecting = true;
        this.reconnectionSuccess = false;
        this.reconnectAttempts = 0;

        scheduler = Executors.newSingleThreadScheduledExecutor();

        reconnectionTask = scheduler.scheduleAtFixedRate(() -> {
            if (reconnectionSuccess) {
                stopReconnecting();
                return;
            }

            long elapsedSeconds = (reconnectAttempts * RECONNECT_INTERVAL_MS) / 1000;
            if (elapsedSeconds >= MAX_AUTO_RECONNECT_SECONDS) {
                stopReconnecting();
                Platform.runLater(() -> {
                    if (onAutoReconnectFailed != null) {
                        onAutoReconnectFailed.run();
                    }
                });
                return;
            }

            attemptReconnect();
            reconnectAttempts++;

        }, 0, RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void attemptReconnect() {
        try {
            System.out.println("Attempting reconnection to " + host + ":" + port + "... (attempt " + (reconnectAttempts + 1) + ")");
            networkManager.connect(host, port);
            protocolHandler.sendReconnect(lastToken);
        } catch (Exception e) {
            System.out.println("Reconnection attempt failed: " + e.getMessage());
        }
    }

    public void manualReconnect(String token) {
        this.lastToken = token;
        attemptReconnect();
    }

    private void stopReconnecting() {
        isReconnecting = false;
        if (reconnectionTask != null) {
            reconnectionTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void setOnAutoReconnectFailed(Runnable handler) {
        this.onAutoReconnectFailed = handler;
    }

    public void setOnReconnectSuccess(Consumer<String> handler) {
        this.onReconnectSuccess = handler;
    }

    public void setConnectionInfo(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public boolean isReconnecting() {
        return isReconnecting;
    }
}
