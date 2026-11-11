package vkbot.stats;

import vkbot.model.Community;
import vkbot.model.Post;
import vkbot.model.Topic;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Готовые данные отчёта — их потребляют «писатели» (HTML, консоль и т.п.). */
public final class Report {
    public final Community community;
    public final Instant from;
    public final Instant to;
    public final Instant generatedAt;
    public final ZoneId zone;
    public final List<Metric> metrics;
    public final List<AuthorStats> authors;
    public final Map<String, Object> totals;
    public final List<Post> posts;
    public final List<Topic> scannedTopics;
    public final List<Topic> failedTopics;
    public final int totalTopics;

    public Report(Community community, Instant from, Instant to, Instant generatedAt, ZoneId zone,
                  List<Metric> metrics, List<AuthorStats> authors, Map<String, Object> totals,
                  List<Post> posts, List<Topic> scannedTopics, List<Topic> failedTopics, int totalTopics) {
        this.community = community;
        this.from = from;
        this.to = to;
        this.generatedAt = generatedAt;
        this.zone = zone;
        this.metrics = metrics;
        this.authors = authors;
        this.totals = totals;
        this.posts = posts;
        this.scannedTopics = scannedTopics;
        this.failedTopics = failedTopics;
        this.totalTopics = totalTopics;
    }
}
