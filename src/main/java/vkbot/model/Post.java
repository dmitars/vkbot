package vkbot.model;

import vkbot.json.JsonNode;

import java.time.Instant;

/**
 * Одно сообщение (комментарий) в обсуждении.
 * Поле {@link #getRaw()} содержит исходный JSON от VK — из него метрики
 * могут доставать любые дополнительные данные (вложения, лайки и т.п.).
 */
public final class Post {
    private final long id;
    private final long groupId;
    private final Topic topic;
    private final long authorId;
    private final Instant date;
    private final String text;
    private final String countedText;
    private final JsonNode raw;
    private final boolean bonus;
    private final int weight;
    private final boolean skipped;

    public Post(long id, long groupId, Topic topic, long authorId, Instant date,
                String text, String countedText, JsonNode raw) {
        this(id, groupId, topic, authorId, date, text, countedText, raw, false, 1);
    }

    public Post(long id, long groupId, Topic topic, long authorId, Instant date,
                String text, String countedText, JsonNode raw, boolean bonus, int weight) {
        this(id, groupId, topic, authorId, date, text, countedText, raw, bonus, weight, false);
    }

    public Post(long id, long groupId, Topic topic, long authorId, Instant date,
                String text, String countedText, JsonNode raw, boolean bonus, int weight, boolean skipped) {
        this.skipped = skipped;
        this.bonus = bonus;
        this.weight = weight;
        this.id = id;
        this.groupId = groupId;
        this.topic = topic;
        this.authorId = authorId;
        this.date = date;
        this.text = text;
        this.countedText = countedText;
        this.raw = raw;
    }

    public long getId() { return id; }
    public long getGroupId() { return groupId; }
    public Topic getTopic() { return topic; }
    /** >0 — пользователь, <0 — сообщество (пост от имени группы). */
    public long getAuthorId() { return authorId; }
    public Instant getDate() { return date; }
    /** Текст как пришёл из VK. */
    public String getText() { return text; }
    /** Текст после нормализации (без разметки ответов и т.п.) — по нему считаются символы. */
    public String getCountedText() { return countedText; }
    public JsonNode getRaw() { return raw; }
    /** Сообщение начинается со строки «Бонусные баллы: ...». */
    public boolean isBonus() { return bonus; }
    /** Множитель символов: 1 для обычных, 2 (по умолчанию) для бонусных. */
    public int getWeight() { return weight; }
    /** Пометка «не считать»: сообщение учитывается в активности, но без символов и баллов. */
    public boolean isSkipped() { return skipped; }

    /** Видимые символы учтённого текста; 0 для сообщений с пометкой «не считать». */
    public int chars() {
        return skipped ? 0 : vkbot.collect.TextNormalizer.length(countedText);
    }

    public String getUrl() {
        return "https://vk.com/topic-" + groupId + "_" + topic.getId() + "?post=" + id;
    }
}
