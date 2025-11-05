package com.rps.network;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class NetworkManager {
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private Consumer<String> onMessageReceived;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        startListening();
    }

    public void disconnect() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        executor.shutdownNow();
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
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void send(String message) {
        executor.submit(() -> {
            try {
                synchronized (out) {
                    System.out.println("CLIENT: " + message);
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

}
