package vkbot.vk;

import vkbot.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Демо-транспорт: генерирует правдоподобные ответы VK без обращения к сети.
 * Нужен, чтобы проверить отчёт и настройки метрик до получения токена (запуск с --demo).
 */
public final class DemoVkTransport implements VkTransport {

    private static final long GROUP_ID = 1;
    private static final String[][] USERS = {
            {"101", "Анна", "Смирнова"}, {"102", "Иван", "Петров"}, {"103", "Мария", "Кузнецова"},
            {"104", "Олег", "Соколов"}, {"105", "Дарья", "Попова"}, {"106", "Сергей", "Лебедев"}};
    private static final String[] TOPICS = {"Правила и объявления", "Обсуждение недели", "Отчёты участников", "Флуд"};
    private static final String[] PHRASES = {
            "Согласна с предыдущим оратором.", "Отчёт: Часов: 3,5. Сделано много интересного 🙂",
            "Интересный вопрос! Давайте обсудим подробнее на встрече в субботу.",
            "Часов: 2. Задание выполнено, ссылка в комментарии #задание",
            "+1", "Кто-нибудь знает, когда следующая встреча? #вопрос",
            "Длинный развёрнутый ответ с рассуждениями о том, как лучше организовать работу клуба, "
                    + "кого пригласить и что подготовить заранее, чтобы всё прошло хорошо.",
            "Бонусные баллы: эссе\nМой развёрнутый ответ на задание недели: разобрал три источника и сравнил подходы.",
            "Бонусные баллы: 5\nКороткий бонусный текст.",
            "Не считать\nЭто служебное сообщение, его не нужно учитывать в статистике."};

    private final long now = System.currentTimeMillis() / 1000;
    private final List<long[]> comments = new ArrayList<>(); // {topicIdx, id, fromId, date, phraseIdx}

    public DemoVkTransport() {
        Random rnd = new Random(42);
        long id = 1;
        for (int t = 0; t < TOPICS.length; t++) {
            int n = 20 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                long date = now - (long) ((n - i) * (14.0 * 86400 / n)) + rnd.nextInt(3600);
                long from = rnd.nextInt(10) == 0 ? -GROUP_ID : Long.parseLong(USERS[rnd.nextInt(USERS.length)][0]);
                comments.add(new long[]{t, id++, from, Math.min(date, now - 60), rnd.nextInt(PHRASES.length)});
            }
        }
    }

    @Override
    public String call(String method, Map<String, String> p) {
        switch (method) {
            case "groups.getById": {
                String key = p.getOrDefault("group_id", "1");
                long id = key.matches("\\d+") ? Long.parseLong(key) : 1000 + Math.abs(key.hashCode() % 100000);
                String name = id == GROUP_ID ? "Демо-сообщество" : "Демо: " + key;
                return "{\"response\":{\"groups\":[{\"id\":" + id + ",\"name\":" + Json.quote(name)
                        + ",\"screen_name\":" + Json.quote(key) + ",\"is_closed\":2}],\"profiles\":[]}}";
            }
            case "users.get":
                return "{\"response\":[{\"id\":101,\"first_name\":\"Анна\",\"last_name\":\"Смирнова\"}]}";
            case "board.getTopics":
                return topics();
            case "board.getComments":
                return comments(Integer.parseInt(p.get("topic_id")), Integer.parseInt(p.getOrDefault("offset", "0")),
                        Integer.parseInt(p.getOrDefault("count", "20")), "desc".equals(p.get("sort")));
            default:
                return "{\"error\":{\"error_code\":3,\"error_msg\":\"Unknown method passed\"}}";
        }
    }

    private String topics() {
        StringBuilder sb = new StringBuilder("{\"response\":{\"count\":" + TOPICS.length + ",\"items\":[");
        for (int t = 0; t < TOPICS.length; t++) {
            long updated = 0;
            int cnt = 0;
            for (long[] c : comments) {
                if (c[0] == t) {
                    updated = Math.max(updated, c[3]);
                    cnt++;
                }
            }
            if (t > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(t + 1).append(",\"title\":").append(Json.quote(TOPICS[t]))
                    .append(",\"created\":").append(now - 30 * 86400).append(",\"updated\":").append(updated)
                    .append(",\"is_closed\":0,\"is_fixed\":").append(t == 0 ? 1 : 0)
                    .append(",\"comments\":").append(cnt).append('}');
        }
        return sb.append("]}}").toString();
    }

    private String comments(int topicId, int offset, int count, boolean desc) {
        List<long[]> list = new ArrayList<>();
        for (long[] c : comments) {
            if (c[0] == topicId - 1) {
                list.add(c);
            }
        }
        if (desc) {
            java.util.Collections.reverse(list);
        }
        StringBuilder sb = new StringBuilder("{\"response\":{\"count\":" + list.size() + ",\"items\":[");
        for (int i = offset; i < Math.min(list.size(), offset + count); i++) {
            long[] c = list.get(i);
            if (i > offset) {
                sb.append(',');
            }
            String text = PHRASES[(int) c[4]];
            if (c[1] % 7 == 0) {
                text = "[id101:bp-1_" + (c[1] - 1) + "|Анна], " + text;
            }
            sb.append("{\"id\":").append(c[1]).append(",\"from_id\":").append(c[2]).append(",\"date\":").append(c[3])
                    .append(",\"text\":").append(Json.quote(text))
                    .append(",\"likes\":{\"count\":").append(c[1] % 4).append('}');
            if (c[1] % 5 == 0) {
                sb.append(",\"attachments\":[{\"type\":\"photo\"}]");
            }
            sb.append('}');
        }
        sb.append("],\"profiles\":[");
        for (int i = 0; i < USERS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(USERS[i][0]).append(",\"first_name\":").append(Json.quote(USERS[i][1]))
                    .append(",\"last_name\":").append(Json.quote(USERS[i][2])).append('}');
        }
        sb.append("],\"groups\":[{\"id\":1,\"name\":\"Демо-сообщество\",\"screen_name\":\"club1\"}]}}");
        return sb.toString();
    }
}
