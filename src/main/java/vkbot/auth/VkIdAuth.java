package vkbot.auth;

import com.sun.net.httpserver.HttpServer;
import vkbot.config.AppConfig;
import vkbot.config.ConfigException;
import vkbot.json.Json;
import vkbot.json.JsonNode;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Авторизация через VK ID (OAuth 2.1 + PKCE).
 *
 * <p>Один раз администратор выполняет {@link #login} (login.bat): входит в VK в браузере,
 * приложение получает refresh-токен и сохраняет его в файл. При каждом обычном запуске
 * {@link #obtainAccessToken} меняет refresh-токен на свежий access-токен (живёт ~1 час)
 * и сразу сохраняет новый refresh-токен (VK ID выдаёт новый при каждом обновлении).
 */
public final class VkIdAuth {

    private static final Logger LOG = Logger.getLogger(VkIdAuth.class.getName());
    private static final String AUTHORIZE_URL = "https://id.vk.com/authorize";
    private static final String TOKEN_URL = "https://id.vk.com/oauth2/auth";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    private static final SecureRandom RND = new SecureRandom();

    private final AppConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public VkIdAuth(AppConfig cfg) {
        this.cfg = cfg;
    }

    private String clientId() {
        return cfg.require("vkid.client_id");
    }

    private String redirectUri() {
        return cfg.get("vkid.redirect_uri", "http://localhost:8765/callback");
    }

    private Path tokenFile() {
        Path p = Paths.get(cfg.get("vkid.token_file", "vkid-token.properties"));
        if (!p.isAbsolute() && cfg.file() != null && cfg.file().toAbsolutePath().getParent() != null) {
            p = cfg.file().toAbsolutePath().getParent().resolve(p);
        }
        return p;
    }

    // ======================= Первичный вход =======================

    public void login() throws IOException {
        String verifier = random(64);
        String state = random(32);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId());
        q.put("redirect_uri", redirectUri());
        q.put("state", state);
        q.put("code_challenge", challenge(verifier));
        q.put("code_challenge_method", "S256");
        String scope = cfg.get("vkid.scope", null);
        if (scope != null) {
            q.put("scope", scope);
        }
        String url = cfg.get("vkid.authorize_url", AUTHORIZE_URL) + "?" + form(q);

        LOG.info("Откройте в браузере ссылку и войдите в VK под аккаунтом администратора сообщества:");
        System.out.println();
        System.out.println(url);
        System.out.println();
        openBrowser(url);

        Map<String, String> answer = waitForRedirect();
        if (answer.containsKey("error")) {
            throw new ConfigException("VK ID отказал во входе: " + answer.get("error") + " "
                    + answer.getOrDefault("error_description", ""));
        }
        String code = answer.get("code");
        String deviceId = answer.get("device_id");
        if (code == null || deviceId == null) {
            throw new ConfigException("В адресе нет параметров code и device_id. Скопируйте адрес целиком "
                    + "из строки браузера после перехода на " + redirectUri());
        }
        if (answer.get("state") != null && !state.equals(answer.get("state"))) {
            throw new ConfigException("Параметр state не совпал — выполните вход заново (login.bat).");
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("grant_type", "authorization_code");
        p.put("code_verifier", verifier);
        p.put("redirect_uri", redirectUri());
        p.put("code", code);
        p.put("client_id", clientId());
        p.put("device_id", deviceId);
        p.put("state", state);
        JsonNode resp = postToken(p);
        save(resp, deviceId);
        LOG.info("Вход выполнен (пользователь id " + resp.get("user_id").asText("?") + "). Ключ сохранён в "
                + tokenFile().toAbsolutePath() + ". Теперь запускайте run.bat — повторный вход не нужен.");
    }

    /** Ждём возврата из VK: локальный сервер на localhost или ручная вставка адреса. */
    private Map<String, String> waitForRedirect() throws IOException {
        URI redirect = URI.create(redirectUri());
        boolean local = "http".equalsIgnoreCase(redirect.getScheme())
                && ("localhost".equalsIgnoreCase(redirect.getHost()) || "127.0.0.1".equals(redirect.getHost()));
        if (local) {
            try {
                return waitLocal(redirect);
            } catch (IOException e) {
                LOG.warning("Не удалось запустить локальный приём ответа (" + e.getMessage()
                        + "). Придётся вставить адрес вручную.");
            }
        }
        System.out.println("После входа браузер перейдёт на адрес, начинающийся с " + redirectUri());
        System.out.println("(страница может не открыться — это нормально). Скопируйте адрес из строки браузера целиком,");
        System.out.println("вставьте сюда (правый клик мышью) и нажмите Enter:");
        System.out.print("> ");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null || line.trim().isEmpty()) {
            throw new ConfigException("Адрес не введён.");
        }
        return parseRedirect(line.trim());
    }

    private Map<String, String> waitLocal(URI redirect) throws IOException {
        int port = redirect.getPort() > 0 ? redirect.getPort() : 80;
        String path = redirect.getPath() == null || redirect.getPath().isEmpty() ? "/" : redirect.getPath();
        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(path, ex -> {
            Map<String, String> params = parseRedirect(ex.getRequestURI().toString());
            byte[] page = ("<!doctype html><meta charset=utf-8><title>vkbot</title>"
                    + "<body style=\"font:16px sans-serif;padding:40px\"><h2>"
                    + (params.containsKey("code") ? "Готово! Окно можно закрыть и вернуться в консоль."
                    : "Ответ VK не содержит кода авторизации — посмотрите сообщение в консоли.")
                    + "</h2></body>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(page);
            }
            if (params.containsKey("code") || params.containsKey("error")) {
                result.complete(params);
            }
        });
        server.start();
        LOG.info("Жду ответа от VK на " + redirectUri() + " (до 5 минут)…");
        try {
            return result.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new ConfigException("За 5 минут ответ от VK не пришёл. Запустите login.bat ещё раз.");
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            server.stop(1);
        }
    }

    /** Достаёт параметры из адреса возврата (query и/или #fragment, а также JSON в payload). */
    public static Map<String, String> parseRedirect(String url) {
        Map<String, String> out = new HashMap<>();
        String s = url;
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        String query = q >= 0 ? s.substring(q + 1, h > q ? h : s.length()) : "";
        String frag = h >= 0 ? s.substring(h + 1) : "";
        for (String part : (query + "&" + frag).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if (out.containsKey("payload")) {
            try {
                JsonNode p = Json.parse(out.get("payload"));
                for (String k : new String[]{"code", "state", "device_id"}) {
                    if (!p.get(k).isMissing()) {
                        out.putIfAbsent(k, p.get(k).asText());
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // не JSON — игнорируем
            }
        }
        return out;
    }

    // ======================= Обычный запуск =======================

    public String obtainAccessToken() throws IOException {
        Path file = tokenFile();
        if (!Files.exists(file)) {
            throw new ConfigException("Нет сохранённого входа VK ID (" + file.toAbsolutePath()
                    + "). Сначала один раз запустите login.bat.");
        }
        Properties saved = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            saved.load(r);
        }
        String refresh = saved.getProperty("refresh_token", "");
        String deviceId = saved.getProperty("device_id", "");
        if (refresh.isEmpty() || deviceId.isEmpty()) {
            throw new ConfigException("Файл " + file + " повреждён. Запустите login.bat ещё раз.");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("grant_type", "refresh_token");
        p.put("refresh_token", refresh);
        p.put("client_id", clientId());
        p.put("device_id", deviceId);
        p.put("state", random(32));
        JsonNode resp;
        try {
            resp = postToken(p);
        } catch (ConfigException e) {
            throw new ConfigException(e.getMessage() + ". Сохранённый вход больше не действует — "
                    + "запустите login.bat ещё раз.");
        }
        save(resp, deviceId); // сохраняем новый refresh-токен сразу, до работы с API
        LOG.info("Ключ VK ID обновлён.");
        return resp.get("access_token").asText();
    }

    // ======================= служебное =======================

    private JsonNode postToken(Map<String, String> params) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.get("vkid.token_url", TOKEN_URL)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос прерван", e);
        }
        JsonNode json;
        try {
            json = Json.parse(resp.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("VK ID вернул не JSON (HTTP " + resp.statusCode() + "): " + abbreviate(resp.body()));
        }
        if (!json.get("error").isMissing()) {
            throw new ConfigException("VK ID: " + json.get("error").asText() + " — "
                    + json.get("error_description").asText(""));
        }
        if (json.get("access_token").isMissing()) {
            throw new IOException("VK ID не вернул access_token: " + abbreviate(resp.body()));
        }
        return json;
    }

    private void save(JsonNode resp, String deviceId) throws IOException {
        String refresh = resp.get("refresh_token").asText(null);
        if (refresh == null) {
            LOG.warning("VK ID не прислал новый refresh_token — оставляю прежний.");
            return;
        }
        Path file = tokenFile();
        StringBuilder sb = new StringBuilder();
        sb.append("# Создано vkbot (login.bat). Не пересылайте этот файл — он даёт доступ к аккаунту VK.\n");
        sb.append("# Обновляется автоматически при каждом запуске.\n");
        sb.append("updated=").append(LocalDateTime.now().withNano(0)).append('\n');
        sb.append("user_id=").append(resp.get("user_id").asText("")).append('\n');
        sb.append("device_id=").append(deviceId).append('\n');
        sb.append("refresh_token=").append(refresh).append('\n');
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception | Error ignored) {
            // ссылка уже напечатана в консоли
        }
    }
}
