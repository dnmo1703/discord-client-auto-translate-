package dev.discordru;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.function.Consumer;

final class TranslatorToRussian {
    static void translate(String text, Consumer<String> status, Consumer<String> success) {
        final LanguageIdentifier identifier;
        try {
            identifier = LanguageIdentification.getClient();
        } catch (RuntimeException error) {
            status.accept("Не удалось запустить определение языка.");
            return;
        }
        identifier.identifyLanguage(text).addOnCompleteListener(task -> {
            identifier.close();
            if (!task.isSuccessful()) {
                status.accept("Не удалось определить язык.");
                return;
            }
            String source = TranslateLanguage.fromLanguageTag(task.getResult());
            if (source == null) {
                status.accept("Язык не распознан или недоступен для перевода.");
                return;
            }
            if (TranslateLanguage.RUSSIAN.equals(source)) {
                success.accept(text);
                return;
            }
            downloadAndTranslate(text, source, status, success);
        });
    }

    private static void downloadAndTranslate(String text, String source, Consumer<String> status,
                                             Consumer<String> success) {
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        status.accept("Подготавливаем модель. При первой загрузке нужен интернет…");
        translator.downloadModelIfNeeded()
                .onSuccessTask(unused -> {
                    status.accept("Переводим…");
                    return translator.translate(text);
                })
                .addOnCompleteListener(task -> {
                    translator.close();
                    if (task.isSuccessful()) success.accept(task.getResult());
                    else status.accept("Перевод не удался. Если модель ещё не скачана, проверьте интернет и повторите.");
                });
    }
}
