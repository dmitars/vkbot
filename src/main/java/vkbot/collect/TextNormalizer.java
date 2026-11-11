package vkbot.collect;

import java.util.regex.Pattern;

/** Подготовка текста и подсчёт символов. */
public final class TextNormalizer {

    /** Разметка ответа/упоминания VK: [id123:bp-1_2|Имя] или [club123|Название]. */
    private static final Pattern MENTION = Pattern.compile("\\[(?:id|club|public)\\d+(?::bp-?\\d+_\\d+)?\\|([^\\]]*)\\]");

    private final boolean stripMentions;

    public TextNormalizer(boolean stripMentions) {
        this.stripMentions = stripMentions;
    }

    /** Текст для подсчёта и показа: разметка упоминаний заменена именами, пробелы сохранены (для превью). */
    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text;
        if (stripMentions) {
            t = MENTION.matcher(t).replaceAll("$1");
        }
        return t.trim();
    }

    /**
     * Количество <b>видимых</b> символов: пробелы любого вида, переносы строк, табуляция
     * и невидимые символы (нулевой ширины, мягкий перенос, BOM, селекторы вариантов эмодзи и т.п.)
     * не считаются. Эмодзи и прочие суррогатные пары считаются за один символ.
     */
    public static int length(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isVisible(cp)) {
                n++;
            }
        }
        return n;
    }

    static boolean isVisible(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return false; // пробелы, NBSP, переносы строк, табуляция
        }
        int type = Character.getType(cp);
        if (type == Character.CONTROL || type == Character.FORMAT) {
            return false; // управляющие и форматирующие: U+200B..U+200F, U+2060.., U+FEFF, U+00AD и др.
        }
        if ((cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)) {
            return false; // селекторы вариантов (например, U+FE0F после эмодзи)
        }
        // «пустые» заполнители, которые выглядят как пробел
        return cp != 0x115F && cp != 0x1160 && cp != 0x3164 && cp != 0xFFA0 && cp != 0x2800;
    }
}
