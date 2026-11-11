package vkbot.model;

/** Автор сообщения: пользователь (id > 0) или сообщество (id < 0). */
public final class Author {
    private final long id;
    private final String name;
    private final String screenName;

    public Author(long id, String name, String screenName) {
        this.id = id;
        this.name = name;
        this.screenName = screenName;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getScreenName() { return screenName; }
    public boolean isCommunity() { return id < 0; }

    public String getUrl() {
        if (screenName != null && !screenName.isEmpty()) {
            return "https://vk.com/" + screenName;
        }
        return id < 0 ? "https://vk.com/club" + (-id) : "https://vk.com/id" + id;
    }

    public static Author unknown(long id) {
        return new Author(id, id < 0 ? "club" + (-id) : "id" + id, null);
    }
}
