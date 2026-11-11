package vkbot.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Настройки приложения из config.properties (UTF-8).
 * Любой ключ можно переопределить через -Dключ=значение при запуске.
 */
public final class AppConfig {

    private final Properties props;
    private final Path file;

    private AppConfig(Properties props, Path file) {
        this.props = props;
        this.file = file;
    }

    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        // Файл настроек необязателен: без него действуют значения по умолчанию, ключ VK вводится на странице
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        // системные свойства (-Dkey=value) имеют приоритет
        for (String name : System.getProperties().stringPropertyNames()) {
            if (p.containsKey(name) || name.matches("(vk|vkapp|vkid|ui|bonus|period|topics|text|report|log|metric|metrics|timezone)(\\..*)?")) {
                p.setProperty(name, System.getProperty(name));
            }
        }
        return new AppConfig(p, file);
    }

    public static AppConfig of(Properties p) {
        return new AppConfig(p, null);
    }

    public Path file() {
        return file;
    }

    // ---------- базовые геттеры ----------

    public String get(String key, String def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    public String require(String key) {
        String v = get(key, null);
        if (v == null || v.startsWith("ВСТАВЬТЕ") || v.startsWith("PUT_")) {
            throw new ConfigException("В " + where() + " не заполнен обязательный параметр '" + key + "'");
        }
        return v;
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ConfigException("Параметр '" + key + "' должен быть целым числом, а указано: " + v);
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1") || v.equalsIgnoreCase("да");
    }

    public List<String> getList(String key, String def) {
        String v = get(key, def);
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        for (String part : v.split("[,;]")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    public Set<Long> getLongSet(String key) {
        Set<Long> out = new LinkedHashSet<>();
        for (String s : getList(key, null)) {
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw new ConfigException("Параметр '" + key + "': ожидались числа через запятую, а встретилось: " + s);
            }
        }
        return out;
    }

    /** Все ключи вида prefix.<имя>.* — возвращает множество имён. */
    public Set<String> subKeys(String prefix) {
        Set<String> out = new TreeSet<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith(prefix + ".")) {
                String rest = k.substring(prefix.length() + 1);
                int dot = rest.indexOf('.');
                out.add(dot < 0 ? rest : rest.substring(0, dot));
            }
        }
        return out;
    }

    // ---------- типизированные настройки ----------

    public String accessToken() {
        return require("vk.access_token");
    }

    public String groupId() {
        return require("vk.group_id");
    }

    public String apiVersion() {
        return get("vk.api_version", "5.199");
    }

    public ZoneId zone() {
        String z = get("timezone", null);
        try {
            return z == null ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (Exception e) {
            throw new ConfigException("Неизвестный часовой пояс timezone=" + z + " (пример: Europe/Moscow)");
        }
    }

    /** Разбор даты "yyyy-MM-dd" или "yyyy-MM-dd HH:mm". */
    public LocalDateTime getDateTime(String key, boolean endOfDay) {
        String v = get(key, null);
        if (v == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (DateTimeParseException ignored) {
            // попробуем только дату
        }
        try {
            LocalDate d = LocalDate.parse(v);
            return endOfDay ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new ConfigException("Параметр '" + key + "' должен быть в формате ГГГГ-ММ-ДД или ГГГГ-ММ-ДД ЧЧ:ММ, указано: " + v);
        }
    }

    private String where() {
        return file == null ? "настройках" : "файле " + file.toAbsolutePath();
    }

}
