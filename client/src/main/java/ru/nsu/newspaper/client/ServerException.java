package ru.nsu.newspaper.client;

import java.io.IOException;

public final class ServerException extends IOException {
    private final int status;
    public ServerException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
