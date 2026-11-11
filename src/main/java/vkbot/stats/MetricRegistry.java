package vkbot.stats;

import vkbot.config.AppConfig;
import vkbot.config.ConfigException;
import vkbot.stats.metrics.BonusMetrics;
import vkbot.stats.metrics.RegexMetric;
import vkbot.stats.metrics.SimpleMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Создаёт список показателей по параметру {@code metrics} из config.properties. */
public final class MetricRegistry {

    private MetricRegistry() {
    }

    /** Встроенные метрики. Новую Java-метрику регистрируйте здесь. */
    public static final String DEFAULT_METRICS = "posts,bonus_posts,chars,weighted_chars,bonus_points";

    public static Map<String, Supplier<Metric>> builtIns() {
        return builtIns(500);
    }

    /** @param divisor делитель для «Бонусных баллов» */
    public static Map<String, Supplier<Metric>> builtIns(double divisor) {
        Map<String, Supplier<Metric>> m = new LinkedHashMap<>();
        m.put("posts", SimpleMetrics.PostCount::new);
        m.put("chars", SimpleMetrics.CharCount::new);
        m.put("avg_chars", SimpleMetrics.AvgChars::new);
        m.put("max_chars", SimpleMetrics.MaxChars::new);
        m.put("topics", SimpleMetrics.TopicCount::new);
        m.put("attachments", SimpleMetrics.Attachments::new);
        m.put("likes", SimpleMetrics.Likes::new);
        m.put("bonus_posts", BonusMetrics.BonusPosts::new);
        m.put("weighted_chars", BonusMetrics.WeightedChars::new);
        m.put("bonus_points", () -> new BonusMetrics.BonusPoints(divisor));
        return m;
    }

    public static List<Metric> fromConfig(AppConfig cfg) {
        return fromConfig(cfg, cfg.getInt("bonus.divisor", 500));
    }

    public static List<Metric> fromConfig(AppConfig cfg, double divisor) {
        Map<String, Supplier<Metric>> builtIns = builtIns(divisor);
        List<String> keys = cfg.getList("metrics", DEFAULT_METRICS);
        // regex-метрики, описанные в конфиге, но не перечисленные в metrics — добавляем в конец
        for (String custom : cfg.subKeys("metric")) {
            if (!keys.contains(custom)) {
                keys.add(custom);
            }
        }
        List<Metric> result = new ArrayList<>();
        for (String key : keys) {
            if (cfg.get("metric." + key + ".type", null) != null) {
                result.add(customMetric(cfg, key));
            } else if (builtIns.containsKey(key)) {
                result.add(builtIns.get(key).get());
            } else {
                throw new ConfigException("Неизвестная метрика '" + key + "' в параметре metrics. Доступны: "
                        + String.join(", ", builtIns.keySet()) + " или собственные metric.<ключ>.*");
            }
        }
        if (result.stream().noneMatch(m -> m.key().equals("posts"))) {
            result.add(0, new SimpleMetrics.PostCount()); // по количеству сообщений сортируем отчёт — оно нужно всегда
        }
        return result;
    }

    private static Metric customMetric(AppConfig cfg, String key) {
        String prefix = "metric." + key + ".";
        String type = cfg.get(prefix + "type", "regex");
        if (!type.equalsIgnoreCase("regex")) {
            throw new ConfigException("metric." + key + ".type: поддерживается только 'regex'");
        }
        String pattern = cfg.get(prefix + "pattern", null);
        if (pattern == null) {
            throw new ConfigException("Для метрики '" + key + "' не задан " + prefix + "pattern");
        }
        int flags = Pattern.UNICODE_CASE;
        if (cfg.getBool(prefix + "ignore_case", true)) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        Pattern p;
        try {
            p = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new ConfigException("Ошибка в регулярном выражении " + prefix + "pattern: " + e.getDescription());
        }
        RegexMetric.Aggregate agg = RegexMetric.parseAggregate(cfg.get(prefix + "aggregate", "count"));
        if (agg == null) {
            throw new ConfigException(prefix + "aggregate: допустимо count, sum, avg, min, max, list, distinct");
        }
        int group = cfg.getInt(prefix + "group", p.matcher("").groupCount() > 0 ? 1 : 0);
        return new RegexMetric(key, cfg.get(prefix + "title", key), p, group, agg,
                cfg.getBool(prefix + "per_post", true));
    }
}
