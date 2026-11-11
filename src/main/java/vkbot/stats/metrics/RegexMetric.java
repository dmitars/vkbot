package vkbot.stats.metrics;

import vkbot.model.Post;
import vkbot.stats.Metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Метрика, описываемая прямо в config.properties: ищет в тексте сообщения регулярное выражение
 * и агрегирует найденное. Пример:
 * <pre>
 * metric.hours.type=regex
 * metric.hours.title=Часы
 * metric.hours.pattern=Часов:\s*(\d+(?:[.,]\d+)?)
 * metric.hours.aggregate=sum
 * </pre>
 * aggregate: count — число совпадений; sum/avg/min/max — над числами из группы;
 * list — все найденные значения; distinct — уникальные значения.
 */
public final class RegexMetric implements Metric {

    public enum Aggregate { COUNT, SUM, AVG, MIN, MAX, LIST, DISTINCT }

    private final String key;
    private final String title;
    private final Pattern pattern;
    private final int group;
    private final Aggregate aggregate;
    private final boolean showPerPost;

    public RegexMetric(String key, String title, Pattern pattern, int group, Aggregate aggregate, boolean showPerPost) {
        this.key = key;
        this.title = title;
        this.pattern = pattern;
        this.group = group;
        this.aggregate = aggregate;
        this.showPerPost = showPerPost;
    }

    public String key() { return key; }
    public String title() { return title; }

    private List<String> matches(Post p) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(p.getText());
        while (m.find()) {
            String v = group <= m.groupCount() ? m.group(group) : m.group();
            if (v != null) {
                out.add(v.trim());
            }
        }
        return out;
    }

    private static List<BigDecimal> numbers(List<String> values) {
        List<BigDecimal> out = new ArrayList<>();
        for (String v : values) {
            try {
                out.add(new BigDecimal(v.replace(',', '.').replaceAll("[\\s\\u00A0]", "")));
            } catch (NumberFormatException ignored) {
                // не число — пропускаем
            }
        }
        return out;
    }

    @Override
    public Object postValue(Post p) {
        if (!showPerPost) {
            return null;
        }
        List<String> values = matches(p);
        if (values.isEmpty()) {
            return null;
        }
        if (aggregate == Aggregate.COUNT) {
            return values.size();
        }
        if (aggregate == Aggregate.LIST || aggregate == Aggregate.DISTINCT) {
            return String.join(", ", values);
        }
        return reduce(numbers(values));
    }

    @Override
    public Object aggregate(List<Post> posts) {
        List<String> all = new ArrayList<>();
        for (Post p : posts) {
            all.addAll(matches(p));
        }
        switch (aggregate) {
            case COUNT:
                return all.size();
            case LIST:
                return String.join(", ", all);
            case DISTINCT:
                Set<String> uniq = new LinkedHashSet<>(all);
                return String.join(", ", uniq);
            default:
                return reduce(numbers(all));
        }
    }

    private Object reduce(List<BigDecimal> nums) {
        if (nums.isEmpty()) {
            return aggregate == Aggregate.SUM ? 0 : null;
        }
        BigDecimal r;
        switch (aggregate) {
            case MIN:
                r = nums.stream().min(BigDecimal::compareTo).get();
                break;
            case MAX:
                r = nums.stream().max(BigDecimal::compareTo).get();
                break;
            case AVG:
                r = nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(nums.size()), 2, RoundingMode.HALF_UP);
                break;
            default: // SUM и всё остальное
                r = nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        r = r.stripTrailingZeros();
        return r.scale() <= 0 ? (Object) r.longValueExact() : (Object) r.doubleValue();
    }

    public static Aggregate parseAggregate(String s) {
        try {
            return Aggregate.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
