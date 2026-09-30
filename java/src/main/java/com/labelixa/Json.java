package com.labelixa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader (RFC 8259) for the server's reports.
 *
 * <p>It exists because the JDK has no JSON API and this client family's
 * rule is "no dependencies": a label client is not worth Jackson or Gson
 * on every caller's classpath. It reads; it does not write, and it makes
 * no attempt at speed. Objects become {@link LinkedHashMap} (key order
 * kept), arrays {@link ArrayList}, strings {@link String}, numbers
 * {@link Long} when integral and {@link Double} otherwise, and
 * {@code true}/{@code false}/{@code null} their Java counterparts.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON document.
     *
     * @param text the document
     * @return the parsed value: {@code Map}, {@code List}, {@code String},
     *         {@code Long}, {@code Double}, {@code Boolean} or {@code null}
     * @throws IllegalArgumentException when the text is not JSON
     */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("trailing characters after the document");
        }
        return value;
    }

    /**
     * Parses a JSON document whose top level must be an object.
     *
     * @param text the document
     * @return the object as a map
     * @throws IllegalArgumentException when the text is not a JSON object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object at the top level");
        }
        return (Map<String, Object>) value;
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw error("unexpected end of document");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after a key");
            }
            pos++;
            skipWhitespace();
            out.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return out;
            } else {
                throw error("expected ',' or '}' in an object");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return out;
            } else {
                throw error("expected ',' or ']' in an array");
            }
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape '\\" + e + "'");
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean integral = true;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        try {
            if (integral) {
                try {
                    return Long.valueOf(token);
                } catch (NumberFormatException tooBig) {
                    return Double.valueOf(token);
                }
            }
            return Double.valueOf(token);
        } catch (NumberFormatException ex) {
            throw error("bad number '" + token + "'");
        }
    }

    private void expectWord(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of document");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + what);
    }
}
