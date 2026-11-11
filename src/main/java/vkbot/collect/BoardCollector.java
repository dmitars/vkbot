package vkbot.collect;

import vkbot.json.JsonNode;
import vkbot.model.Community;
import vkbot.model.Post;
import vkbot.model.Topic;
import vkbot.vk.VkApiException;
import vkbot.vk.VkClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Собирает все сообщения во всех обсуждениях сообщества за период [from; to). */
public final class BoardCollector {

    private static final Logger LOG = Logger.getLogger(BoardCollector.class.getName());

    private final VkClient vk;
    private final TextNormalizer normalizer;
    private final BonusRule bonusRule;
    private SkipRule skipRule;
    private final Set<Long> onlyTopics;
    private final Set<Long> excludeTopics;
    private final boolean skipFailedTopics;

    public BoardCollector(VkClient vk, TextNormalizer normalizer, Set<Long> onlyTopics,
                          Set<Long> excludeTopics, boolean skipFailedTopics) {
        this(vk, normalizer, null, onlyTopics, excludeTopics, skipFailedTopics);
    }

    public BoardCollector(VkClient vk, TextNormalizer normalizer, BonusRule bonusRule, Set<Long> onlyTopics,
                          Set<Long> excludeTopics, boolean skipFailedTopics) {
        this.vk = vk;
        this.normalizer = normalizer;
        this.bonusRule = bonusRule;
        this.onlyTopics = onlyTopics;
        this.excludeTopics = excludeTopics;
        this.skipFailedTopics = skipFailedTopics;
    }

    /** Сообщения с пометкой «не считать» в первой строке будут пропускаться. */
    public BoardCollector withSkipRule(SkipRule rule) {
        this.skipRule = rule;
        return this;
    }

    public static final class Result {
        public final List<Topic> scannedTopics = new ArrayList<>();
        public final List<Topic> failedTopics = new ArrayList<>();
        public final List<Post> posts = new ArrayList<>();
        public int totalTopics;
        /** Сколько сообщений за период пропущено по пометке «не считать». */
        public int skipped;
    }

    public Result collect(Community community, Instant from, Instant to) {
        Result result = new Result();
        long groupId = community.getId();

        List<Topic> all = vk.getAllTopics(groupId);
        result.totalTopics = all.size();
        LOG.info("Всего обсуждений в сообществе: " + all.size());

        List<Topic> candidates = new ArrayList<>();
        for (Topic t : all) {
            if (!onlyTopics.isEmpty() && !onlyTopics.contains(t.getId())) {
                continue;
            }
            if (excludeTopics.contains(t.getId())) {
                continue;
            }
            // Тема, не обновлявшаяся с начала периода, не может содержать новых сообщений
            if (t.getUpdated().isBefore(from)) {
                continue;
            }
            candidates.add(t);
        }
        LOG.info("Обсуждений с активностью за период: " + candidates.size());

        int n = 0;
        for (Topic topic : candidates) {
            n++;
            LOG.info(String.format("[%d/%d] «%s»", n, candidates.size(), topic.getTitle()));
            try {
                int before = result.posts.size();
                result.skipped += collectTopic(groupId, topic, from, to, result.posts);
                result.scannedTopics.add(topic);
                LOG.fine("   сообщений за период: " + (result.posts.size() - before));
            } catch (VkApiException e) {
                if (!skipFailedTopics) {
                    throw e;
                }
                result.failedTopics.add(topic);
                LOG.severe("Не удалось прочитать обсуждение «" + topic.getTitle() + "» (id " + topic.getId()
                        + "): " + e.getMessage() + " → обсуждение пропущено");
            }
        }
        result.posts.sort(Comparator.comparing(Post::getDate));
        return result;
    }

    /** @return сколько сообщений пропущено по пометке «не считать» */
    private int collectTopic(long groupId, Topic topic, Instant from, Instant to, List<Post> out) {
        Set<Long> seen = new HashSet<>(); // защита от дублей при сдвиге offset во время чтения
        int[] skipped = {0};
        vk.forEachCommentPage(groupId, topic.getId(), items -> {
            Instant first = null;
            Instant last = null;
            for (JsonNode c : items) {
                Instant date = Instant.ofEpochSecond(c.get("date").asLong());
                if (first == null) {
                    first = date;
                }
                last = date;
                if (date.isBefore(from) || !date.isBefore(to)) {
                    continue;
                }
                long id = c.get("id").asLong();
                if (!seen.add(id)) {
                    continue;
                }
                String text = c.get("text").asText();
                if (skipRule != null && skipRule.skip(text)) {
                    // «не считать»: сообщение остаётся в активности (число сообщений, обсуждения),
                    // но без символов и бонусных баллов
                    skipped[0]++;
                    out.add(new Post(id, groupId, topic, c.get("from_id").asLong(), date,
                            text, normalizer.normalize(skipRule.stripMarker(text)), c, false, 0, true));
                    continue;
                }
                if (bonusRule != null) {
                    BonusRule.Analysis a = bonusRule.analyze(text);
                    out.add(new Post(id, groupId, topic, c.get("from_id").asLong(), date,
                            text, a.countedText, c, a.bonus, a.weight));
                } else {
                    out.add(new Post(id, groupId, topic, c.get("from_id").asLong(), date,
                            text, normalizer.normalize(text), c));
                }
            }
            if (first == null) {
                return false;
            }
            boolean descending = !first.isBefore(last);
            // Если страница отсортирована по убыванию и уже дошли до сообщений старше периода — дальше не листаем.
            // Если VK вдруг вернул по возрастанию — читаем тему целиком (медленнее, но корректно).
            return !(descending && last.isBefore(from));
        });
        return skipped[0];
    }
}
