package vkbot.stats;

import vkbot.model.Author;
import vkbot.model.Post;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Группирует сообщения по авторам и считает по ним все метрики. */
public final class StatisticsBuilder {

    private static final Logger LOG = Logger.getLogger(StatisticsBuilder.class.getName());

    private final List<Metric> metrics;

    public StatisticsBuilder(List<Metric> metrics) {
        this.metrics = metrics;
    }

    public List<AuthorStats> byAuthor(List<Post> posts, LongFunction<Author> authorLookup) {
        Map<Long, List<Post>> grouped = new LinkedHashMap<>();
        for (Post p : posts) {
            grouped.computeIfAbsent(p.getAuthorId(), k -> new ArrayList<>()).add(p);
        }
        List<AuthorStats> result = new ArrayList<>();
        for (Map.Entry<Long, List<Post>> e : grouped.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Metric m : metrics) {
                values.put(m.key(), safe(m, () -> m.aggregate(e.getValue())));
            }
            result.add(new AuthorStats(authorLookup.apply(e.getKey()), e.getValue(), values));
        }
        result.sort(Comparator.comparingLong((AuthorStats a) -> a.number("posts")).reversed()
                .thenComparing(Comparator.comparingLong((AuthorStats a) -> a.number("chars")).reversed())
                .thenComparing(a -> a.getAuthor().getName()));
        return result;
    }

    public Map<String, Object> totals(List<Post> posts) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Metric m : metrics) {
            values.put(m.key(), safe(m, () -> m.total(posts)));
        }
        return values;
    }

    private interface Calc {
        Object get();
    }

    /** Ошибка в одной метрике не должна ронять весь отчёт. */
    private static Object safe(Metric m, Calc calc) {
        try {
            return calc.get();
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Ошибка при расчёте метрики '" + m.key() + "'", ex);
            return "ошибка";
        }
    }
}
