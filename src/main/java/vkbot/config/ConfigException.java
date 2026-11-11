package vkbot.config;

/** Ошибка в настройках — выводится пользователю без стектрейса. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
