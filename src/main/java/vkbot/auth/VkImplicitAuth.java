package vkbot.auth;

import com.sun.net.httpserver.HttpServer;
import vkbot.config.AppConfig;
import vkbot.config.ConfigException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Ключ пользователя через oauth.vk.com (Implicit Flow) для приложения VK (например, мини-приложения).
 *
 * <p>Такой ключ обычно живёт сутки, поэтому при запуске:
 * <ol>
 *   <li>если в кэше есть ещё действующий ключ — используем его;</li>
 *   <li>иначе открываем страницу авторизации VK в браузере. Если администратор уже вошёл в VK
 *       и ранее разрешил доступ, VK сразу перенаправит на адрес с ключом;</li>
 *   <li>ключ забираем автоматически (redirect на localhost) или просим вставить адрес из браузера.</li>
 * </ol>
 */
public final class VkImplicitAuth {

    private static final Logger LOG = Logger.getLogger(VkImplicitAuth.class.getName());
    private static final String AUTHORIZE_URL = "https://oauth.vk.com/authorize";
    /** Не берём ключ, который истечёт во время работы. */
    private static final long MIN_REMAINING_SEC = 15 * 60;

    private final AppConfig cfg;

    public VkImplicitAuth(AppConfig cfg) {
        this.cfg = cfg;
    }

    private String redirectUri() {
        return cfg.get("vkapp.redirect_uri", "https://oauth.vk.com/blank.html");
    }

    private Path cacheFile() {
        Path p = Paths.get(cfg.get("vkapp.token_cache", "vk-token-cache.properties"));
        if (!p.isAbsolute() && cfg.file() != null && cfg.file().toAbsolutePath().getParent() != null) {
            p = cfg.file().toAbsolutePath().getParent().resolve(p);
        }
        return p;
    }

    public String obtainAccessToken() throws IOException {
        String cached = readCache();
        if (cached != null) {
            return cached;
        }
        return authorize();
    }

    /** Сбросить кэш (например, если VK ответил, что ключ недействителен). */
    public void invalidate() {
        try {
            Files.deleteIfExists(cacheFile());
        } catch (IOException ignored) {
            // не критично
        }
    }

    // ---------------- кэш ----------------

    private Properties loadCache() {
        Path f = cacheFile();
        if (!Files.exists(f)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return null;
        }
        if (!cfg.get("vkapp.client_id", "").equals(p.getProperty("client_id"))) {
            return null;
        }
        return p;
    }

    private String readCache() {
        return readCache(true);
    }

    private String readCache(boolean log) {
        Properties p = loadCache();
        if (p == null) {
            return null;
        }
        String token = p.getProperty("access_token", "");
        if (token.isEmpty() || !cfg.get("vkapp.client_id", "").equals(p.getProperty("client_id"))) {
            return null;
        }
        long expiresAt = Long.parseLong(p.getProperty("expires_at", "0"));
        if (expiresAt != 0 && expiresAt - Instant.now().getEpochSecond() < MIN_REMAINING_SEC) {
            if (log) {
                LOG.info("Сохранённый ключ VK истёк — нужен новый.");
            }
            return null;
        }
        if (log) LOG.info("Использую сохранённый ключ VK" + (expiresAt == 0 ? " (бессрочный)"
                : " (действует до " + fmt(expiresAt) + ")"));
        return token;
    }

    private void writeCache(String token, long expiresAt, String userId) throws IOException {
        Path f = cacheFile();
        StringBuilder sb = new StringBuilder();
        sb.append("# Создано vkbot. Временный ключ VK — не пересылайте этот файл.\n");
        sb.append("client_id=").append(cfg.get("vkapp.client_id", "")).append('\n');
        sb.append("user_id=").append(userId == null ? "" : userId).append('\n');
        sb.append("expires_at=").append(expiresAt).append('\n');
        sb.append("access_token=").append(token).append('\n');
        if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
        }
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    // ---------------- авторизация ----------------

