package dev.discordru;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public final class InlineTranslation {
    public static View wrap(TextView content) {
        return new MessageRow(content);
    }

    public static void bind(TextView content, String id) {
        if (content.getParent() instanceof MessageRow) {
            ((MessageRow) content.getParent()).bindMessage(id, content);
        }
    }

    private static final class MessageRow extends LinearLayout {
        private final Button translate;
        private final TextView result;
        private String messageId;
        private String source;
        private String translation;
        private long binding;

        MessageRow(TextView content) {
            super(content.getContext());
            setOrientation(VERTICAL);
            addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            translate = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
            translate.setAllCaps(false);
            translate.setText("Перевести");
            translate.setTextSize(14);
            translate.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            translate.setMinimumWidth(0);
            translate.setMinWidth(0);
            translate.setMinimumHeight(dp(42));
            translate.setMinHeight(dp(42));
            translate.setPaddingRelative(0, 0, dp(12), 0);
            translate.setVisibility(GONE);
            translate.setOnClickListener(view -> translate());
            addView(translate, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            result = new TextView(getContext());
            result.setTextSize(14);
            result.setPaddingRelative(0, 0, 0, dp(4));
            result.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            result.setVisibility(GONE);
            addView(result, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        void bindMessage(String id, TextView content) {
            ColorStateList linkColors = content.getLinkTextColors();
            translate.setTextColor(linkColors != null ? linkColors : content.getTextColors());
            result.setTextColor(content.getTextColors());
            translate.setPaddingRelative(content.getPaddingStart(), 0,
                    content.getPaddingEnd() + dp(12), 0);
            result.setPaddingRelative(content.getPaddingStart(), 0, content.getPaddingEnd(), dp(4));
            String text = content.getText().toString().trim();
            if (TextUtils.equals(messageId, id) && TextUtils.equals(source, text)) return;

            messageId = id;
            source = text;
            binding++;
            translation = null;
            result.setText("");
            result.setVisibility(GONE);
            translate.setText("Перевести");
            translate.setEnabled(true);
            boolean hasText = text.length() <= 10000
                    && text.codePoints().anyMatch(Character::isLetter);
            translate.setVisibility(hasText ? VISIBLE : GONE);
        }

        private void translate() {
            if (translation != null) {
                boolean show = result.getVisibility() != VISIBLE;
                result.setVisibility(show ? VISIBLE : GONE);
                translate.setText(show ? "Скрыть перевод" : "Перевести");
                return;
            }

            long currentBinding = binding;
            WeakReference<MessageRow> row = new WeakReference<>(this);
            translate.setEnabled(false);
            showResult("Определяем язык…");
            TranslatorToRussian.translate(source,
                    status -> apply(row, currentBinding, view -> view.showResult(status)),
                    translated -> apply(row, currentBinding, view -> {
                        view.translation = translated;
                        view.showResult(translated.equals(view.source)
                                ? "Сообщение уже на русском." : translated);
                        view.translate.setText("Скрыть перевод");
                        view.translate.setEnabled(true);
                    }),
                    error -> apply(row, currentBinding, view -> {
                        view.showResult(error);
                        view.translate.setEnabled(true);
                    }));
        }

        private static void apply(WeakReference<MessageRow> reference, long binding,
                                  java.util.function.Consumer<MessageRow> action) {
            MessageRow row = reference.get();
            if (row != null && row.binding == binding) action.accept(row);
        }

        private void showResult(String text) {
            result.setText(text);
            result.setVisibility(VISIBLE);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }
}
