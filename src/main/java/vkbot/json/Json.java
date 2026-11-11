package vkbot.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON-парсер без внешних зависимостей.
 * Объекты -> LinkedHashMap, массивы -> ArrayList, числа -> Long/Double,
 * строки -> String, true/false -> Boolean, null -> null.
 */
public final class Json {

    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    public static JsonNode parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw p.error("Лишние символы после JSON");
        }
        return new JsonNode(value);
    }

    private Object readValue() {
        if (pos >= s.length()) {
            throw error("Неожиданный конец JSON");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Неожиданный символ '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("Ожидался ключ объекта");
            }
            String key = readString();
            skipWs();
            if (peek() != ':') {
                throw error("Ожидалось ':'");
            }
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Ожидалось ',' или '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Ожидалось ',' или ']'");
            }
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("Незакрытая строка");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("Некорректная escape-последовательность \\" + e);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        boolean floating = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                floating = true;
                pos++;
            } else {
                break;
            }
        }
        String num = s.substring(start, pos);
        try {
            if (!floating) {
                return Long.parseLong(num);
            }
        } catch (NumberFormatException ignored) {
            // слишком большое целое — читаем как double
        }
        return Double.parseDouble(num);
    }

    private void expect(String word) {
        if (!s.startsWith(word, pos)) {
            throw error("Ожидалось '" + word + "'");
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= s.length()) {
            throw error("Неожиданный конец JSON");
        }
        return s.charAt(pos);
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " (позиция " + pos + ")");
    }

    /** Экранирование строки для вывода в JSON/JS. */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
