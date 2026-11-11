package vkbot.stats.metrics;

import vkbot.model.Post;
import vkbot.stats.Metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Показатели, связанные с бонусными сообщениями («Бонусные баллы: ...»). */
public final class BonusMetrics {

    private BonusMetrics() {
    }

    static long weighted(Post p) {
        return (long) p.chars() * p.getWeight();
    }

    /** Сколько сообщений со строкой «Бонусные баллы». */
    public static final class BonusPosts implements Metric {
        public String key() { return "bonus_posts"; }
        public String title() { return "Из них бонусных"; }
        public Object postValue(Post p) { return p.isBonus() && !p.isSkipped() ? "×" + p.getWeight() : null; }
        public Object aggregate(List<Post> posts) {
            return posts.stream().filter(p -> p.isBonus() && !p.isSkipped()).count();
        }
    }

    /** Символы с учётом множителя: обычные ×1, текст после строки бонуса ×2. */
    public static final class WeightedChars implements Metric {
        public String key() { return "weighted_chars"; }
        public String title() { return "Символов с ×2"; }
        public Object postValue(Post p) { return weighted(p); }
        public Object aggregate(List<Post> posts) {
            long sum = 0;
            for (Post p : posts) {
                sum += weighted(p);
            }
            return sum;
        }
    }

    /** Бонусные баллы = символы с учётом множителя / делитель, 2 знака после запятой. */
    public static final class BonusPoints implements Metric {
        private final BigDecimal divisor;

        public BonusPoints(double divisor) {
            if (divisor <= 0) {
                throw new IllegalArgumentException("Делитель должен быть больше нуля");
            }
            this.divisor = BigDecimal.valueOf(divisor);
        }

        public String key() { return "bonus_points"; }
        public String title() { return "Бонусные баллы"; }

        public Object aggregate(List<Post> posts) {
            long sum = 0;
            for (Post p : posts) {
                sum += weighted(p);
            }
            return BigDecimal.valueOf(sum).divide(divisor, 2, RoundingMode.HALF_UP).doubleValue();
        }
    }
}
