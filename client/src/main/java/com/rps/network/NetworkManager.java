package com.rps.network;

import java.io.*;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class NetworkManager {
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Consumer<String> onMessageReceived;
    private Runnable onDisconnected;
    private ExecutorService executor;
    private ScheduledExecutorService timeoutChecker;
    private volatile boolean intentionalDisconnect = false;

    // Таймаут: если за это время не пришло ни одного сообщения, считаем что связь потеряна
    private static final int CONNECTION_TIMEOUT_SECONDS = 6;
    private volatile long lastMessageTime;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        executor = Executors.newSingleThreadExecutor();
        intentionalDisconnect = false;
        lastMessageTime = System.currentTimeMillis();

        startListening();
        startTimeoutMonitoring();
    }

    public void disconnect() {
        intentionalDisconnect = true;
        stopTimeoutMonitoring();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        if (executor != null) executor.shutdownNow();
    }

    private void startListening() {
        Thread listenerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    lastMessageTime = System.currentTimeMillis();
                    if (onMessageReceived != null)
                        onMessageReceived.accept(line);
                }
            } catch (IOException e) {
                System.out.println("Connection closed: " + e.getMessage());
            } finally {
                stopTimeoutMonitoring();
                if (!intentionalDisconnect && onDisconnected != null) {
                    onDisconnected.run();
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void startTimeoutMonitoring() {
        timeoutChecker = Executors.newSingleThreadScheduledExecutor();
        timeoutChecker.scheduleAtFixedRate(() -> {
            long timeSinceLastMessage = System.currentTimeMillis() - lastMessageTime;
            long secondsSinceLastMessage = timeSinceLastMessage / 1000;

            if (secondsSinceLastMessage > CONNECTION_TIMEOUT_SECONDS) {
                System.out.println("Connection timeout detected! No messages for " +
                        secondsSinceLastMessage + " seconds");

                // Принудительно закрываем соединение и запускаем переподключение
                intentionalDisconnect = false;
                try {
                    if (socket != null) socket.close();
                } catch (IOException ignored) {}
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopTimeoutMonitoring() {
        if (timeoutChecker != null && !timeoutChecker.isShutdown()) {
            timeoutChecker.shutdown();
        }
    }

    public void send(String message) {
        if (executor == null || executor.isShutdown() || !isConnected()) {
            System.out.println("Cannot send, executor is shut down: " + message);
            return;
        }

        executor.submit(() -> {
            try {
                synchronized (out) {
                    if (!Objects.equals(message, "PONG")) {
                        System.out.println("CLIENT: " + message);
                    }
                    out.write(message + "\r\n");
                    out.flush();
                }
            } catch (IOException e) {
                System.out.println("Failed to send: " + message);
            }
        });
    }

    public void setOnMessageReceived(Consumer<String> handler) {
        this.onMessageReceived = handler;
    }

    public void setOnDisconnected(Runnable handler) {
        this.onDisconnected = handler;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}

