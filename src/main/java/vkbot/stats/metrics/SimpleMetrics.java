package vkbot.stats.metrics;

import vkbot.model.Post;
import vkbot.stats.Metric;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Встроенные показатели. Каждый — маленький класс, пример для собственных метрик. */
public final class SimpleMetrics {

    private SimpleMetrics() {
    }

    /** Количество сообщений. */
    public static final class PostCount implements Metric {
        public String key() { return "posts"; }
        public String title() { return "Сообщений"; }
        public Object aggregate(List<Post> posts) { return posts.size(); }
    }

    /** Символов в сообщении / суммарно. */
    public static final class CharCount implements Metric {
        public String key() { return "chars"; }
        public String title() { return "Символов"; }
        public Object postValue(Post p) { return p.chars(); }
        public Object aggregate(List<Post> posts) {
            long sum = 0;
            for (Post p : posts) {
                sum += p.chars();
            }
            return sum;
        }
    }

    /** Среднее число символов в сообщении. */
    public static final class AvgChars implements Metric {
        public String key() { return "avg_chars"; }
        public String title() { return "Среднее, симв."; }
        public Object aggregate(List<Post> posts) {
            long sum = 0;
            int n = 0;
            for (Post p : posts) {
                if (!p.isSkipped()) {
                    sum += p.chars();
                    n++;
                }
            }
            return n == 0 ? 0 : Math.round((double) sum / n);
        }
    }

    /** Самое длинное сообщение. */
    public static final class MaxChars implements Metric {
        public String key() { return "max_chars"; }
        public String title() { return "Макс., симв."; }
        public Object aggregate(List<Post> posts) {
            int max = 0;
            for (Post p : posts) {
                max = Math.max(max, p.chars());
            }
            return max;
        }
    }

    /** В скольких разных обсуждениях писал автор. */
    public static final class TopicCount implements Metric {
        public String key() { return "topics"; }
        public String title() { return "Обсуждений"; }
        public Object aggregate(List<Post> posts) {
            Set<Long> ids = new HashSet<>();
            for (Post p : posts) {
                ids.add(p.getTopic().getId());
            }
            return ids.size();
        }
    }

    /** Количество вложений (фото, документы, ...) — пример чтения сырого JSON сообщения. */
    public static final class Attachments implements Metric {
        public String key() { return "attachments"; }
        public String title() { return "Вложений"; }
        public Object postValue(Post p) { return p.getRaw().get("attachments").size(); }
        public Object aggregate(List<Post> posts) {
            long sum = 0;
            for (Post p : posts) {
                sum += p.getRaw().get("attachments").size();
            }
            return sum;
        }
    }

    /** Полученные лайки. */
    public static final class Likes implements Metric {
        public String key() { return "likes"; }
        public String title() { return "Лайков"; }
        public Object postValue(Post p) { return p.getRaw().get("likes").get("count").asLong(); }
        public Object aggregate(List<Post> posts) {
            long sum = 0;
            for (Post p : posts) {
                sum += p.getRaw().get("likes").get("count").asLong();
            }
            return sum;
        }
    }
}
