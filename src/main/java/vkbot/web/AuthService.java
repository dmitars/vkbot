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

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AuthService.class.getName());

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
    /** Ключ, полученный на странице. Сохраняется в файл (vk-token.properties) вместе со временем получения. */
    private String pageToken;
    /** Когда получен pageToken: epoch-секунды. */
    private long pageTokenObtainedAt;
    private String pageTokenUser;
    /** Срок действия pageToken: epoch-секунды, 0 — бессрочный. */
    private long pageTokenExpiresAt;

    /** Откуда брать ID приложения и способ возврата (настройки со страницы). */
    private java.util.function.Supplier<String> clientIdSource = () -> "";
    private java.util.function.BooleanSupplier autoRedirectSource = () -> false;
    private java.util.function.Supplier<String> modeSource = () -> "miniapp";
    /** Право, без которого не читаются обсуждения закрытых и частных сообществ (документация board.getTopics). */
    public static final String SCOPE = "groups";
    private int uiPort = 8765;

    public void setAuthSettingsSource(java.util.function.Supplier<String> clientId,
                                      java.util.function.BooleanSupplier autoRedirect, int port,
                                      java.util.function.Supplier<String> mode) {
        this.modeSource = mode;
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
            loadToken();
        }
    }

    // ---------------- хранение ключа на диске ----------------

    /** Файл с ключом рядом с config.properties (в git не попадает). */
    private java.nio.file.Path tokenFile() {
        java.nio.file.Path p = java.nio.file.Paths.get(cfg.get("ui.token_file", "vk-token.properties"));
        if (!p.isAbsolute() && cfg.file() != null && cfg.file().toAbsolutePath().getParent() != null) {
            p = cfg.file().toAbsolutePath().getParent().resolve(p);
        }
        return p;
    }

    /** Загрузить сохранённый ключ; просроченный ключ не используется (будет запрошен заново). */
    private void loadToken() {
        java.nio.file.Path f = tokenFile();
        if (!java.nio.file.Files.exists(f)) {
            return;
        }
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(f, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
            String t = p.getProperty("access_token", "").trim();
            if (t.isEmpty()) {
                return;
            }
            pageToken = t;
            pageTokenExpiresAt = Long.parseLong(p.getProperty("expires_at", "0").trim());
            pageTokenObtainedAt = Long.parseLong(p.getProperty("obtained_at", "0").trim());
            String user = p.getProperty("user", "").trim();
            pageTokenUser = user.isEmpty() ? null : user;
            LOG.info(livePageToken() != null
                    ? "Загружен сохранённый ключ VK (получен " + VkImplicitAuth.fmt(pageTokenObtainedAt) + ", "
                      + (pageTokenExpiresAt == 0 ? "бессрочный" : "действует до " + VkImplicitAuth.fmt(pageTokenExpiresAt)) + ")"
                    : "Сохранённый ключ VK истёк — нужно получить новый");
        } catch (Exception e) {
            LOG.warning("Не удалось прочитать " + f + ": " + e.getMessage() + " — ключ нужно получить заново");
        }
    }

    private void saveToken() {
        java.nio.file.Path f = tokenFile();
        try {
            if (pageToken == null) {
                java.nio.file.Files.deleteIfExists(f);
                return;
            }
            String text = "# Ключ доступа VK для vkbot. Не пересылайте этот файл и не добавляйте его в git.\n"
                    + "# Получен: " + VkImplicitAuth.fmt(pageTokenObtainedAt)
                    + (pageTokenExpiresAt == 0 ? ", бессрочный" : ", действует до " + VkImplicitAuth.fmt(pageTokenExpiresAt)) + "\n"
                    + "obtained_at=" + pageTokenObtainedAt + "\n"
                    + "expires_at=" + pageTokenExpiresAt + "\n"
                    + "user=" + (pageTokenUser == null ? "" : pageTokenUser.replace("\\", "").replace("\n", " ")) + "\n"
                    + "access_token=" + pageToken + "\n";
            java.nio.file.Files.write(f, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { // только владелец может читать файл (на системах с POSIX-правами)
                java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | java.io.IOException ignored) {
                // Windows — права не меняем
            }
        } catch (java.io.IOException e) {
            LOG.warning("Не удалось сохранить ключ в " + f + ": " + e.getMessage());
        }
    }

    /** Запомнить, чей это ключ (после проверки users.get) — показывается в статусе. */
    public synchronized void setTokenOwner(String who) {
        if (pageToken != null && who != null && !who.isEmpty()) {
            pageTokenUser = who;
            saveToken();
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
        if (pageToken != null) {
            pageToken = null;
            saveToken(); // удаляем недействительный ключ из файла
        }
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
        pageTokenObtainedAt = Instant.now().getEpochSecond();
        pageTokenUser = null;
        pageTokenExpiresAt = expiresIn == 0 ? 0 : pageTokenObtainedAt + expiresIn;
        saveToken();
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
                m.put("text", ok ? (pageTokenUser != null ? pageTokenUser + " · ключ получен " : "Ключ получен ")
                        + VkImplicitAuth.fmt(pageTokenObtainedAt)
                        + (pageTokenExpiresAt == 0 ? ", бессрочный" : ", действует до " + VkImplicitAuth.fmt(pageTokenExpiresAt))
                        : pageToken != null ? "Ключ VK истёк — вставьте новый" : "Введите ключ доступа VK");
                String clientId = clientId();
                String mode = modeSource.get();
                m.put("clientId", clientId);
                m.put("authMode", mode);
                m.put("callbackUrl", callbackUrl());
                m.put("miniAppUrl", "http://localhost:" + uiPort + "/vkapp");
                if (!clientId.isEmpty()) {
                    String blank = VkImplicitAuth.authorizeUrl(clientId, "https://oauth.vk.com/blank.html", cfg.apiVersion(), SCOPE);
                    // ссылка с возвратом на blank.html — для закладки «→ vkbot» и ручной вставки
                    m.put("authUrlBlank", blank);
                    switch (mode) {
                        case "miniapp":
                            m.put("authUrl", "https://vk.com/app" + clientId);
                            break;
                        default:
                            m.put("authUrl", blank);
                    }
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
