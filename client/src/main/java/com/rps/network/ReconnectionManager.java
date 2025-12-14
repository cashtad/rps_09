package com.rps.network;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReconnectionManager {
    private static final Logger LOG = Logger.getLogger(ReconnectionManager.class.getName());

    private final NetworkManager networkManager;
    private final ProtocolHandler protocolHandler;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final Executor callbackExecutor;
    private final Duration interval;
    private final Duration autoWindow;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicInteger attempts = new AtomicInteger();
    private final Object connectLock = new Object();

    private ScheduledFuture<?> autoTask;
    private Runnable onAutoReconnectFailed;
    private Consumer<String> onReconnectSuccess;
    private volatile String lastToken;
    private volatile String host;
    private volatile int port;

    public ReconnectionManager(NetworkManager networkManager,
                               ProtocolHandler protocolHandler,
                               EventBus eventBus) {
        this(networkManager, protocolHandler, eventBus, Runnable::run,
                Duration.ofSeconds(1), Duration.ofSeconds(45));
    }

    public ReconnectionManager(NetworkManager networkManager,
                               ProtocolHandler protocolHandler,
                               EventBus eventBus,
                               Executor callbackExecutor,
                               Duration interval,
                               Duration autoWindow) {
        this.networkManager = Objects.requireNonNull(networkManager, "networkManager");
        this.protocolHandler = Objects.requireNonNull(protocolHandler, "protocolHandler");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.callbackExecutor = callbackExecutor != null ? callbackExecutor : Runnable::run;
        this.interval = interval != null ? interval : Duration.ofSeconds(1);
        this.autoWindow = autoWindow != null ? autoWindow : Duration.ofSeconds(5);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("reconnect"));
        registerEventHandlers();
    }

    public void startAutoReconnect(String token) {
        ensureConnectionInfo();
        Objects.requireNonNull(token, "token");
        if (!state.compareAndSet(State.IDLE, State.AUTO)) {
            return;
        }
        this.lastToken = token;
        attempts.set(0);
        cancelAutoTask();
        autoTask = scheduler.scheduleAtFixedRate(this::autoAttempt, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void manualReconnect(String token) {
        ensureConnectionInfo();
        Objects.requireNonNull(token, "token");
        cancelAutoTask();
        this.lastToken = token;
        state.set(State.MANUAL);
        attemptReconnect();
    }

    public void setOnAutoReconnectFailed(Runnable handler) {
        this.onAutoReconnectFailed = handler;
    }

    public void setOnReconnectSuccess(Consumer<String> handler) {
        this.onReconnectSuccess = handler;
    }

    public void setConnectionInfo(String host, int port) {
        Objects.requireNonNull(host, "host");
        if (host.isBlank() || port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("Invalid connection info");
        }
        this.host = host;
        this.port = port;
    }

    public boolean isReconnecting() {
        State current = state.get();
        return current == State.AUTO || current == State.MANUAL;
    }

    public void abortAutoReconnect() {
        cancelAutoTask();
        attempts.set(0);
        state.set(State.IDLE);
    }

    public void shutdown() {
        cancelAutoTask();
        scheduler.shutdownNow();
    }

    private void autoAttempt() {
        if (state.get() != State.AUTO) {
            return;
        }
        long elapsed = attempts.get() * interval.toMillis();
        if (elapsed >= autoWindow.toMillis()) {
            failAutoReconnect();
            return;
        }
        attempts.incrementAndGet();
        attemptReconnect();
    }

    private void attemptReconnect() {
        synchronized (connectLock) {
            try {
                LOG.info(() -> "Attempting reconnection to " + host + ":" + port);
                networkManager.disconnect();
                networkManager.connect(host, port);
                protocolHandler.sendReconnect(lastToken);

            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Reconnection attempt failed", ex);
            }
        }
    }

    private void registerEventHandlers() {
        eventBus.subscribe("RECONNECT_OK", this::handleReconnectOk);
        eventBus.subscribe("ERR", this::handleError);
    }

    private void handleReconnectOk(ServerEvent event) {
        cancelAutoTask();
        state.set(State.IDLE);
        callbackExecutor.execute(() -> {
            Consumer<String> handler = onReconnectSuccess;
            if (handler != null) {
                handler.accept(parseServerState(event));
            }
        });
    }

    private void handleError(ServerEvent event) {
        String errorMsg = event.getPart(2);
        if (!"INVALID_TOKEN".equals(errorMsg)) {
            return;
        }
        State previous = state.getAndSet(State.IDLE);
        cancelAutoTask();
        if (previous == State.AUTO) {
            callbackExecutor.execute(() -> {
                if (onAutoReconnectFailed != null) {
                    onAutoReconnectFailed.run();
                }
            });
        }
    }

    private String parseServerState(ServerEvent event) {
        String statePart = event.getPart(1);
        if ("GAME".equals(statePart) && event.getPartsCount() >= 5) {
            return "GAME " + event.getPart(2) + " " + event.getPart(3) + " " + event.getPart(4);
        }
        if ("LOBBY".equals(statePart)) {
            if (event.getPartsCount() >= 4) {
                return "LOBBY " + event.getPart(2) + " " + event.getPart(3);
            }
            if (event.getPartsCount() >= 3) {
                return "LOBBY " + event.getPart(2);
            }
            return "LOBBY NONE";
        }
        return statePart != null ? statePart : "UNKNOWN";
    }

    private void failAutoReconnect() {
        cancelAutoTask();
        state.set(State.IDLE);
        callbackExecutor.execute(() -> {
            if (onAutoReconnectFailed != null) {
                onAutoReconnectFailed.run();
            }
        });
    }

    private void cancelAutoTask() {
        ScheduledFuture<?> task = autoTask;
        if (task != null) {
            task.cancel(true);
            autoTask = null;
        }
    }

    private void ensureConnectionInfo() {
        if (host == null || port <= 0) {
            throw new IllegalStateException("Connection info is not set");
        }
    }

    private enum State {
        IDLE,
        AUTO,
        MANUAL
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private int index = 0;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
