package com.rps.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class NetworkManager {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int IDLE_TIMEOUT_SECONDS = 6;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(IDLE_TIMEOUT_SECONDS);

    private final Object stateLock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(named("network-writer"));
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter bufferedWriter;
    private ScheduledExecutorService monitor;
    private Consumer<String> onMessageReceived;
    private Runnable onDisconnected;
    private volatile boolean intentionalClose = true;
    private volatile long lastMessageAt = System.nanoTime();

    public void connect(String host, int port) throws IOException {
        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            BufferedReader newReader = new BufferedReader(new InputStreamReader(newSocket.getInputStream()));
            BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream()));

            synchronized (stateLock) {
                closeResources();
                socket = newSocket;
                reader = newReader;
                bufferedWriter = newWriter;
                intentionalClose = false;
                lastMessageAt = System.nanoTime();
                startListener();
                startMonitor();
            }
        } catch (IOException e) {
            try {
                newSocket.close();
            } catch (IOException ignored) {}
            throw e;
        }
    }

    public void disconnect() {
        disconnectInternal(true);
    }

    public void send(String message) {
        BufferedWriter target;
        synchronized (stateLock) {
            if (!isConnectedLocked()) {
                System.out.println("Cannot send, no active connection: " + message);
                return;
            }
            target = bufferedWriter;
        }

        writer.submit(() -> {
            try {
                synchronized (target) {
                    if (!Objects.equals(message, "PONG")) {
                        System.out.println("CLIENT: " + message);
                    }
                    target.write(message);
                    target.write("\r\n");
                    target.flush();
                }
            } catch (IOException e) {
                System.out.println("Failed to send: " + message + " (" + e.getMessage() + ")");
                handleUnexpectedDisconnect(e);
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
        synchronized (stateLock) {
            return isConnectedLocked();
        }
    }

    private void startListener() {
        Thread listener = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastMessageAt = System.nanoTime();
                    Consumer<String> handler = onMessageReceived;
                    if (handler != null) {
                        handler.accept(line);
                    }
                }
                throw new EOFException("Server closed connection");
            } catch (IOException e) {
                handleUnexpectedDisconnect(e);
            }
        }, "network-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private void startMonitor() {
        stopMonitor();
        monitor = Executors.newSingleThreadScheduledExecutor(named("network-timeout"));
        monitor.scheduleAtFixedRate(() -> {
            if (!isConnected()) {
                return;
            }
            long idleNanos = System.nanoTime() - lastMessageAt;
            if (Duration.ofNanos(idleNanos).compareTo(IDLE_TIMEOUT) >= 0) {
                handleUnexpectedDisconnect(new IOException("Read timeout"));
            }
        }, IDLE_TIMEOUT_SECONDS, 1, TimeUnit.SECONDS);
    }

    private void stopMonitor() {
        if (monitor != null) {
            monitor.shutdownNow();
            monitor = null;
        }
    }

    private void handleUnexpectedDisconnect(IOException cause) {
        boolean notify;
        synchronized (stateLock) {
            if (intentionalClose || socket == null) {
                return;
            }
            System.out.println("Connection lost: " + cause.getMessage());
            notify = true;
        }
        if (notify) {
            disconnectInternal(false);
        }
    }

    private void disconnectInternal(boolean intentional) {
        Runnable callback = null;
        synchronized (stateLock) {
            if (socket == null && reader == null && bufferedWriter == null) {
                intentionalClose = intentional;
                return;
            }
            intentionalClose = intentional;
            closeResources();
            stopMonitor();
            if (!intentional) {
                callback = onDisconnected;
            }
        }
        if (callback != null) {
            callback.run();
        }
    }

    private void closeResources() {
        closeQuietly(reader);
        closeQuietly(bufferedWriter);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
        socket = null;
        reader = null;
        bufferedWriter = null;
    }

    private boolean isConnectedLocked() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private static void closeQuietly(Closeable closable) {
        if (closable != null) {
            try {
                closable.close();
            } catch (IOException ignored) {}
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
