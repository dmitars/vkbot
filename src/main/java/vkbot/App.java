package vkbot;

import vkbot.auth.VkIdAuth;
import vkbot.auth.VkImplicitAuth;
import vkbot.collect.BoardCollector;
import vkbot.collect.BonusRule;
import vkbot.collect.TextNormalizer;
import vkbot.config.AppConfig;
import vkbot.config.ConfigException;
import vkbot.log.LogSetup;
import vkbot.model.Community;
import vkbot.report.HtmlReportWriter;
import vkbot.stats.AuthorStats;
import vkbot.stats.Metric;
import vkbot.stats.MetricRegistry;
import vkbot.stats.Report;
import vkbot.stats.StatisticsBuilder;
import vkbot.vk.DemoVkTransport;
import vkbot.vk.HttpVkTransport;
import vkbot.vk.VkApiException;
import vkbot.vk.VkClient;
import vkbot.vk.VkNetworkException;
import vkbot.vk.VkTransport;
import vkbot.web.WebServer;

import java.awt.Desktop;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Точка входа. Использование:
 * <pre>
 *   java -jar vkbot.jar [config.properties] [--demo]   — веб-интерфейс (по умолчанию)
 *   java -jar vkbot.jar [config.properties] --cli      — один отчёт по vk.group_id без интерфейса
 *   java -jar vkbot.jar [config.properties] --login    — первичный вход через VK ID
 * </pre>
 */
