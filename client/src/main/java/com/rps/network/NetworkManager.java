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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Low-level TCP connection manager.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Open and close a TCP socket.</li>
 *     <li>Read and write line-based messages in background threads.</li>
 *     <li>Emit soft and hard timeouts based on inactivity.</li>
 * </ul>
 */
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

    /**
     * Creates new manager with default soft and hard timeouts.
     */
    public NetworkManager() {
        this(DEFAULT_TIMEOUT, DEFAULT_HARD_TIMEOUT);
    }

    /**
     * Creates manager with custom soft timeout and default hard timeout.
     *
     * @param softTimeout inactivity duration before soft timeout callback is fired.
     */
    public NetworkManager(Duration softTimeout) {
        this(softTimeout, DEFAULT_HARD_TIMEOUT);
    }

    /**
     * Creates manager with custom soft and hard timeout values.
     *
     * @param softTimeout inactivity duration before soft timeout callback is fired.
     * @param hardTimeout inactivity duration before hard timeout callback is fired.
     */
    public NetworkManager(Duration softTimeout, Duration hardTimeout) {
        this.softTimeout = softTimeout != null ? softTimeout : DEFAULT_TIMEOUT;
        this.hardTimeout = hardTimeout != null ? hardTimeout : DEFAULT_HARD_TIMEOUT;
    }

    /**
     * Opens a new TCP connection to given host and port.
     *
     * @param host remote host name or IP address.
     * @param port remote TCP port number (1-65535).
     * @throws IOException              if network connection fails.
     * @throws IllegalArgumentException if port is out of range.
     */
    public void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port");
        }
        LOG.info("Connecting to " + host + ":" + port);
        synchronized (lifecycleLock) {
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

    /**
     * Closes current TCP connection if present and marks it as intentional.
     * Input: none, Output: none.
     */
    public void disconnect() {
        intentionalClose.set(true);
        disconnectInternal();
    }

    /**
     * Sends a single text line to server, appending CRLF.
     *
     * @param message non-null string payload to send.
     */
    public void send(String message) {
        Objects.requireNonNull(message, "message");
        ExecutorService executor = this.writerExecutor;
        if (executor == null || executor.isShutdown()) {
            LOG.warning("Writer executor unavailable, dropping message");
            return;
        }
        LOG.info("CLIENT: " + message);
        executor.execute(() -> writeSafely(message));
    }

    /**
     * Registers callback invoked when a full line is read from server.
     *
     * @param handler line consumer; may be null to disable callbacks.
     */
    public void setOnMessageReceived(Consumer<String> handler) {
        this.onMessageReceived = handler;
    }

    /**
     * Registers callback invoked when connection is closed unexpectedly.
     *
     * @param handler callback for disconnect event; may be null.
     */
    public void setOnDisconnected(Runnable handler) {
        this.onDisconnected = handler;
    }

    /**
     * Registers callback invoked when soft timeout occurs.
     *
     * @param handler callback for soft timeout; may be null.
     */
    public void setOnSoftTimeout(Runnable handler) {
        this.onSoftTimeout = handler;
    }

    /**
     * Registers callback invoked when hard timeout occurs.
     *
     * @param handler callback for hard timeout; may be null.
     */
    public void setOnHardTimeout(Runnable handler) {
        this.onHardTimeout = handler;
    }

    /**
     * Checks whether there is an active connected socket.
     *
     * @return true if connected, false otherwise.
     */
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

    /**
     * Background loop that continuously reads lines from socket
     * and dispatches them to the registered listener.
     */
    private void startReaderThread() {
        readerThread = new Thread(this::readLoop, "network-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Periodic task run by watchdog executor to detect inactivity.
     * Computes elapsed time and invokes soft/hard timeout callbacks.
     */
    private void startWatchdog() {
        shutdownExecutor(watchdogExecutor);
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("network-watchdog"));
        watchdogExecutor.scheduleAtFixedRate(this::checkInactivity, 1, 1, TimeUnit.SECONDS);
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

    private void checkInactivity() {
        if (!isConnected()) {
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
//            resetTimeoutFlags();
            closeReaderThread();
            closeSocket();
            closeQuietly(reader);
            closeQuietly(writer);
            reader = null;
            writer = null;
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
                socket.shutdownInput();
                socket.shutdownOutput();
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

    /**
     * Simple thread factory that assigns a name prefix and marks threads as daemons.
     */
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
