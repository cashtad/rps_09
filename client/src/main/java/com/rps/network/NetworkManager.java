package com.rps.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NetworkManager {
    private static final Logger LOG = Logger.getLogger(NetworkManager.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(6);

    private final Duration inactivityTimeout;
    private final Object lifecycleLock = new Object();
    private final Object writerLock = new Object();
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    private final AtomicLong lastMessageAt = new AtomicLong();

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private ExecutorService writerExecutor;
    private ExecutorService watchdogExecutor;
    private Thread readerThread;

    private Consumer<String> onMessageReceived;
    private Runnable onDisconnected;

    public NetworkManager() {
        this(DEFAULT_TIMEOUT);
    }

    public NetworkManager(Duration inactivityTimeout) {
        this.inactivityTimeout = inactivityTimeout != null ? inactivityTimeout : DEFAULT_TIMEOUT;
    }

    public void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port");
        }
        synchronized (lifecycleLock) {
            disconnectInternal();
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 1000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writerExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("network-writer"));
            watchdogExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("network-watchdog"));
            lastMessageAt.set(System.nanoTime());
            intentionalClose.set(false);
            startReaderThread();
            startWatchdog();
        }
    }

    public void disconnect() {
        intentionalClose.set(true);
        disconnectInternal();
    }

    public void send(String message) {
        Objects.requireNonNull(message, "message");
        ExecutorService executor = this.writerExecutor;
        if (executor == null || executor.isShutdown()) {
            LOG.warning("Writer executor unavailable, dropping message");
            return;
        }
        executor.execute(() -> writeSafely(message));
    }

    public void setOnMessageReceived(Consumer<String> handler) {
        this.onMessageReceived = handler;
    }

    public void setOnDisconnected(Runnable handler) {
        this.onDisconnected = handler;
    }

    public boolean isConnected() {
        Socket current = this.socket;
        return current != null && current.isConnected() && !current.isClosed();
    }

    private void writeSafely(String message) {
        BufferedWriter currentWriter = writer;
        if (currentWriter == null) {
            return;
        }
        try {
            synchronized (writerLock) {
                currentWriter.write(message);
                currentWriter.write("\r\n");
                currentWriter.flush();
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to send message: " + message, ex);
            forceCloseSocket();
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(this::readLoop, "network-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while (!Thread.currentThread().isInterrupted() && reader != null && (line = reader.readLine()) != null) {
                lastMessageAt.set(System.nanoTime());
                Consumer<String> handler = onMessageReceived;
                if (handler != null) {
                    handler.accept(line);
                }
            }
        } catch (IOException ex) {
            if (!intentionalClose.get()) {
                LOG.log(Level.WARNING, "Connection closed unexpectedly", ex);
            }
        } finally {
            boolean wasIntentional = intentionalClose.getAndSet(false);
            disconnectInternal();
            if (!wasIntentional) {
                Runnable handler = onDisconnected;
                if (handler != null) {
                    handler.run();
                }
            }
        }
    }

    private void startWatchdog() {
        if (watchdogExecutor == null) {
            return;
        }
        long period = Math.max(500, inactivityTimeout.toMillis() / 2);
        watchdogExecutor.execute(() -> {});
        ((ExecutorService) watchdogExecutor).shutdown();
        watchdogExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("network-watchdog"));
        watchdogExecutor.submit(() -> {});
        ((ExecutorService) watchdogExecutor).shutdown();
    }

    private void forceCloseSocket() {
        synchronized (lifecycleLock) {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    LOG.log(Level.FINE, "Error closing socket", ex);
                }
            }
        }
    }

    private void disconnectInternal() {
        synchronized (lifecycleLock) {
            closeReaderThread();
            closeQuietly(reader);
            closeQuietly(writer);
            reader = null;
            writer = null;
            closeSocket();
            shutdownExecutor(writerExecutor);
            writerExecutor = null;
            shutdownExecutor(watchdogExecutor);
            watchdogExecutor = null;
        }
    }

    private void closeReaderThread() {
        Thread thread = readerThread;
        if (thread == null) {
            return;
        }
        readerThread = null;
        if (Thread.currentThread() == thread) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(200);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            } finally {
                socket = null;
            }
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
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
