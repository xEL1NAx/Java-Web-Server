package server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleJson {
    private final String text;
    private int i;

    private SimpleJson(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        SimpleJson p = new SimpleJson(text);
        Object value = p.readValue();
        p.skipWs();
        if (p.i != p.text.length()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at position " + p.i);
        }
        return value;
    }

    private Object readValue() {
        skipWs();
        if (i >= text.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = text.charAt(i);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || Character.isDigit(c)) yield readNumber();
                throw new IllegalArgumentException("Unexpected character '" + c + "' at " + i);
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek('}')) {
            expect('}');
            return map;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWs();
            if (peek('}')) {
                expect('}');
                break;
            }
            expect(',');
        }
        return map;
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek(']')) {
            expect(']');
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            if (peek(']')) {
                expect(']');
                break;
            }
            expect(',');
        }
        return list;
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (i >= text.length()) throw new IllegalArgumentException("Bad escape sequence");
                char e = text.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                        String hex = text.substring(i, i + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Unknown escape: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Object readNumber() {
        int start = i;
        if (text.charAt(i) == '-') i++;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        boolean isFloat = false;
        if (i < text.length() && text.charAt(i) == '.') {
            isFloat = true;
            i++;
            while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        }
        if (i < text.length() && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            isFloat = true;
            i++;
            if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) i++;
            while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        }
        String num = text.substring(start, i);
        return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
    }

    private Object readLiteral(String expected, Object value) {
        if (!text.startsWith(expected, i)) {
            throw new IllegalArgumentException("Expected " + expected + " at " + i);
        }
        i += expected.length();
        return value;
    }

    private void skipWs() {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private boolean peek(char c) {
        return i < text.length() && text.charAt(i) == c;
    }

    private void expect(char c) {
        skipWs();
        if (i >= text.length() || text.charAt(i) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at " + i);
        }
        i++;
    }
}
