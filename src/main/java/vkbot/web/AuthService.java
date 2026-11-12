package vkbot.web;

import vkbot.App;
import vkbot.auth.VkIdAuth;
import vkbot.auth.VkImplicitAuth;
import vkbot.config.AppConfig;
import vkbot.config.ConfigException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ключ доступа VK для веб-интерфейса: без вопросов в консоли, всё через страницу. */
public final class AuthService {

    /** Ключа нет — пользователь должен получить его через интерфейс. */
    public static final class NeedTokenException extends RuntimeException {
        public NeedTokenException(String message) {
            super(message);
        }
    }

    private final AppConfig cfg;
    private final boolean demo;
    private final String mode;
    private String vkidToken;
    private long vkidTokenAt;
    /** Ключ, введённый на странице. Хранится только в памяти — на диск не пишется. */
    private String pageToken;
    /** Срок действия pageToken: epoch-секунды, 0 — бессрочный. */
    private long pageTokenExpiresAt;

    /** Откуда брать ID приложения и способ возврата (настройки со страницы). */
    private java.util.function.Supplier<String> clientIdSource = () -> "";
    private java.util.function.BooleanSupplier autoRedirectSource = () -> false;
    private java.util.function.Supplier<String> scopeSource = () -> "";
    private int uiPort = 8765;

    public void setAuthSettingsSource(java.util.function.Supplier<String> clientId,
                                      java.util.function.BooleanSupplier autoRedirect, int port,
                                      java.util.function.Supplier<String> scope) {
        this.scopeSource = scope;
        this.clientIdSource = clientId;
        this.autoRedirectSource = autoRedirect;
        this.uiPort = port;
    }

    /** ID приложения: со страницы, иначе из config.properties. */
    private String clientId() {
        String c = clientIdSource.get();
        if (c == null || c.isEmpty()) {
            c = cfg.get("vkapp.client_id", "");
        }
        return c.startsWith("ВСТАВЬТЕ") ? "" : c;
    }

    public String callbackUrl() {
        return "http://localhost:" + uiPort + "/callback";
    }

    public AuthService(AppConfig cfg, boolean demo) {
        this.cfg = cfg;
        this.demo = demo;
        this.mode = demo ? "demo" : App.authMode(cfg);
        if (mode.equals("vkapp")) {
            // ключ между запусками не сохраняется — удаляем кэш, оставшийся от прежних версий
            new VkImplicitAuth(cfg).invalidate();
        }
    }

    private String livePageToken() {
        if (pageToken == null) {
            return null;
        }
        if (pageTokenExpiresAt != 0 && pageTokenExpiresAt - Instant.now().getEpochSecond() < 5 * 60) {
            return null;
        }
        return pageToken;
    }

    public synchronized String token() throws IOException {
        switch (mode) {
            case "demo":
                return "demo";
            case "vkapp": {
                String t = livePageToken();
                if (t == null) {
                    throw new NeedTokenException("Нужен ключ VK: вставьте его в поле «Ключ доступа VK» вверху страницы.");
                }
                return t;
            }
            case "vkid":
                // access-токен VK ID живёт около часа — обновляем не чаще раза в 30 минут
                if (vkidToken == null || System.currentTimeMillis() - vkidTokenAt > 30 * 60_000L) {
                    vkidToken = new VkIdAuth(cfg).obtainAccessToken();
                    vkidTokenAt = System.currentTimeMillis();
                }
                return vkidToken;
            default:
                return cfg.accessToken();
        }
    }

    /** VK ответил «ключ недействителен» — забыть его. */
    public synchronized void invalidate() {
        pageToken = null;
        vkidToken = null;
    }

    public synchronized String accept(String input) throws IOException {
        if (mode.equals("demo")) {
            return "демо-режим";
        }
        if (!mode.equals("vkapp")) {
            throw new ConfigException("Ключ сейчас берётся из config.properties (режим " + mode + "). "
                    + "Чтобы вводить его на странице, уберите vk.access_token / vk.auth из config.properties.");
        }
        String s = input == null ? "" : input.trim();
        Map<String, String> answer;
        if (s.contains("access_token=") || s.contains("error=")) {
            answer = VkIdAuth.parseRedirect(s.contains("#") || s.contains("?") ? s : "#" + s);
        } else {
            answer = new LinkedHashMap<>();
            if (!s.isEmpty()) {
                answer.put("access_token", s);
                answer.put("expires_in", "86400"); // срок неизвестен — считаем, что сутки
            }
        }
        if (answer.containsKey("error")) {
            throw new ConfigException("VK отказал в доступе: " + answer.get("error") + " — "
                    + answer.getOrDefault("error_description", ""));
        }
        String token = answer.get("access_token");
        if (token == null || token.isEmpty() || token.contains(" ")) {
            throw new ConfigException("Не похоже на ключ VK. Вставьте ключ (vk1.a.…) или адрес из браузера целиком "
                    + "(…blank.html#access_token=…)");
        }
        long expiresIn;
        try {
            expiresIn = Long.parseLong(answer.getOrDefault("expires_in", "86400").trim());
        } catch (NumberFormatException e) {
            expiresIn = 86400;
        }
        pageToken = token;
        pageTokenExpiresAt = expiresIn == 0 ? 0 : Instant.now().getEpochSecond() + expiresIn;
        return pageTokenExpiresAt == 0 ? "бессрочный" : "действует до " + VkImplicitAuth.fmt(pageTokenExpiresAt);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        switch (mode) {
            case "demo":
                m.put("ok", true);
                m.put("text", "Демо-режим: данные сгенерированы, к VK обращений нет");
                break;
            case "vkapp": {
                boolean ok = livePageToken() != null;
                m.put("ok", ok);
                m.put("text", ok ? (pageTokenExpiresAt == 0 ? "Ключ VK бессрочный"
                        : "Ключ VK действует до " + VkImplicitAuth.fmt(pageTokenExpiresAt))
                        : pageToken != null ? "Ключ VK истёк — вставьте новый" : "Введите ключ доступа VK");
                String clientId = clientId();
                boolean auto = autoRedirectSource.getAsBoolean();
                m.put("clientId", clientId);
                m.put("autoRedirect", auto);
                m.put("callbackUrl", callbackUrl());
                String scope = scopeSource.get();
                if (scope == null || scope.isEmpty()) {
                    scope = cfg.get("vkapp.scope", "");
                }
                m.put("scope", scopeSource.get());
                if (!clientId.isEmpty()) {
                    m.put("authUrl", VkImplicitAuth.authorizeUrl(clientId,
                            auto ? callbackUrl() : "https://oauth.vk.com/blank.html", cfg.apiVersion(), scope));
                    // ссылка с возвратом на blank.html — для закладки «→ vkbot» и ручной вставки
                    m.put("authUrlBlank", VkImplicitAuth.authorizeUrl(clientId,
                            "https://oauth.vk.com/blank.html", cfg.apiVersion(), scope));
                }
                m.put("canPaste", true);
                break;
            }
            case "vkid":
                m.put("ok", true);
                m.put("text", "Вход через VK ID (ключ обновляется автоматически; повторный вход — login.bat)");
                break;
            default: {
                String t = cfg.get("vk.access_token", "");
                boolean ok = !t.isEmpty() && !t.startsWith("ВСТАВЬТЕ");
                m.put("ok", ok);
                m.put("text", ok ? "Ключ из config.properties (vk.access_token)"
                        : "Не заполнен vk.access_token в config.properties");
            }
        }
        return m;
    }
}
