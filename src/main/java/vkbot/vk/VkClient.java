package vkbot.vk;

import vkbot.json.Json;
import vkbot.json.JsonNode;
import vkbot.model.Author;
import vkbot.model.Community;
import vkbot.model.Topic;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Обёртка над VK API: токен, версия, ограничение частоты запросов, повторы при временных ошибках,
 * разбор ответов и кэш имён авторов.
 */
public final class VkClient {

    private static final Logger LOG = Logger.getLogger(VkClient.class.getName());
    public static final int PAGE_SIZE = 100; // максимум для board.getTopics / board.getComments

    private final VkTransport transport;
    private final String token;
    private final String version;
    private final long minDelayMs;
    private final int maxRetries;
    private final Map<Long, Author> authors = new HashMap<>();

    private long lastCallAt;
    private int callCount;

    public VkClient(VkTransport transport, String token, String version, long minDelayMs, int maxRetries) {
        this.transport = transport;
        this.token = token;
        this.version = version;
        this.minDelayMs = minDelayMs;
        this.maxRetries = maxRetries;
    }

    public int getCallCount() {
        return callCount;
    }

    /** Вызов метода; возвращает содержимое поля "response". */
    public JsonNode call(String method, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("access_token", token);
        p.put("v", version);

        int attempt = 0;
        while (true) {
            attempt++;
            throttle();
            try {
                LOG.fine(() -> "→ " + method + " " + params);
                callCount++;
                String body = transport.call(method, p);
                JsonNode root = Json.parse(body);
                JsonNode err = root.get("error");
                if (!err.isMissing()) {
                    throw new VkApiException(method, (int) err.get("error_code").asLong(-1),
                            err.get("error_msg").asText("неизвестная ошибка"));
                }
                return root.get("response");
            } catch (VkApiException e) {
                if (!e.isRetryable() || attempt > maxRetries) {
                    throw e;
                }
                LOG.warning("Временная ошибка VK (" + e.getCode() + "), повтор " + attempt + "/" + maxRetries);
                sleep(backoff(attempt));
            } catch (IOException | IllegalArgumentException e) {
                if (attempt > maxRetries) {
                    throw new VkNetworkException("Не удалось выполнить " + method + " после " + maxRetries
                            + " повторов: " + e.getMessage(), e);
                }
                LOG.log(Level.WARNING, "Сетевая ошибка при вызове " + method + " (" + e.getMessage()
                        + "), повтор " + attempt + "/" + maxRetries);
                sleep(backoff(attempt));
            }
        }
    }

    // ---------------- Сообщество ----------------

    /** Принимает числовой id ("123"), "club123", "public123", короткое имя или ссылку vk.com/... */
    public Community resolveCommunity(String idOrName) {
        String key = idOrName.trim();
        key = key.replaceFirst("^https?://", "").replaceFirst("^(m\\.)?vk\\.(com|ru)/", "");
        if (key.startsWith("-")) {
            key = key.substring(1);
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("group_id", key);
        JsonNode resp = call("groups.getById", p);
        // В новых версиях API: {"groups":[...]}; в старых — сразу массив
        JsonNode g = resp.has("groups") ? resp.get("groups").get(0) : resp.get(0);
        if (g.isMissing()) {
            throw new VkApiException("groups.getById", 100, "сообщество '" + idOrName + "' не найдено");
        }
        return new Community(g.get("id").asLong(), g.get("name").asText(),
                g.get("screen_name").asText(null), (int) g.get("is_closed").asLong(0));
    }

    // ---------------- Темы ----------------

    /** Все темы сообщества (постранично). */
    public List<Topic> getAllTopics(long groupId) {
        List<Topic> topics = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("group_id", String.valueOf(groupId));
            p.put("order", "1"); // по дате обновления, по убыванию
            p.put("count", String.valueOf(PAGE_SIZE));
            p.put("offset", String.valueOf(offset));
            p.put("preview", "0");
            JsonNode resp = call("board.getTopics", p);
            long total = resp.get("count").asLong();
            JsonNode items = resp.get("items");
            for (JsonNode t : items) {
                topics.add(new Topic(
                        t.get("id").asLong(),
                        t.get("title").asText(),
                        Instant.ofEpochSecond(t.get("created").asLong()),
                        Instant.ofEpochSecond(t.get("updated").asLong()),
                        t.get("is_closed").asBoolean(),
                        t.get("is_fixed").asBoolean(),
                        t.get("comments").asLong()));
            }
            offset += items.size();
            if (items.size() == 0 || offset >= total) {
                return topics;
            }
        }
    }

    // ---------------- Комментарии ----------------

    /** Обработчик страницы комментариев. Вернуть false, чтобы прекратить листание. */
    public interface PageHandler {
        boolean onPage(JsonNode items);
    }

    /**
     * Листает комментарии темы от новых к старым (sort=desc), передавая каждую страницу обработчику.
     * Попутно запоминает имена авторов из profiles/groups (extended=1).
     */
    public void forEachCommentPage(long groupId, long topicId, PageHandler handler) {
        int offset = 0;
        while (true) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("group_id", String.valueOf(groupId));
            p.put("topic_id", String.valueOf(topicId));
            p.put("count", String.valueOf(PAGE_SIZE));
            p.put("offset", String.valueOf(offset));
            p.put("sort", "desc");
            p.put("extended", "1");
            p.put("need_likes", "1");
            JsonNode resp = call("board.getComments", p);
            rememberAuthors(resp);
            JsonNode items = resp.get("items");
            long total = resp.get("count").asLong();
            boolean more = handler.onPage(items);
            offset += items.size();
            if (!more || items.size() == 0 || offset >= total) {
                return;
            }
        }
    }

    // ---------------- Авторы ----------------

    private void rememberAuthors(JsonNode resp) {
        for (JsonNode u : resp.get("profiles")) {
            long id = u.get("id").asLong();
            String name = (u.get("first_name").asText() + " " + u.get("last_name").asText()).trim();
            authors.put(id, new Author(id, name.isEmpty() ? "id" + id : name, u.get("screen_name").asText(null)));
        }
        for (JsonNode g : resp.get("groups")) {
            long id = -g.get("id").asLong();
            authors.put(id, new Author(id, g.get("name").asText(), g.get("screen_name").asText(null)));
        }
    }

    public Author author(long id) {
        return authors.computeIfAbsent(id, Author::unknown);
    }

    // ---------------- служебное ----------------

    private void throttle() {
        long wait = lastCallAt + minDelayMs - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static long backoff(int attempt) {
        return Math.min(30_000L, 1000L * (1L << Math.min(attempt, 5)));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Прервано", e);
        }
    }
}
