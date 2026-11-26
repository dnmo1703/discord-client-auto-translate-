package dev.discordru;

import java.io.IOException;

public final class SessionExpiredException extends IOException {
    public SessionExpiredException(String message) {
        super(message);
    }
}
