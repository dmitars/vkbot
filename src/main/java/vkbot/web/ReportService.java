package vkbot.web;

import vkbot.collect.BoardCollector;
import vkbot.collect.BonusRule;
import vkbot.collect.SkipRule;
import vkbot.collect.TextNormalizer;
import vkbot.config.AppConfig;
import vkbot.model.Author;
import vkbot.model.Community;
import vkbot.model.Post;
import vkbot.model.Topic;
import vkbot.stats.AuthorStats;
import vkbot.stats.Metric;
import vkbot.stats.MetricRegistry;
import vkbot.stats.StatisticsBuilder;
import vkbot.vk.VkApiException;
import vkbot.vk.VkClient;
import vkbot.vk.VkTransport;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Подсчёт статистики по выбранным сообществам в фоне, с журналом для страницы. */
public final class ReportService {

    private static final Logger LOG = Logger.getLogger(ReportService.class.getName());
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AppConfig cfg;
    private final Supplier<VkTransport> transport;
    private final AuthService auth;

    private final List<String> log = new ArrayList<>();
    private volatile boolean running;
    private volatile String error;
    private volatile Map<String, Object> result;

    public ReportService(AppConfig cfg, Supplier<VkTransport> transport, AuthService auth) {
        this.cfg = cfg;
        this.transport = transport;
        this.auth = auth;
        // всё, что пишут сборщик и клиент VK, показываем и на странице
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (running && r.getLevel().intValue() >= Level.INFO.intValue()) {
                    String prefix = r.getLevel().intValue() >= Level.SEVERE.intValue() ? "ОШИБКА: "
                            : r.getLevel().intValue() >= Level.WARNING.intValue() ? "Внимание: " : "";
                    synchronized (log) {
                        log.add(prefix + r.getMessage());
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    public synchronized void start(List<Store.Entry> communities, double divisor) {
        if (running) {
            throw new IllegalStateException("Подсчёт уже идёт");
        }
        if (communities.isEmpty()) {
            throw new IllegalArgumentException("Не выбрано ни одного сообщества");
        }
        for (Store.Entry e : communities) {
            if (e.topics != null && !e.topics.isEmpty() && e.excludedTopics.containsAll(e.topics.keySet())) {
                throw new IllegalArgumentException("У сообщества «" + e.name + "» не отмечено ни одного обсуждения. "
                        + "Отметьте обсуждения или снимите галочку с самого сообщества.");
            }
            if (e.from.isAfter(e.to)) {
                throw new IllegalArgumentException("У сообщества «" + e.name + "» дата начала позже даты конца");
            }
        }
        synchronized (log) {
            log.clear();
        }
        error = null;
        result = null;
        running = true;
        Thread t = new Thread(() -> {
            try {
                result = run(communities, divisor);
                LOG.info("Готово.");
            } catch (AuthService.NeedTokenException e) {
                error = e.getMessage();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Подсчёт прерван: " + e.getMessage(), e);
                error = e.getMessage();
            } finally {
                running = false;
            }
        }, "report");
        t.setDaemon(true);
        t.start();
    }

    public Map<String, Object> status(int since) {
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (log) {
            int from = Math.max(0, Math.min(since, log.size()));
            m.put("log", new ArrayList<>(log.subList(from, log.size())));
            m.put("next", log.size());
        }
        m.put("running", running);
        m.put("error", error);
        if (!running) {
            m.put("result", result);
        }
        return m;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> run(List<Store.Entry> entries, double divisor) throws Exception {
        ZoneId zone = cfg.zone();
        List<Metric> metrics = MetricRegistry.fromConfig(cfg, divisor);
        TextNormalizer normalizer = new TextNormalizer(
                cfg.getBool("text.strip_reply_mentions", true));
        BonusRule bonus = new BonusRule(cfg.get("bonus.marker_regex", BonusRule.DEFAULT_MARKER),
                cfg.getInt("bonus.multiplier", 2), normalizer);
        VkClient vk = new VkClient(transport.get(), auth.token(), cfg.apiVersion(),
                "demo".equals(auth.status().get("mode")) ? 0 : cfg.getInt("vk.request_delay_ms", 350),
                cfg.getInt("vk.max_retries", 5));
        SkipRule skipRule = new SkipRule(cfg.get("skip.marker_regex", SkipRule.DEFAULT_MARKER));
        StatisticsBuilder stats = new StatisticsBuilder(metrics);

        List<Object> perCommunity = new ArrayList<>();
        List<Post> allPosts = new ArrayList<>();
        Map<Long, String> communityNames = new HashMap<>();
        int n = 0;
        for (Store.Entry e : entries) {
            n++;
            Instant from = e.from.atStartOfDay(zone).toInstant();
            Instant to = e.to.plusDays(1).atStartOfDay(zone).toInstant();
            LOG.info("=== [" + n + "/" + entries.size() + "] " + e.name + ": "
                    + e.from + " — " + e.to + " ===");
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", e.id);
            cm.put("name", e.name);
            cm.put("from", e.from.toString());
            cm.put("to", e.to.toString());
            cm.put("url", e.toMap().get("url"));
            communityNames.put(e.id, e.name);
            try {
                Community c = new Community(e.id, e.name, e.screenName, e.closedType);
                // обсуждения, снятые пользователем, + общие исключения из config.properties
                java.util.Set<Long> excluded = new java.util.HashSet<>(cfg.getLongSet("topics.exclude_ids"));
                excluded.addAll(e.excludedTopics);
                if (!e.excludedTopics.isEmpty()) {
                    LOG.info("Не считаются обсуждения (снятые галочки): " + e.excludedTopics.size());
                }
                BoardCollector collector = new BoardCollector(vk, normalizer, bonus,
                        cfg.getLongSet("topics.only_ids"), excluded, cfg.getBool("topics.skip_on_error", true))
                        .withSkipRule(skipRule);
                cm.put("excludedTopics", e.excludedTopics.size());
                BoardCollector.Result r = collector.collect(c, from, to);
                List<String> failed = new ArrayList<>();
                for (Topic t : r.failedTopics) {
                    failed.add(t.getTitle());
                }
                cm.put("topicsTotal", r.totalTopics);
                cm.put("failedTopics", failed);
                List<AuthorStats> byAuthor = stats.byAuthor(r.posts, vk::author);
                cm.put("authors", authorsJson(byAuthor, metrics, zone, communityNames, false));
                cm.put("totals", stats.totals(r.posts));
                cm.put("topics", topicsJson(r.posts, e.id));
                cm.put("skipped", r.skipped);
                if (r.skipped > 0) {
                    LOG.info("Сообщений с пометкой «не считать» (без символов и баллов): " + r.skipped);
                }
                LOG.info("Сообщений: " + r.posts.size() + ", авторов: " + byAuthor.size());
                allPosts.addAll(r.posts);
            } catch (VkApiException ex) {
                if (ex.getCode() == 5) {
                    auth.invalidate();
                }
                LOG.severe(e.name + ": " + ex.getMessage());
                cm.put("error", ex.getMessage());
            }
            perCommunity.add(cm);
        }

        Map<String, Object> combined = new LinkedHashMap<>();
        List<AuthorStats> byAuthor = stats.byAuthor(allPosts, vk::author);
        combined.put("authors", authorsJson(byAuthor, metrics, zone, communityNames, true));
        combined.put("totals", stats.totals(allPosts));

        List<Object> metricList = new ArrayList<>();
        for (Metric m : metrics) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("key", m.key());
            mm.put("title", m.title());
            mm.put("perPost", !allPosts.isEmpty() && allPosts.stream().limit(500).anyMatch(p -> safePost(m, p) != null));
            metricList.add(mm);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("generatedAt", DT.format(Instant.now().atZone(zone)));
        res.put("timezone", zone.getId());
        res.put("divisor", divisor);
        res.put("metrics", metricList);
        res.put("communities", perCommunity);
        res.put("combined", combined);
        return res;
    }

    private List<Object> authorsJson(List<AuthorStats> list, List<Metric> metrics, ZoneId zone,
                                     Map<Long, String> communityNames, boolean withCommunity) {
        int previewLength = cfg.getInt("report.preview_length", 150);
        List<Object> out = new ArrayList<>();
        for (AuthorStats a : list) {
            Author au = a.getAuthor();
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", au.getId());
            am.put("name", au.getName());
            am.put("url", au.getUrl());
            am.put("isCommunity", au.isCommunity());
            am.put("values", new LinkedHashMap<>(a.getValues()));
            List<Object> posts = new ArrayList<>();
            for (Post p : a.getPosts()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("date", DT.format(p.getDate().atZone(zone)));
                pm.put("ts", p.getDate().getEpochSecond());
                pm.put("topic", p.getTopic().getTitle());
                if (withCommunity) {
                    pm.put("community", communityNames.get(p.getGroupId()));
                }
                pm.put("url", p.getUrl());
                pm.put("bonus", p.isBonus() && !p.isSkipped());
                pm.put("skipped", p.isSkipped());
                pm.put("preview", preview(p.getCountedText(), previewLength));
                Map<String, Object> vals = new LinkedHashMap<>();
                for (Metric m : metrics) {
                    Object v = safePost(m, p);
                    if (v != null) {
                        vals.put(m.key(), v);
                    }
                }
                pm.put("values", vals);
                posts.add(pm);
            }
            am.put("posts", posts);
            out.add(am);
        }
        return out;
    }

    /** Активность по обсуждениям за период: сообщений, авторов, символов. Самое активное — первое. */
    private static List<Object> topicsJson(List<Post> posts, long groupId) {
        Map<Long, List<Post>> byTopic = new LinkedHashMap<>();
        for (Post p : posts) {
            byTopic.computeIfAbsent(p.getTopic().getId(), k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Post> list : byTopic.values()) {
            Topic t = list.get(0).getTopic();
            long chars = 0;
            java.util.Set<Long> authors = new java.util.HashSet<>();
            for (Post p : list) {
                chars += p.chars();
                authors.add(p.getAuthorId());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("url", "https://vk.com/topic-" + groupId + "_" + t.getId());
            m.put("posts", list.size());
            m.put("authors", authors.size());
            m.put("chars", chars);
            out.add(m);
        }
        // по числу сообщений, при равенстве — по символам
        out.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("posts"), (Integer) a.get("posts"));
            return c != 0 ? c : Long.compare((Long) b.get("chars"), (Long) a.get("chars"));
        });
        return new ArrayList<>(out);
    }

    private static Object safePost(Metric m, Post p) {
        try {
            return m.postValue(p);
        } catch (RuntimeException e) {
            return "ошибка";
        }
    }

    private static String preview(String text, int len) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) <= len) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, len)) + "…";
    }
}
