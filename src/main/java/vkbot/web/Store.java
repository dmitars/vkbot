package vkbot.web;

import vkbot.json.Json;
import vkbot.json.JsonNode;
import vkbot.json.JsonOut;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Список сообществ, их периоды и делитель — хранятся в communities.json рядом с config.properties. */
public final class Store {

    public static final class Entry {
        public long id;
        public String name;
        public String screenName;
        public int closedType;
        public LocalDate from;
        public LocalDate to;
        public boolean enabled = true;
        /** Обсуждения сообщества (id → название), как их последний раз вернул VK. null — ещё не загружались. */
        public LinkedHashMap<Long, String> topics;
        /** Обсуждения, которые пользователь снял с подсчёта. Новые обсуждения по умолчанию считаются. */
        public final java.util.Set<Long> excludedTopics = new java.util.LinkedHashSet<>();

        /** Для сохранения на диск — без дат. */
        Map<String, Object> toStoredMap() {
            Map<String, Object> m = toMap();
            m.remove("from");
            m.remove("to");
            m.remove("url");
            return m;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("screen_name", screenName);
            m.put("closed", closedType);
            m.put("from", from.toString());
            m.put("to", to.toString());
            m.put("enabled", enabled);
            if (topics != null) {
                List<Object> tl = new ArrayList<>();
                for (Map.Entry<Long, String> t : topics.entrySet()) {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id", t.getKey());
                    tm.put("title", t.getValue());
                    tm.put("enabled", !excludedTopics.contains(t.getKey()));
                    tl.add(tm);
                }
                m.put("topics", tl);
            }
            m.put("excludedTopics", new ArrayList<>(excludedTopics));
            m.put("url", screenName != null && !screenName.isEmpty()
                    ? "https://vk.com/" + screenName : "https://vk.com/club" + id);
            return m;
        }
    }

    private final Path file;
    private final LocalDate defaultFrom;
    private final LocalDate defaultTo;
    private final List<Entry> entries = new ArrayList<>();
    private double divisor = 500;
    /** ID приложения VK для получения ключа (вводится на странице, запоминается). */
    private String clientId = "";
    /** true — VK возвращает ключ прямо на http://localhost:порт/callback; false — на oauth.vk.com/blank.html. */
    private boolean autoRedirect;
    /** Запрашиваемые права (scope) для ключа, например "groups". Пусто — без дополнительных прав. */
    private String scope = "";

    /**
     * @param defaultFrom/defaultTo период, который ставится всем сообществам при запуске:
     *                              даты между запусками не сохраняются (выбираются заново каждый раз)
     */
    public Store(Path file, LocalDate defaultFrom, LocalDate defaultTo, double defaultDivisor) throws IOException {
        this.file = file;
        this.defaultFrom = defaultFrom;
        this.defaultTo = defaultTo;
        this.divisor = defaultDivisor;
        load();
    }

    public LocalDate defaultFrom() {
        return defaultFrom;
    }

    public LocalDate defaultTo() {
        return defaultTo;
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized double divisor() {
        return divisor;
    }

    public synchronized String clientId() {
        return clientId;
    }

    public synchronized boolean autoRedirect() {
        return autoRedirect;
    }

    public synchronized String scope() {
        return scope;
    }

    public synchronized void setScope(String s) throws IOException {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", "");
        if (!v.isEmpty() && !v.matches("[a-z_,0-9]+")) {
            throw new IllegalArgumentException("Права (scope) — слова через запятую, например groups или groups,offline");
        }
        scope = v;
        save();
    }

    public synchronized void setAuthSettings(String clientId, Boolean autoRedirect) throws IOException {
        if (clientId != null) {
            String c = clientId.trim();
            if (!c.isEmpty() && !c.matches("\\d{1,12}")) {
                throw new IllegalArgumentException("ID приложения — это число, например 1234567");
            }
            this.clientId = c;
        }
        if (autoRedirect != null) {
            this.autoRedirect = autoRedirect;
        }
        save();
    }

    public synchronized void setDivisor(double d) throws IOException {
        if (!(d > 0)) {
            throw new IllegalArgumentException("Делитель должен быть больше нуля");
        }
        divisor = d;
        save();
    }

    public synchronized Entry find(long id) {
        for (Entry e : entries) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    public synchronized void add(Entry e) throws IOException {
        if (find(e.id) != null) {
            throw new IllegalArgumentException("Сообщество «" + e.name + "» уже в списке");
        }
        entries.add(e);
        save();
    }

    public synchronized void remove(long id) throws IOException {
        entries.removeIf(e -> e.id == id);
        save();
    }

    public synchronized void update(long id, LocalDate from, LocalDate to, Boolean enabled) throws IOException {
        Entry e = find(id);
        if (e == null) {
            throw new IllegalArgumentException("Сообщество не найдено");
        }
        if (from != null) {
            e.from = from;
        }
        if (to != null) {
            e.to = to;
        }
        if (enabled != null) {
            e.enabled = enabled;
            save(); // на диск идут только список и галочки; даты живут до закрытия программы
        }
    }

    /** Обновить список обсуждений из VK. Снятые галочки у сохранившихся обсуждений остаются. */
    public synchronized void setTopics(long id, LinkedHashMap<Long, String> topics) throws IOException {
        Entry e = require(id);
        e.topics = topics;
        e.excludedTopics.retainAll(topics.keySet());
        save();
    }

    /** topicId == null — применить ко всем обсуждениям сообщества. */
    public synchronized void setTopicEnabled(long id, Long topicId, boolean enabled) throws IOException {
        Entry e = require(id);
        java.util.Collection<Long> ids = topicId != null ? java.util.List.of(topicId)
                : e.topics != null ? e.topics.keySet() : java.util.List.of();
        for (Long t : ids) {
            if (enabled) {
                e.excludedTopics.remove(t);
            } else {
                e.excludedTopics.add(t);
            }
        }
        save();
    }

    private Entry require(long id) {
        Entry e = find(id);
        if (e == null) {
            throw new IllegalArgumentException("Сообщество не найдено");
        }
        return e;
    }

    public synchronized List<Object> toJson() {
        List<Object> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(e.toMap());
        }
        return out;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonNode root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        clientId = root.get("clientId").asText(clientId);
        autoRedirect = root.get("autoRedirect").asBoolean();
        scope = root.get("scope").asText("");
        double d = Double.parseDouble(root.get("divisor").asText(String.valueOf(divisor)));
        divisor = d > 0 ? d : divisor;
        for (JsonNode n : root.get("communities")) {
            Entry e = new Entry();
            e.id = n.get("id").asLong();
            e.name = n.get("name").asText("club" + e.id);
            e.screenName = n.get("screen_name").asText(null);
            e.closedType = (int) n.get("closed").asLong(0);
            e.from = defaultFrom;
            e.to = defaultTo;
            e.enabled = n.get("enabled").isMissing() || n.get("enabled").asBoolean();
            if (n.get("topics").isArray()) {
                e.topics = new LinkedHashMap<>();
                for (JsonNode t : n.get("topics")) {
                    e.topics.put(t.get("id").asLong(), t.get("title").asText(""));
                }
            }
            for (JsonNode x : n.get("excludedTopics")) {
                e.excludedTopics.add(x.asLong());
            }
            entries.add(e);
        }
    }

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("divisor", divisor);
        root.put("clientId", clientId);
        root.put("autoRedirect", autoRedirect);
        root.put("scope", scope);
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            list.add(e.toStoredMap());
        }
        root.put("communities", list);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, JsonOut.write(root).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
