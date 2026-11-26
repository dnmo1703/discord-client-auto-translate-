package dev.discordru;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.function.Consumer;

final class TranslatorToRussian {
    static void translate(String text, Consumer<String> status, Consumer<String> success,
                          Consumer<String> failure) {
        LanguageIdentifier identifier;
        try {
            identifier = LanguageIdentification.getClient();
        } catch (RuntimeException error) {
            failure.accept("Не удалось запустить определение языка.");
            return;
        }
        identifier.identifyLanguage(text).addOnCompleteListener(task -> {
            identifier.close();
            if (!task.isSuccessful()) {
                failure.accept("Не удалось определить язык.");
                return;
            }
            String source = TranslateLanguage.fromLanguageTag(task.getResult());
            if (source == null) {
                failure.accept("Язык не распознан или недоступен для перевода.");
                return;
            }
            if (TranslateLanguage.RUSSIAN.equals(source)) {
                success.accept(text);
                return;
            }
            downloadAndTranslate(text, source, status, success, failure);
        });
    }

    private static void downloadAndTranslate(String text, String source, Consumer<String> status,
                                             Consumer<String> success, Consumer<String> failure) {
        Translator translator;
        try {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        } catch (RuntimeException error) {
            failure.accept("Не удалось запустить перевод.");
            return;
        }
        status.accept("Подготавливаем модель. При первой загрузке нужен интернет…");
        translator.downloadModelIfNeeded()
                .onSuccessTask(unused -> {
                    status.accept("Переводим…");
                    return translator.translate(text);
                })
                .addOnCompleteListener(task -> {
                    translator.close();
                    if (task.isSuccessful()) success.accept(task.getResult());
                    else failure.accept("Перевод не удался. Если модель ещё не скачана, проверьте интернет и повторите.");
                });
    }
}
