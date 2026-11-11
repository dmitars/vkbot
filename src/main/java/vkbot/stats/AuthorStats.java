package vkbot.stats;

import vkbot.model.Author;
import vkbot.model.Post;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Сообщения одного автора и посчитанные по ним показатели (ключ метрики -> значение). */
public final class AuthorStats {
    private final Author author;
    private final List<Post> posts;
    private final Map<String, Object> values;

    public AuthorStats(Author author, List<Post> posts, Map<String, Object> values) {
        this.author = author;
        this.posts = Collections.unmodifiableList(posts);
        this.values = Collections.unmodifiableMap(values);
    }

    public Author getAuthor() { return author; }
    public List<Post> getPosts() { return posts; }
    public Map<String, Object> getValues() { return values; }

    public long number(String key) {
        Object v = values.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }
}
