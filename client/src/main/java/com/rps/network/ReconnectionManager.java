package com.rps.network;

import javafx.application.Platform;

import java.io.IOException;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ReconnectionManager {
    private static final int MAX_ATTEMPTS = 6;
    private static final int ATTEMPT_INTERVAL_MS = 1000;

    private final NetworkManager networkManager;
    private final ProtocolHandler protocolHandler;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(named("reconnect-manager"));

    private ScheduledFuture<?> autoTask;
    private Runnable onAutoReconnectFailed;
    private Consumer<String> onReconnectSuccess;
    private String host;
    private int port;
    private String token;
    private volatile boolean autoReconnectActive;
    private volatile int attemptCounter;

    public ReconnectionManager(NetworkManager networkManager,
                               ProtocolHandler protocolHandler,
                               EventBus eventBus) {
        this.networkManager = networkManager;
        this.protocolHandler = protocolHandler;
        this.eventBus = eventBus;
        wireEvents();
    }

    public void setConnectionInfo(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setOnAutoReconnectFailed(Runnable handler) {
        this.onAutoReconnectFailed = handler;
    }

    public void setOnReconnectSuccess(Consumer<String> handler) {
        this.onReconnectSuccess = handler;
    }

    public void startAutoReconnect(String token) {
        if (autoReconnectActive || host == null) {
            return;
        }
        this.token = token;
        autoReconnectActive = true;
        attemptCounter = 0;
        autoTask = scheduler.scheduleAtFixedRate(this::attemptReconnect,
                0, ATTEMPT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void manualReconnect(String token) {
        this.token = token;
        attemptReconnect();
    }

    public boolean isReconnecting() {
        return autoReconnectActive;
    }

    private void attemptReconnect() {
        if (token == null || host == null) {
            stopAutoReconnect(false);
            return;
        }
        if (autoReconnectActive && attemptCounter >= MAX_ATTEMPTS) {
            stopAutoReconnect(false);
            return;
        }
        attemptCounter++;
        try {
            System.out.println("Reconnect attempt " + attemptCounter + "/" + MAX_ATTEMPTS);
            networkManager.connect(host, port);
            protocolHandler.sendReconnect(token);
        } catch (IOException e) {
            System.out.println("Reconnection attempt failed: " + e.getMessage());
        }
    }

    private void wireEvents() {
        eventBus.subscribe("RECONNECT_OK", event -> {
            String state = buildStatePayload(event);
            stopAutoReconnect(true);
            Consumer<String> handler = onReconnectSuccess;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(state));
            }
        });

        eventBus.subscribe("ERR", event -> {
            if (!autoReconnectActive) {
                return;
            }
            String errorMsg = event.getPartsCount() > 2 ? event.getPart(2) : "";
            if ("INVALID_TOKEN".equals(errorMsg)) {
                stopAutoReconnect(false);
            }
        });
    }

    private String buildStatePayload(ServerEvent event) {
        if (event.getPartsCount() < 2) {
            return "UNKNOWN";
        }
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add(event.getPart(1));
        for (int i = 2; i < event.getPartsCount(); i++) {
            joiner.add(event.getPart(i));
        }
        return joiner.toString();
    }

    private void stopAutoReconnect(boolean success) {
        if (autoTask != null) {
            autoTask.cancel(true);
            autoTask = null;
        }
        boolean shouldNotifyFailure = autoReconnectActive && !success;
        autoReconnectActive = false;
        attemptCounter = 0;
        if (shouldNotifyFailure && onAutoReconnectFailed != null) {
            Platform.runLater(onAutoReconnectFailed);
        }
    }

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
