package vkbot.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import vkbot.config.AppConfig;
import vkbot.config.ConfigException;
import vkbot.json.Json;
import vkbot.json.JsonNode;
import vkbot.json.JsonOut;
import vkbot.model.Community;
import vkbot.vk.VkApiException;
import vkbot.vk.VkClient;
import vkbot.vk.VkNetworkException;
import vkbot.vk.VkTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Локальный веб-интерфейс: http://localhost:порт (доступен только с этого компьютера). */
public final class WebServer {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());

    private final AppConfig cfg;
    private final Supplier<VkTransport> transport;
    private final AuthService auth;
    private final Store store;
    private final ReportService reports;

    public WebServer(AppConfig cfg, Supplier<VkTransport> transport, boolean demo, Path storeFile) throws IOException {
        this.cfg = cfg;
        this.transport = transport;
        this.auth = new AuthService(cfg, demo);
        LocalDate today = LocalDate.now(cfg.zone());
        this.store = new Store(storeFile, today.minusDays(7), today.minusDays(1), cfg.getInt("bonus.divisor", 500));
        this.reports = new ReportService(cfg, transport, auth);
        auth.setAuthSettingsSource(store::clientId, store::autoRedirect, cfg.getInt("ui.port", 8765), store::authMode);
    }

    public String start() throws IOException {
        int port = cfg.getInt("ui.port", 8765);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException e) {
            throw new ConfigException("Порт " + port + " занят — возможно, программа уже запущена в другом окне. "
                    + "Закройте его или поменяйте ui.port в config.properties.");
        }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
        return "http://localhost:" + port + "/";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                sendResource(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/vkapp") || path.equals("/vkapp/")) {
                // страница мини-приложения VK: получает ключ через VKWebAppGetAuthToken и передаёт его программе
                sendResource(ex, "/web/vkapp.html", "text/html; charset=utf-8");
            } else if (path.equals("/vk-bridge.min.js")) {
                sendResource(ex, "/web/vk-bridge.min.js", "application/javascript; charset=utf-8");
            } else if (path.equals("/callback")) {
                // возврат из VK (redirect на localhost или закладка «→ vkbot»): ключ во фрагменте адреса.
                // Эта вкладка — та, что открыла кнопка «Получить ключ», поэтому после передачи ключа закрываем её,
                // а основная вкладка программы узнаёт о ключе через BroadcastChannel — вторая копия программы не открывается.
                send(ex, 200, "text/html; charset=utf-8", ("<!doctype html><meta charset=utf-8><title>vkbot — ключ</title>"
                        + "<body style=\"font:16px sans-serif;padding:40px\"><h2 id=m>Передаю ключ…</h2><p id=h></p><script>"
                        + "fetch('/api/auth',{method:'POST',body:JSON.stringify({input:location.href})})"
                        + ".then(r=>r.json()).then(j=>{var m=document.getElementById('m');"
                        + "if(j.error){m.textContent='Ошибка: '+j.error;return;}"
                        + "m.textContent='Готово! Ключ получен.';"
                        + "try{new BroadcastChannel('vkbot').postMessage('auth')}catch(e){}"
                        + "history.replaceState(null,'','/callback');"
                        + "setTimeout(function(){window.close();document.getElementById('h').textContent="
                        + "'Эту вкладку можно закрыть — программа уже открыта в другой вкладке.'},600)});"
                        + "</script></body>").getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/auth") && "OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                cors(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else if (path.startsWith("/api/")) {
                if (path.equals("/api/auth")) {
                    cors(ex);
                }
                Object result = api(ex, path.substring(5));
                sendJson(ex, 200, result);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (AuthService.NeedTokenException | ConfigException | IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> m = err(e);
            m.put("needToken", e instanceof AuthService.NeedTokenException);
            sendJson(ex, 400, m);
        } catch (VkApiException | VkNetworkException e) {
            if (e instanceof VkApiException && ((VkApiException) e).getCode() == 5) {
                auth.invalidate();
            }
            sendJson(ex, 400, err(e));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Ошибка обработки запроса " + path, e);
            sendJson(ex, 500, err(e));
        }
    }

    private Object api(HttpExchange ex, String action) throws IOException {
        JsonNode body = "POST".equalsIgnoreCase(ex.getRequestMethod()) ? readBody(ex) : Json.parse("{}");
        switch (action) {
            case "state": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("auth", auth.status());
                m.put("divisor", store.divisor());
                m.put("communities", store.toJson());
                m.put("running", reports.isRunning());
                m.put("timezone", cfg.zone().getId());
                m.put("defaultFrom", store.defaultFrom().toString());
                m.put("defaultTo", store.defaultTo().toString());
                return m;
            }
            case "auth": {
                String until = auth.accept(body.get("input").asText());
                // сразу проверяем ключ: VK скажет, чей он, или вернёт ошибку (неверный ключ, другой IP и т.п.)
                String who;
                try {
                    VkClient vk = new VkClient(transport.get(), auth.token(), cfg.apiVersion(), 0, 1);
                    JsonNode u = vk.call("users.get", new LinkedHashMap<>()).get(0);
                    who = (u.get("first_name").asText() + " " + u.get("last_name").asText()).trim();
                } catch (VkApiException e) {
                    auth.invalidate();
                    throw e;
                }
                LOG.info("Ключ VK принят" + (who.isEmpty() ? "" : ": " + who) + ", " + until);
                auth.setTokenOwner(who);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("text", (who.isEmpty() ? "" : who + " · ") + until);
                return m;
            }
            case "communities/add": {
                String address = body.get("address").asText("").trim();
                if (address.isEmpty()) {
                    throw new IllegalArgumentException("Укажите адрес или id сообщества");
                }
                VkClient vk = new VkClient(transport.get(), auth.token(), cfg.apiVersion(), 0,
                        cfg.getInt("vk.max_retries", 5));
                Community c = vk.resolveCommunity(address);
                Store.Entry e = new Store.Entry();
                e.id = c.getId();
                e.name = c.getName();
                e.screenName = c.getScreenName();
                e.closedType = c.getClosedType();
                e.from = store.defaultFrom();
                e.to = store.defaultTo();
                store.add(e);
                LOG.info("Добавлено сообщество: " + e.name + " (id " + e.id + ")");
                try {
                    loadTopics(e.id);
                } catch (RuntimeException tex) {
                    // список обсуждений можно будет загрузить позже кнопкой «Обновить список»
                    LOG.warning("Не удалось загрузить обсуждения «" + e.name + "»: " + tex.getMessage());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("warning", "Сообщество добавлено, но список обсуждений получить не удалось: " + tex.getMessage());
                    return m;
                }
                return Map.of("ok", true);
            }
            case "communities/update":
                store.update(body.get("id").asLong(), date(body, "from"), date(body, "to"),
                        body.has("enabled") ? body.get("enabled").asBoolean() : null);
                return Map.of("ok", true);
            case "communities/period-all": {
                LocalDate from = date(body, "from");
                LocalDate to = date(body, "to");
                for (Store.Entry e : store.entries()) {
                    store.update(e.id, from, to, null);
                }
                return Map.of("ok", true);
            }
            case "auth/diagnose":
                return Diagnostics.run(new VkClient(transport.get(), auth.token(), cfg.apiVersion(), 0, 1), store.entries());
            case "auth/settings":
                if (body.has("authMode")) {
                    store.setAuthMode(body.get("authMode").asText(""));
                }
                store.setAuthSettings(body.has("clientId") ? body.get("clientId").asText("") : null,
                        body.has("autoRedirect") ? body.get("autoRedirect").asBoolean() : null);
                return Map.of("ok", true);
            case "settings":
                store.setDivisor(parseDivisor(body.get("divisor").asText("")));
                return Map.of("ok", true);
            case "communities/topics":
                return Map.of("ok", true, "count", loadTopics(body.get("id").asLong()));
            case "communities/topic":
                store.setTopicEnabled(body.get("id").asLong(),
                        body.has("topicId") ? body.get("topicId").asLong() : null,
                        body.get("enabled").asBoolean());
                return Map.of("ok", true);
            case "communities/delete":
                store.remove(body.get("id").asLong());
                return Map.of("ok", true);
            case "report/start": {
                double divisor = parseDivisor(body.get("divisor").asText(""));
                store.setDivisor(divisor);
                List<Store.Entry> selected = new ArrayList<>();
                for (Store.Entry e : store.entries()) {
                    if (e.enabled) {
                        selected.add(e);
                    }
                }
                auth.token(); // сразу сообщить, если ключа нет
                reports.start(selected, divisor);
                return Map.of("ok", true);
            }
            case "report/status": {
                String q = ex.getRequestURI().getQuery();
                int since = 0;
                if (q != null && q.startsWith("since=")) {
                    since = Integer.parseInt(q.substring(6));
                }
                return reports.status(since);
            }
            default:
                throw new IllegalArgumentException("Неизвестная команда: " + action);
        }
    }

    private int loadTopics(long communityId) throws IOException {
        VkClient vk = new VkClient(transport.get(), auth.token(), cfg.apiVersion(), 0, cfg.getInt("vk.max_retries", 5));
        LinkedHashMap<Long, String> topics = new LinkedHashMap<>();
        for (vkbot.model.Topic t : vk.getAllTopics(communityId)) {
            topics.put(t.getId(), t.getTitle());
        }
        store.setTopics(communityId, topics);
        return topics.size();
    }

    /** Разрешаем передачу ключа только на /api/auth и с любой страницы (включая мини-приложение во фрейме VK). */
    private static void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin == null ? "*" : origin);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        ex.getResponseHeaders().set("Vary", "Origin");
    }

    private static Map<String, Object> err(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage() != null ? e.getMessage() : String.valueOf(e));
        return m;
    }

    private static double parseDivisor(String s) {
        try {
            double d = Double.parseDouble(s.replace(',', '.').trim());
            if (d > 0) {
                return d;
            }
        } catch (NumberFormatException ignored) {
            // ниже
        }
        throw new IllegalArgumentException("Делитель должен быть положительным числом");
    }

    private static LocalDate date(JsonNode body, String key) {
        String s = body.get(key).asText(null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("Неверная дата: " + s);
        }
    }

    private static JsonNode readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            String s = buf.toString(StandardCharsets.UTF_8);
            return Json.parse(s.isEmpty() ? "{}" : s);
        }
    }

    private void sendResource(HttpExchange ex, String name, String type) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(name)) {
            if (in == null) {
                send(ex, 500, "text/plain; charset=utf-8", ("Нет ресурса " + name).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, type, in.readAllBytes());
        }
    }

    private static void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        send(ex, code, "application/json; charset=utf-8", JsonOut.write(obj).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
