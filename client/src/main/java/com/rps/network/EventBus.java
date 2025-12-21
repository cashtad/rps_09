package com.rps.network;

import javafx.application.Platform;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EventBus {
    private static final Logger LOG = Logger.getLogger(EventBus.class.getName());

    private final Executor dispatcher;
    private final Map<String, CopyOnWriteArrayList<Consumer<ServerEvent>>> listeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ServerEvent>> wildcardListeners = new CopyOnWriteArrayList<>();

    private int invalidStreak = 0;
    private Runnable onTooManyInvalid;

    public EventBus() {
        this(Runnable::run);
    }

    public EventBus(Executor dispatcher) {
        this.dispatcher = dispatcher != null ? dispatcher : Runnable::run;
    }

    public static EventBus createJavaFxBus() {
        return new EventBus(command -> Platform.runLater(command));
    }

    public Subscription subscribe(String command, Consumer<ServerEvent> listener) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(listener, "listener");
        listeners.computeIfAbsent(command, key -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> unsubscribe(command, listener);
    }

    public Subscription subscribeAll(Consumer<ServerEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        wildcardListeners.add(listener);
        return () -> wildcardListeners.remove(listener);
    }

    public void unsubscribe(String command, Consumer<ServerEvent> listener) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(listener, "listener");
        List<Consumer<ServerEvent>> handlers = listeners.get(command);
        if (handlers != null) {
            handlers.remove(listener);
            if (handlers.isEmpty()) {
                listeners.remove(command, handlers);
            }
        }
    }

    public void publish(ServerEvent event) {
        Objects.requireNonNull(event, "event");
        wildcardListeners.forEach(listener -> dispatch(listener, event));
        List<Consumer<ServerEvent>> handlers = listeners.get(event.getCommand());
        if (handlers != null) {
            handlers.forEach(listener -> dispatch(listener, event));
        } else {
            recordInvalidEvent();
            LOG.warning("No listeners for event: " + event.getCommand());
        }
    }

    public void clear() {
        listeners.clear();
        wildcardListeners.clear();
    }

    public int getSubscriberCount(String command) {
        List<Consumer<ServerEvent>> handlers = listeners.get(command);
        return handlers != null ? handlers.size() : 0;
    }

    private void dispatch(Consumer<ServerEvent> listener, ServerEvent event) {
        try {
            dispatcher.execute(() -> invokeListener(listener, event));
        } catch (RejectedExecutionException ex) {
            LOG.log(Level.WARNING, "Dispatcher rejected event " + event.getCommand(), ex);
        }
    }

    private void invokeListener(Consumer<ServerEvent> listener, ServerEvent event) {
        try {
            listener.accept(event);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Listener failure for " + event.getCommand(), ex);
        }
    }

    public interface Subscription extends AutoCloseable {
        void unsubscribe();

        @Override
        default void close() {
            unsubscribe();
        }
    }

    public void setOnTooManyInvalid(Runnable action) {
        this.onTooManyInvalid = action;
    }

    public void recordInvalidEvent() {
        if (onTooManyInvalid == null) {
            return;
        }
        invalidStreak = invalidStreak + 1;
        if (invalidStreak >= 3) {
            invalidStreak = 0;
            onTooManyInvalid.run();
        }
    }
}
