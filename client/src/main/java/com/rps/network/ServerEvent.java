package com.rps.network;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ServerEvent {
    private final String command;
    private final String[] parts;
    private final String fullMessage;

    public ServerEvent(String command, String[] parts, String fullMessage) {
        this.command = Objects.requireNonNull(command, "command");
        this.parts = parts != null ? parts.clone() : new String[]{command};
        this.fullMessage = Objects.requireNonNull(fullMessage, "fullMessage");
    }

    public ServerEvent(String command, List<String> parts, String fullMessage) {
        this(command, parts != null ? parts.toArray(String[]::new) : null, fullMessage);
    }

    public String getCommand() {
        return command;
    }

    public String[] getParts() {
        return parts.clone();
    }

    public String getFullMessage() {
        return fullMessage;
    }

    public String getPart(int index) {
        return (index >= 0 && index < parts.length) ? parts[index] : null;
    }

    public int getPartsCount() {
        return parts.length;
    }

    @Override
    public String toString() {
        return "ServerEvent{command='" + command + "', parts=" + Arrays.toString(parts) + ", fullMessage='" + fullMessage + "'}";
    }
}
