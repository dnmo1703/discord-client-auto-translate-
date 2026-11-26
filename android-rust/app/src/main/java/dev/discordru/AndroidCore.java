package dev.discordru;

import java.io.IOException;

final class AndroidCore {
    static { System.loadLibrary("discordru_native"); }

    static native String discord(String operation, String token, String id, String value,
                                 int proxyPort, int routeRevision) throws IOException;
    static native String translate(String text) throws IOException;
}
