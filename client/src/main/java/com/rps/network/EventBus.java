
package com.rps.network;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class EventBus {
    // Используем потокобезопасные коллекции
    private final Map<String, List<Consumer<ServerEvent>>> listeners = new ConcurrentHashMap<>();
    
    // Wildcard подписчик - срабатывает на любое событие
    private final List<Consumer<ServerEvent>> wildcardListeners = new CopyOnWriteArrayList<>();


    public void subscribe(String command, Consumer<ServerEvent> listener) {
        listeners.computeIfAbsent(command, k -> new CopyOnWriteArrayList<>()).add(listener);
    }


    public void subscribeAll(Consumer<ServerEvent> listener) {
        wildcardListeners.add(listener);
    }

    public void unsubscribe(String command, Consumer<ServerEvent> listener) {
        List<Consumer<ServerEvent>> handlers = listeners.get(command);
        if (handlers != null) {
            handlers.remove(listener);
        }
    }

    public void publish(ServerEvent event) {
        // Вызываем wildcard подписчиков
        for (Consumer<ServerEvent> listener : wildcardListeners) {
            Platform.runLater(() -> listener.accept(event));
        }

        // Вызываем подписчиков на конкретную команду
        List<Consumer<ServerEvent>> handlers = listeners.get(event.getCommand());
        if (handlers != null) {
            for (Consumer<ServerEvent> handler : handlers) {
                Platform.runLater(() -> {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        System.err.println("Error handling event " + event.getCommand() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
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
}
