package vkbot.model;

/** Сообщество, по которому строится отчёт. */
public final class Community {
    private final long id;
    private final String name;
    private final String screenName;
    private final int closedType;

    public Community(long id, String name, String screenName, int closedType) {
        this.id = id;
        this.name = name;
        this.screenName = screenName;
        this.closedType = closedType;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getScreenName() { return screenName; }
    /** 0 — открытое, 1 — закрытое, 2 — частное (поле is_closed в VK API). */
    public int getClosedType() { return closedType; }
    public boolean isClosed() { return closedType != 0; }
    public String accessLabel() {
        return closedType == 2 ? "частное" : closedType == 1 ? "закрытое" : "открытое";
    }

    public String getUrl() {
        return screenName != null && !screenName.isEmpty()
                ? "https://vk.com/" + screenName : "https://vk.com/club" + id;
    }
}
