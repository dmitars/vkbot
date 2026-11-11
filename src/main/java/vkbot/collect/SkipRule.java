package vkbot.collect;

import java.util.regex.Pattern;

/**
 * Правило исключения: если первая строка сообщения — фраза «не считать» (отдельной строкой),
 * сообщение не учитывается в статистике вообще. Регистр не важен, точка/восклицательный знак
 * в конце строки допускаются; разметка ответа VK ([id..|Имя], ) в начале пропускается.
 */
public final class SkipRule {

    public static final String DEFAULT_MARKER = "не\\s+считать";

    private final Pattern pattern;

    public SkipRule(String markerRegex) {
        this.pattern = Pattern.compile(
                "^\\s*(?:\\[(?:id|club|public)\\d+(?::bp-?\\d+_\\d+)?\\|[^\\]]*\\]\\s*,?\\s*)*"
                        + "(?:" + markerRegex + ")[ \\t]*[.!]*[ \\t]*(?:\\r?\\n|$)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public boolean skip(String rawText) {
        return rawText != null && pattern.matcher(rawText).find();
    }

    /** Текст без строки «не считать» (для показа в списке сообщений). */
    public String stripMarker(String rawText) {
        return rawText == null ? "" : pattern.matcher(rawText).replaceFirst("");
    }
}
