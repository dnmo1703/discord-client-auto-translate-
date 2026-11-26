package dev.discordru;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class TranslatorToRussian {
    private static final ExecutorService REQUESTS = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void translate(TranslationSettings.Server server, String text,
                          Consumer<String> success, Consumer<String> failure) {
        REQUESTS.execute(() -> {
            try {
                System.loadLibrary("discordru_native");
                String translated = nativeTranslate(server.endpoint, server.key, text);
                MAIN.post(() -> success.accept(translated));
            } catch (IOException error) {
                MAIN.post(() -> failure.accept(error.getMessage()));
            } catch (LinkageError error) {
                MAIN.post(() -> failure.accept("Не удалось запустить перевод."));
            }
        });
    }

    private static native String nativeTranslate(String endpoint, String key, String text)
            throws IOException;
}
