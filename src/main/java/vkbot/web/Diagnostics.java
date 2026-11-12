package vkbot.web;

import vkbot.json.JsonNode;
import vkbot.vk.VkApiException;
import vkbot.vk.VkClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка ключа VK по шагам: чей ключ, какие права у него есть, состоит ли владелец в сообществах
 * и даёт ли VK читать их обсуждения. Помогает понять причину ошибки 7/15.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    /** Битовая маска прав пользовательского ключа (account.getAppPermissions). */
    private static final Object[][] SCOPES = {
            {1, "notify"}, {2, "friends"}, {4, "photos"}, {8, "audio"}, {16, "video"}, {64, "stories"},
            {128, "pages"}, {1024, "status"}, {2048, "notes"}, {4096, "messages"}, {8192, "wall"},
            {32768, "ads"}, {65536, "offline"}, {131072, "docs"}, {262144, "groups"},
            {524288, "notifications"}, {1048576, "stats"}, {4194304, "email"}, {134217728, "market"}};

    static Map<String, Object> run(VkClient vk, List<Store.Entry> communities) {
        List<Object> checks = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        List<String> advice = new ArrayList<>();
        out.put("advice", advice);

        // 1. Чей ключ
        long userId = 0;
        try {
            JsonNode u = vk.call("users.get", new LinkedHashMap<>()).get(0);
            userId = u.get("id").asLong();
            checks.add(check("Ключ рабочий, владелец", true,
                    (u.get("first_name").asText() + " " + u.get("last_name").asText()).trim() + " (id " + userId + ")"));
        } catch (VkApiException e) {
            checks.add(check("Ключ рабочий", false, e.getMessage()));
            advice.add(e.getCode() == 5 ? "Ключ недействителен, истёк или получен с другого IP — получите новый на этом компьютере."
                    : "Ключ не принят VK — получите новый.");
            return out;
        }

        // 2. Какие права выданы ключу
        boolean hasGroups = false;
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("user_id", String.valueOf(userId));
            long mask = vk.call("account.getAppPermissions", p).asLong();
            List<String> names = new ArrayList<>();
            for (Object[] s : SCOPES) {
                if ((mask & ((Integer) s[0]).longValue()) != 0) {
                    names.add((String) s[1]);
                }
            }
            hasGroups = (mask & 262144) != 0;
            checks.add(check("Права ключа (scope)", true,
                    names.isEmpty() ? "без дополнительных прав (маска " + mask + ")" : String.join(", ", names) + " (маска " + mask + ")"));
        } catch (VkApiException e) {
            checks.add(check("Права ключа (scope)", false, e.getMessage()));
        }

        // 3. Каждое сообщество: членство, роль, чтение обсуждений
        boolean anyBoard7 = false;
        boolean anyNotMember = false;
        for (Store.Entry c : communities) {
            String prefix = "«" + c.name + "»: ";
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("group_id", String.valueOf(c.id));
                p.put("user_id", String.valueOf(userId));
                long member = vk.call("groups.isMember", p).asLong();
                checks.add(check(prefix + "владелец ключа — участник", member == 1, member == 1 ? "да" : "нет"));
                if (member != 1) {
                    anyNotMember = true;
                }
            } catch (VkApiException e) {
                checks.add(check(prefix + "проверка участия", false, e.getMessage()));
            }
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("group_id", String.valueOf(c.id));
                p.put("fields", "is_admin,admin_level,is_member,is_closed");
                JsonNode resp = vk.call("groups.getById", p);
                JsonNode g = resp.has("groups") ? resp.get("groups").get(0) : resp.get(0);
                long lvl = g.get("admin_level").asLong(0);
                String role = g.get("is_admin").asBoolean()
                        ? (lvl == 3 ? "администратор" : lvl == 2 ? "редактор" : lvl == 1 ? "модератор" : "руководитель") : "нет роли руководителя";
                checks.add(check(prefix + "роль владельца ключа", true, role));
            } catch (VkApiException e) {
                checks.add(check(prefix + "роль", false, e.getMessage()));
            }
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("group_id", String.valueOf(c.id));
                p.put("count", "1");
                long count = vk.call("board.getTopics", p).get("count").asLong();
                checks.add(check(prefix + "чтение обсуждений (board.getTopics)", true, "доступно, обсуждений: " + count));
            } catch (VkApiException e) {
                checks.add(check(prefix + "чтение обсуждений (board.getTopics)", false, e.getMessage()));
                if (e.getCode() == 7) {
                    anyBoard7 = true;
                }
            }
        }

        if (anyBoard7) {
            if (!hasGroups) {
                advice.add("У ключа нет права groups. Укажите в поле «Права (scope)» значение groups и получите ключ заново. "
                        + "Если VK ответит «invalid scope» — этот тип приложения не может выдать нужное право.");
            }
            if (anyNotMember) {
                advice.add("Владелец ключа не состоит в части сообществ — ключ нужно получать под аккаунтом участника (лучше администратора).");
            }
            if (hasGroups && !anyNotMember) {
                advice.add("Право groups есть и владелец — участник, но VK всё равно запрещает чтение: ограничение на стороне VK "
                        + "для ключей этого приложения. Нужен ключ другого типа приложения (см. README, раздел про ошибку 7).");
            }
        }
        if (communities.isEmpty()) {
            advice.add("Добавьте сообщество в список, чтобы проверить доступ к его обсуждениям.");
        }
        return out;
    }

    private static Map<String, Object> check(String title, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }
}
