package vkbot.vk;

/** Ошибка, которую вернул VK API ({"error": {...}}). */
public class VkApiException extends RuntimeException {

    private final int code;
    private final String method;

    public VkApiException(String method, int code, String message) {
        super("VK API " + method + ": ошибка " + code + " — " + message
                + (message != null && message.contains("another ip") ? IP_HINT : hint(code)));
        this.code = code;
        this.method = method;
    }

    private static final String IP_HINT = ". Подсказка: ключ привязан к IP-адресу, с которого его получили в браузере, "
            + "а программа выходит в интернет с другого. Получите ключ на этом же компьютере и в той же сети; "
            + "VPN/прокси должны быть одинаково включены (или выключены) и для браузера, и для программы.";

    public int getCode() {
        return code;
    }

    public String getMethod() {
        return method;
    }

    /** Ошибки, при которых имеет смысл повторить запрос. */
    public boolean isRetryable() {
        return code == 1 || code == 6 || code == 10;
    }

    private static String hint(int code) {
        switch (code) {
            case 5:
                return ". Подсказка: токен недействителен или просрочен — получите новый и пропишите в vk.access_token.";
            case 15:
            case 7:
                return ". Подсказка: нет доступа. Для закрытого/частного сообщества нужен ключ пользователя-участника (лучше администратора), сервисный ключ не подойдёт.";
            case 27:
            case 28:
                return ". Подсказка: этот тип ключа не подходит для метода — используйте сервисный ключ или ключ пользователя (не ключ сообщества).";
            case 29:
                return ". Подсказка: исчерпан суточный лимит вызовов метода, попробуйте завтра.";
            case 100:
                return ". Подсказка: проверьте vk.group_id.";
            case 9:
                return ". Подсказка: слишком много однотипных действий, подождите и повторите.";
            default:
                return "";
        }
    }
}
