package com.rps.network;

public class ServerEvent {
    private final String command;
    private final String[] parts;
    private final String fullMessage;

    public ServerEvent(String command, String[] parts, String fullMessage) {
        this.command = command;
        this.parts = parts;
        this.fullMessage = fullMessage;
    }

    public String getCommand() {
        return command;
    }

    public String[] getParts() {
        return parts;
    }

    public String getFullMessage() {
        return fullMessage;
    }

    public String getPart(int index) {
        return index < parts.length ? parts[index] : null;
    }

    public int getPartsCount() {
        return parts.length;
    }

    @Override
    public String toString() {
        return "ServerEvent{command='" + command + "', message='" + fullMessage + "'}";
    }
}
