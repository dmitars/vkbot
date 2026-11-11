package vkbot.stats;

import vkbot.model.Post;

import java.util.List;

/**
 * Точка расширения статистики.
 *
 * <p>Чтобы добавить новый показатель:
 * <ol>
 *   <li>реализуйте этот интерфейс (см. примеры в пакете {@code vkbot.stats.metrics});</li>
 *   <li>зарегистрируйте его в {@link MetricRegistry#builtIns()};</li>
 *   <li>добавьте его ключ в параметр {@code metrics} в config.properties.</li>
 * </ol>
 * Для простого извлечения данных из текста код писать не нужно — достаточно описать
 * regex-метрику в config.properties (см. {@code metric.<ключ>.*}).
 */
public interface Metric {

    /** Уникальный ключ (используется в config.properties). */
    String key();

    /** Заголовок колонки в отчёте. */
    String title();

    /**
     * Значение для отдельного сообщения (колонка в подробном списке сообщений автора).
     * Верните null, если для отдельного сообщения показатель не имеет смысла.
     */
    default Object postValue(Post post) {
        return null;
    }

    /** Значение по набору сообщений одного автора. Число (Number) или строка. */
    Object aggregate(List<Post> posts);

    /** Значение для строки «Итого» по всем сообщениям. По умолчанию — та же агрегация. */
    default Object total(List<Post> allPosts) {
        return aggregate(allPosts);
    }
}
