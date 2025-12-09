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
    private volatile boolean intentionalDisconnect = false;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        executor = Executors.newSingleThreadExecutor();
        intentionalDisconnect = false;
        startListening();
    }

    public void disconnect() {
        intentionalDisconnect = true;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Симулирует неожиданную потерю соединения (для тестирования)
     */
    public void simulateConnectionLoss() {
        intentionalDisconnect = false;  // НЕ намеренное отключение
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
                    if (onMessageReceived != null)
                        onMessageReceived.accept(line);
                }
            } catch (IOException e) {
                System.out.println("Connection closed: " + e.getMessage());
            } finally {
                if (!intentionalDisconnect && onDisconnected != null) {
                    onDisconnected.run();
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void send(String message) {
        if (executor == null || executor.isShutdown()) {
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