public final class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        String configPath = "config.properties";
        boolean demo = false;
        boolean login = false;
        boolean cli = false;
        for (String a : args) {
            if (a.equals("--cli")) {
                cli = true;
            }
            if (a.equals("--demo")) {
                demo = true;
            } else if (a.equals("--login")) {
                login = true;
            } else if (!a.startsWith("--")) {
                configPath = a;
            }
        }

        LogSetup.setupConsoleEncoding(System.getProperty("console.encoding", "UTF-8"));
        LogSetup.init(Level.INFO, null);

        AppConfig cfgRef = null;
        try {
            AppConfig cfg = AppConfig.load(Paths.get(configPath));
            cfgRef = cfg;
            String logFile = cfg.get("log.file", "logs/vkbot.log");
            LogSetup.init(LogSetup.parseLevel(cfg.get("log.level", "INFO")),
                    logFile.equalsIgnoreCase("none") ? null : Paths.get(logFile));
            if (login) {
                new VkIdAuth(cfg).login();
                return 0;
            }
            if (!cli) {
                return runUi(cfg, demo);
            }
            return execute(cfg, demo);
        } catch (ConfigException e) {
            LOG.severe("Ошибка настроек: " + e.getMessage());
        } catch (VkApiException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            if (e.getCode() == 5 && cfgRef != null && authMode(cfgRef).equals("vkapp")) {
                new VkImplicitAuth(cfgRef).invalidate();
                LOG.severe("Сохранённый ключ сброшен — при следующем запуске будет получен новый.");
            }
        } catch (VkNetworkException e) {
            LOG.log(Level.SEVERE, e.getMessage() + ". Проверьте подключение к интернету / прокси.", e);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Непредвиденная ошибка: " + e, e);
        }
        LOG.severe("Работа завершена с ошибкой.");
        return 1;
    }

    /** Веб-интерфейс: сервер на localhost, страница открывается в браузере, окно консоли — журнал. */
    private static int runUi(AppConfig cfg, boolean demo) throws Exception {
        Path base = cfg.file() != null && cfg.file().toAbsolutePath().getParent() != null
                ? cfg.file().toAbsolutePath().getParent() : Paths.get(".");
        Path storeFile = base.resolve(cfg.get("ui.data_file", demo ? "communities-demo.json" : "communities.json"));
        DemoVkTransport demoTransport = demo ? new DemoVkTransport() : null;
        WebServer server = new WebServer(cfg, () -> demo ? demoTransport : new HttpVkTransport(), demo, storeFile);
        String url = server.start();
        if (demo) {
            LOG.warning("ДЕМО-РЕЖИМ: данные сгенерированы, к VK обращений нет.");
        }
        LOG.info("Интерфейс запущен: " + url);
        LOG.info("Если браузер не открылся сам — откройте этот адрес вручную.");
        LOG.info("НЕ ЗАКРЫВАЙТЕ это окно, пока работаете с программой. Здесь же пишутся ошибки.");
        if (cfg.getBool("ui.open_browser", true)) {
            openInBrowser(java.net.URI.create(url));
        }
        Thread.currentThread().join(); // работаем, пока окно не закроют
        return 0;
    }

    private static int execute(AppConfig cfg, boolean demo) throws Exception {
        long started = System.currentTimeMillis();
        ZoneId zone = cfg.zone();

        // ---------- период ----------
        Instant now = Instant.now();
        LocalDateTime fromCfg = cfg.getDateTime("period.from", false);
        LocalDateTime toCfg = cfg.getDateTime("period.to", true);
        Instant to = toCfg != null ? toCfg.atZone(zone).toInstant() : now;
        Instant from = fromCfg != null ? fromCfg.atZone(zone).toInstant()
                : to.minus(Duration.ofDays(cfg.getInt("period.days", 7)));
        if (!from.isBefore(to)) {
            throw new ConfigException("Начало периода должно быть раньше конца (period.from / period.to / period.days)");
        }

        List<Metric> metrics = MetricRegistry.fromConfig(cfg);

        // ---------- VK ----------
        VkTransport transport;
        String token;
        String group;
        if (demo) {
            LOG.warning("ДЕМО-РЕЖИМ: данные сгенерированы, к VK обращений нет.");
            transport = new DemoVkTransport();
            token = "demo";
            group = "1";
        } else {
            transport = new HttpVkTransport();
            group = cfg.groupId();
            String mode = authMode(cfg);
            if (mode.equals("vkid")) {
                token = new VkIdAuth(cfg).obtainAccessToken();
            } else if (mode.equals("vkapp")) {
                token = new VkImplicitAuth(cfg).obtainAccessToken();
            } else {
                token = cfg.accessToken();
            }
        }
        VkClient vk = new VkClient(transport, token, cfg.apiVersion(),
                demo ? 0 : cfg.getInt("vk.request_delay_ms", 350), cfg.getInt("vk.max_retries", 5));

        Community community = vk.resolveCommunity(group);
        LOG.info("Сообщество: " + community.getName() + " (id " + community.getId() + ")"
                + ", " + community.accessLabel());
        LOG.info("Период: " + from.atZone(zone).toLocalDateTime().withNano(0) + " — "
                + to.atZone(zone).toLocalDateTime().withNano(0) + " (" + zone + ")");

        TextNormalizer normalizer = new TextNormalizer(cfg.getBool("text.strip_reply_mentions", true));
        BonusRule bonus = new BonusRule(cfg.get("bonus.marker_regex", BonusRule.DEFAULT_MARKER),
                cfg.getInt("bonus.multiplier", 2), normalizer);
        BoardCollector collector = new BoardCollector(vk, normalizer, bonus,
                cfg.getLongSet("topics.only_ids"), cfg.getLongSet("topics.exclude_ids"),
                cfg.getBool("topics.skip_on_error", true))
                .withSkipRule(new vkbot.collect.SkipRule(cfg.get("skip.marker_regex", vkbot.collect.SkipRule.DEFAULT_MARKER)));
        BoardCollector.Result data = collector.collect(community, from, to);

        // ---------- статистика ----------
        StatisticsBuilder stats = new StatisticsBuilder(metrics);
        List<AuthorStats> byAuthor = stats.byAuthor(data.posts, vk::author);
        Map<String, Object> totals = stats.totals(data.posts);
        Report report = new Report(community, from, to, Instant.now(), zone, metrics, byAuthor, totals,
                data.posts, data.scannedTopics, data.failedTopics, data.totalTopics);

        printSummary(report);

        // ---------- HTML ----------
        HtmlReportWriter writer = new HtmlReportWriter(cfg.getBool("report.include_posts", true),
                cfg.getInt("report.preview_length", 150));
        Path file = writer.write(report, Paths.get(cfg.get("report.dir", "reports")));
        LOG.info("Отчёт сохранён: " + file.toAbsolutePath());
        LOG.info(String.format("Готово за %.1f с, запросов к VK: %d",
                (System.currentTimeMillis() - started) / 1000.0, vk.getCallCount()));

        if (cfg.getBool("report.open_in_browser", true)) {
            openInBrowser(file);
        }
        return data.failedTopics.isEmpty() ? 0 : 2;
    }

    /**
     * vk.auth=token — постоянный ключ в vk.access_token;
     * vk.auth=vkapp — ключ через oauth.vk.com по ID приложения VK (получается при запуске, кэшируется);
     * vk.auth=vkid  — вход через VK ID (login.bat), ключ обновляется сам.
     */
    public static String authMode(AppConfig cfg) {
        String token = cfg.get("vk.access_token", "");
        boolean hasToken = !token.isEmpty() && !token.startsWith("ВСТАВЬТЕ");
        String mode = cfg.get("vk.auth", null);
        if (mode == null) {
            if (hasToken) {
                return "token";
            }
            // по умолчанию — ключ вводится на странице (или берётся через приложение VK, если задан vkapp.client_id)
            return cfg.get("vkid.client_id", null) != null ? "vkid" : "vkapp";
        }
        mode = mode.toLowerCase();
        if (mode.equals("token")) {
            return hasToken ? "token" : "vkapp"; // ключ не вписан в файл — значит, вводим его на странице
        }
        if (mode.equals("vkapp") || mode.equals("vkid")) {
            return mode;
        }
        throw new ConfigException("vk.auth: допустимо token, vkapp или vkid, указано: " + mode);
    }

    private static void printSummary(Report r) {
        LOG.info("Сообщений за период: " + r.totals.get("posts") + ", авторов: " + r.authors.size());
        int shown = 0;
        for (AuthorStats a : r.authors) {
            if (shown++ >= 10) {
                LOG.info("   … и ещё " + (r.authors.size() - 10) + " (полный список — в HTML-отчёте)");
                break;
            }
            LOG.info(String.format("   %-30s сообщений: %4d   символов: %7d",
                    a.getAuthor().getName(), a.number("posts"), a.number("chars")));
        }
    }

    private static void openInBrowser(Path file) {
        openInBrowser(file.toAbsolutePath().toUri());
    }

    private static void openInBrowser(java.net.URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
            }
        } catch (Exception | Error e) {
            LOG.fine("Не удалось открыть браузер: " + e);
        }
    }
}
