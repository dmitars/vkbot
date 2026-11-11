package vkbot.model;

import java.time.Instant;

/** Тема (обсуждение) сообщества. */
public final class Topic {
    private final long id;
    private final String title;
    private final Instant created;
    private final Instant updated;
    private final boolean closed;
    private final boolean fixed;
    private final long commentsCount;

    public Topic(long id, String title, Instant created, Instant updated,
                 boolean closed, boolean fixed, long commentsCount) {
        this.id = id;
        this.title = title;
        this.created = created;
        this.updated = updated;
        this.closed = closed;
        this.fixed = fixed;
        this.commentsCount = commentsCount;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public Instant getCreated() { return created; }
    public Instant getUpdated() { return updated; }
    public boolean isClosed() { return closed; }
    public boolean isFixed() { return fixed; }
    public long getCommentsCount() { return commentsCount; }
}