    /** Ссылка авторизации VK для заданного приложения и адреса возврата. */
    public static String authorizeUrl(String clientId, String redirectUri, String apiVersion, String scope) {
        StringBuilder url = new StringBuilder(AUTHORIZE_URL).append("?client_id=")
                .append(URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                .append("&display=page&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8))
                .append("&response_type=token&v=").append(URLEncoder.encode(apiVersion, StandardCharsets.UTF_8));
        if (scope != null && !scope.isEmpty()) {
            url.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /** Ссылка авторизации VK для получения ключа. */
    public String authorizeUrl() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", cfg.require("vkapp.client_id"));
        q.put("display", "page");
        q.put("redirect_uri", redirectUri());
        q.put("response_type", "token");
        q.put("v", cfg.apiVersion());
        String scope = cfg.get("vkapp.scope", null);
        if (scope != null) {
            q.put("scope", scope);
        }
        StringBuilder url = new StringBuilder(AUTHORIZE_URL).append('?');
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (url.charAt(url.length() - 1) != '?') {
                url.append('&');
            }
            url.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /** Действующий ключ из кэша или null (без вопросов пользователю). */
    public String cachedToken() {
        return readCache(false);
    }

    /** Срок действия ключа в кэше: epoch-секунды, 0 — бессрочный, -1 — ключа нет. */
    public long cachedExpiresAt() {
        Properties p = loadCache();
        if (p == null || p.getProperty("access_token", "").isEmpty()) {
            return -1;
        }
        return Long.parseLong(p.getProperty("expires_at", "0"));
    }

    /**
     * Принять ответ VK: адрес из браузера (…#access_token=…&expires_in=…) или сам ключ.
     * Сохраняет ключ в кэш. Возвращает описание срока действия.
     */
    public String accept(String input) throws IOException {
        String s = input == null ? "" : input.trim();
        Map<String, String> answer;
        if (s.contains("access_token=") || s.contains("error=")) {
            answer = VkIdAuth.parseRedirect(s.contains("#") || s.contains("?") ? s : "#" + s);
        } else {
            answer = new LinkedHashMap<>();
            if (!s.isEmpty()) {
                answer.put("access_token", s);
                answer.put("expires_in", "86400");
            }
        }
        return storeAnswer(answer);
    }

    private String storeAnswer(Map<String, String> answer) throws IOException {
        if (answer.containsKey("error")) {
            throw new ConfigException("VK отказал в доступе: " + answer.get("error") + " — "
                    + answer.getOrDefault("error_description", "")
                    + (answer.getOrDefault("error_description", "").contains("scope")
                    ? ". Уберите vkapp.scope из config.properties." : ""));
        }
        String token = answer.get("access_token");
        if (token == null || token.isEmpty()) {
            throw new ConfigException("В адресе нет access_token. Скопируйте адрес из строки браузера целиком "
                    + "(он начинается с " + redirectUri() + "#access_token=...)");
        }
        long expiresIn = parseLong(answer.get("expires_in"));
        long expiresAt = expiresIn == 0 ? 0 : Instant.now().getEpochSecond() + expiresIn;
        writeCache(token, expiresAt, answer.get("user_id"));
        String until = expiresAt == 0 ? "бессрочный" : "действует до " + fmt(expiresAt);
        LOG.info("Ключ получен (пользователь id " + answer.getOrDefault("user_id", "?") + ", " + until + ").");
        return until;
    }

    private String authorize() throws IOException {
        String url = authorizeUrl();

        LOG.info("Нужен ключ VK. Открываю страницу авторизации в браузере "
                + "(должен быть выполнен вход в VK под администратором сообщества):");
        System.out.println();
        System.out.println(url);
        System.out.println();
        Map<String, String> answer = isLocal() ? captureLocal(url) : capturePaste(url);
        storeAnswer(answer);
        return answer.get("access_token");
    }

    private boolean isLocal() {
        URI u = URI.create(redirectUri());
        return "http".equalsIgnoreCase(u.getScheme())
                && ("localhost".equalsIgnoreCase(u.getHost()) || "127.0.0.1".equals(u.getHost()));
    }

    private Map<String, String> capturePaste(String url) throws IOException {
        VkIdAuth.openBrowser(url);
        System.out.println("Браузер перейдёт на страницу " + redirectUri() + " (она может быть пустой — это нормально).");
        System.out.println("Скопируйте адрес из строки браузера целиком, вставьте сюда (правый клик мышью) и нажмите Enter:");
        System.out.print("> ");
        System.out.flush();
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (line == null || line.trim().isEmpty()) {
            throw new ConfigException("Адрес не введён — ключ не получен.");
        }
        return VkIdAuth.parseRedirect(line.trim());
    }

    /**
     * Ключ приходит во фрагменте адреса (#access_token=...), который браузер не отправляет серверу.
     * Поэтому локальная страница маленьким скриптом пересылает фрагмент обратно приложению.
     */
    private Map<String, String> captureLocal(String url) throws IOException {
        URI redirect = URI.create(redirectUri());
        int port = redirect.getPort() > 0 ? redirect.getPort() : 80;
        String path = redirect.getPath() == null || redirect.getPath().isEmpty() ? "/" : redirect.getPath();
        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            LOG.warning("Порт " + port + " занят (" + e.getMessage() + ") — придётся вставить адрес вручную.");
            return capturePaste(url);
        }
        server.createContext("/", ex -> {
            String uri = ex.getRequestURI().toString();
            String html;
            if (uri.startsWith("/__token")) {
                Map<String, String> params = VkIdAuth.parseRedirect(uri);
                html = params.containsKey("access_token")
                        ? "Готово! Ключ получен, окно можно закрыть."
                        : "VK не передал ключ — посмотрите сообщение в консоли.";
                result.complete(params);
            } else if (uri.startsWith(path)) {
                html = "Передаю ключ приложению…<script>"
                        + "var h=location.hash.substring(1)||location.search.substring(1);"
                        + "fetch('/__token?'+h).then(function(){document.body.innerHTML="
                        + "'<h2>Готово! Окно можно закрыть.</h2>'});</script>";
            } else {
                html = "vkbot";
            }
            byte[] page = ("<!doctype html><meta charset=utf-8><title>vkbot</title>"
                    + "<body style=\"font:16px sans-serif;padding:40px\"><h2>" + html + "</h2></body>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(page);
            }
        });
        server.start();
        VkIdAuth.openBrowser(url);
        LOG.info("Жду ответа VK (до 5 минут)…");
        try {
            return result.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new ConfigException("За 5 минут ключ не получен. Если VK показал ошибку redirect_uri — "
                    + "уберите vkapp.redirect_uri из config.properties (будет ручная вставка адреса).");
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            server.stop(1);
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String fmt(long epoch) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(
                Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()));
    }
}
