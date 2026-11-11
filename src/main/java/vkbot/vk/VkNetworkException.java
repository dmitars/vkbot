package vkbot.vk;

/** Сеть недоступна / VK не отвечает после всех повторов. */
public class VkNetworkException extends RuntimeException {
    public VkNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
