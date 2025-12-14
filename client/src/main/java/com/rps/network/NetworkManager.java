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
import java.util.concurrent.ScheduledExecutorService;
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
    private static final Duration DEFAULT_HARD_TIMEOUT = Duration.ofSeconds(45);

    private final Duration softTimeout;
    private final Duration hardTimeout;
    private final Object lifecycleLock = new Object();
    private final Object writerLock = new Object();
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    private final AtomicBoolean softTimeoutTriggered = new AtomicBoolean(false);
    private final AtomicBoolean hardTimeoutTriggered = new AtomicBoolean(false);
    private final AtomicLong lastMessageAt = new AtomicLong();

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private ExecutorService writerExecutor;
    private ScheduledExecutorService watchdogExecutor;
    private Thread readerThread;

    private Consumer<String> onMessageReceived;
    private Runnable onDisconnected;
    private Runnable onSoftTimeout;
    private Runnable onHardTimeout;

    public NetworkManager() {
        this(DEFAULT_TIMEOUT, DEFAULT_HARD_TIMEOUT);
    }

    public NetworkManager(Duration softTimeout) {
        this(softTimeout, DEFAULT_HARD_TIMEOUT);
    }

    public NetworkManager(Duration softTimeout, Duration hardTimeout) {
        this.softTimeout = softTimeout != null ? softTimeout : DEFAULT_TIMEOUT;
        this.hardTimeout = hardTimeout != null ? hardTimeout : DEFAULT_HARD_TIMEOUT;
    }

    public void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port");
        }
        LOG.info("Connecting to " + host + ":" + port);
        synchronized (lifecycleLock) {
            LOG.info("Establishing socket connection...");
            disconnectInternal();
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 1000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writerExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("network-writer"));
            watchdogExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("network-watchdog"));
            lastMessageAt.set(System.nanoTime());
            resetTimeoutFlags();
            intentionalClose.set(false);
            startReaderThread();
            startWatchdog();
        }
    }

    public void disconnect() {
        LOG.info("Disconnecting from server (intentional)...");
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

    public void setOnSoftTimeout(Runnable handler) {
        this.onSoftTimeout = handler;
    }

    public void setOnHardTimeout(Runnable handler) {
        this.onHardTimeout = handler;
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
                resetTimeoutFlags();
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
            resetTimeoutFlags();
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
        shutdownExecutor(watchdogExecutor);
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("network-watchdog"));
        watchdogExecutor.scheduleAtFixedRate(this::checkInactivity, 1, 1, TimeUnit.SECONDS);
    }

    private void checkInactivity() {
        LOG.info("Checking for inactivity...");
        if (!isConnected()) {
            LOG.info("Socket not connected, skipping inactivity check");
            return;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - lastMessageAt.get());
        if (!softTimeoutTriggered.get() && elapsed.compareTo(softTimeout) >= 0) {
            softTimeoutTriggered.set(true);
            Runnable handler = onSoftTimeout;
            if (handler != null) {
                LOG.info("Soft timeout triggered after " + elapsed.toSeconds() + " seconds");
                handler.run();
            }
        }
        if (!hardTimeoutTriggered.get() && elapsed.compareTo(hardTimeout) >= 0) {
            hardTimeoutTriggered.set(true);
            Runnable handler = onHardTimeout;
            if (handler != null) {
                LOG.info("Hard timeout triggered after " + elapsed.toSeconds() + " seconds");
                handler.run();
            }
        }
    }

    private void resetTimeoutFlags() {
        softTimeoutTriggered.set(false);
        hardTimeoutTriggered.set(false);
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
            LOG.info("Disconnecting from server...");
            resetTimeoutFlags();
            closeReaderThread();
            closeQuietly(reader);
            closeQuietly(writer);
            LOG.info("Closed reader and writer.");
            reader = null;
            writer = null;
            closeSocket();
            LOG.info("Closed socket.");
            shutdownExecutor(writerExecutor);
            writerExecutor = null;
            shutdownExecutor(watchdogExecutor);
            watchdogExecutor = null;
            LOG.info("Disconnected.");
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
