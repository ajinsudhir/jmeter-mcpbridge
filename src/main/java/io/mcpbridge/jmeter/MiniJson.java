package io.mcpbridge.jmeter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately minimal, dependency-free JSON parser — just enough to read
 * HAR files (which are themselves JSON) and to detect/flatten JSON response
 * bodies during correlation analysis. Not a general-purpose JSON library.
 *
 * Parses into plain Java objects: {@code Map<String,Object>} for objects,
 * {@code List<Object>} for arrays, {@code String}, {@code Double},
 * {@code Boolean}, or {@code null} for leaves.
 */
final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Throws IllegalArgumentException if the text isn't valid JSON. */
    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object value = p.parseValue();
        p.skipWs();
        return value;
    }

    private Object parseValue() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON at " + pos);
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': return parseString();
            case 't': expectLiteral("true"); return Boolean.TRUE;
            case 'f': expectLiteral("false"); return Boolean.FALSE;
            case 'n': expectLiteral("null"); return null;
            default: return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
            }
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(parseValue());
            skipWs();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
            }
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unterminated string at " + pos);
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad escape \\" + esc + " at " + pos);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || "+-.eE".indexOf(src.charAt(pos)) >= 0)) {
            pos++;
        }
        String num = src.substring(start, pos);
        if (num.isEmpty()) {
            throw new IllegalArgumentException("Invalid number at " + start);
        }
        return Double.parseDouble(num);
    }

    private void expectLiteral(String literal) {
        if (pos + literal.length() > src.length() || !src.regionMatches(pos, literal, 0, literal.length())) {
            throw new IllegalArgumentException("Expected '" + literal + "' at " + pos);
        }
        pos += literal.length();
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
        }
        pos++;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON at " + pos);
        }
        return src.charAt(pos++);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    /**
     * Flattens a parsed JSON value into dot-path keys mapped to their leaf
     * string representation (numbers/booleans stringified), for scanning
     * during correlation analysis. Array indices are included in the path,
     * e.g. "data.items.0.id".
     */
    static void flatten(Object value, String prefix, Map<String, String> out) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(e.getValue(), path, out);
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                flatten(list.get(i), prefix + "." + i, out);
            }
        } else if (value instanceof String) {
            out.put(prefix, (String) value);
        } else if (value instanceof Double) {
            double d = (Double) value;
            String s = d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            out.put(prefix, s);
        } else if (value instanceof Boolean) {
            out.put(prefix, value.toString());
        }
        // null values contribute nothing to flatten() — nothing to correlate.
    }

    /** Serializes a plain Java object graph (Map/List/String/Number/Boolean/null,
     *  the same shapes {@link #parse} produces) back to JSON text. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
