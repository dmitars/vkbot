package vkbot.collect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Правило «бонусных» сообщений.
 *
 * <p>Если сообщение начинается со строки-маркера (по умолчанию «Бонусные баллы: ...»),
 * то сама эта строка не учитывается, а весь текст после неё считается с множителем (по умолчанию ×2).
 * Разметка ответа VK в начале сообщения ([id123:bp-..|Имя], ) при проверке пропускается.
 */
public final class BonusRule {

    /** Разметка ответа/упоминания, которую VK добавляет в начало ответа на сообщение. */
    private static final Pattern LEADING_MENTION =
            Pattern.compile("^\\s*(?:\\[(?:id|club|public)\\d+(?::bp-?\\d+_\\d+)?\\|[^\\]]*\\]\\s*,?\\s*)+");

    private final Pattern marker;
    private final int multiplier;
    private final TextNormalizer normalizer;

    public BonusRule(String markerRegex, int multiplier, TextNormalizer normalizer) {
        this.marker = Pattern.compile("^\\s*(?:" + markerRegex + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.multiplier = multiplier;
        this.normalizer = normalizer;
    }

    public static final String DEFAULT_MARKER = "Бонусные\\s+баллы\\s*:";

    /** Результат разбора: бонусное ли сообщение, какой текст считать и с каким весом. */
    public static final class Analysis {
        public final boolean bonus;
        public final String countedText;
        public final int weight;

        Analysis(boolean bonus, String countedText, int weight) {
            this.bonus = bonus;
            this.countedText = countedText;
            this.weight = weight;
        }
    }

    public Analysis analyze(String rawText) {
        String text = rawText == null ? "" : rawText;
        Matcher m = LEADING_MENTION.matcher(text);
        String body = m.find() ? text.substring(m.end()) : text;
        Matcher mm = marker.matcher(body);
        if (mm.find()) {
            int nl = body.indexOf('\n', mm.end());
            String after = nl < 0 ? "" : body.substring(nl + 1);
            return new Analysis(true, normalizer.normalize(after), multiplier);
        }
        return new Analysis(false, normalizer.normalize(text), 1);
    }
}
