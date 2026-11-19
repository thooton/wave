package com.wave.xp3.core.sources;

import com.wave.xp3.core.git.Tree;
import com.xp3.common.Json;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Field validation shared by the two manifest readers. */
final class Fields {
    static final Pattern FOLDER = Pattern.compile("[a-z0-9][a-z0-9-]*");
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    static final Pattern VERSION = Pattern.compile("[0-9]++(?:\\.[0-9]++)*+");
    private final Map<String, Object> values;
    private final String prefix;

    private Fields(Map<String, Object> values, String prefix) { this.values = values; this.prefix = prefix; }

    static Fields parse(byte[] bytes, String file) {
        if (bytes == null) throw new IllegalArgumentException("Missing " + file);
        if (bytes.length > Tree.MAX_FILE) throw new IllegalArgumentException(file + " exceeds 64 KiB");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Object value = Json.parse(text);
            checkUnicode(value);
            return object(value, "");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(file + " is not UTF-8");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage());
        }
    }

    private static void checkUnicode(Object value) {
        if (value instanceof String) {
            String s = (String) value;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++;
                else if (Character.isSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode in JSON string");
            }
        } else if (value instanceof Map) {
            for (Map.Entry<String, Object> entry : Json.object(value).entrySet()) {
                checkUnicode(entry.getKey());
                checkUnicode(entry.getValue());
            }
        } else if (value instanceof List) {
            for (Object item : Json.array(value)) checkUnicode(item);
        }
    }

    static Fields object(Object value, String name) {
        if (!(value instanceof Map)) throw bad(name.isEmpty() ? "manifest" : name, "must be an object");
        return new Fields(Json.object(value), name.isEmpty() ? "" : name + '.');
    }

    Map<String, Object> entries() { return Collections.unmodifiableMap(values); }

    Fields child(String key, boolean required) {
        return !required && !values.containsKey(key) ? object(Collections.emptyMap(), at(key)) : object(get(key), at(key));
    }

    Object get(String key) {
        if (!values.containsKey(key)) throw bad(at(key), "is required");
        return values.get(key);
    }

    String string(String key, int min, int max, boolean required) {
        if (!required && !values.containsKey(key)) return "";
        return string(get(key), at(key), min, max);
    }

    static String string(Object value, String name, int min, int max) {
        if (!(value instanceof String)) throw bad(name, "must be a string");
        String s = (String) value;
        int length = s.codePointCount(0, s.length());
        if (length < min) throw bad(name, "must not be empty");
        if (length > max) throw bad(name, "must have at most " + max + " characters");
        return s;
    }

    long number(String key, boolean required) {
        if (!required && !values.containsKey(key)) return 0;
        Object number = get(key);
        if (!(number instanceof BigDecimal)) throw bad(at(key), "must be a positive whole number");
        BigDecimal n = (BigDecimal) number;
        // Range check first, so e.g. 1e9999999 is rejected without expanding it.
        if (n.signum() <= 0 || n.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw bad(at(key), "must be a positive whole number in range");
        }
        try { return n.longValueExact(); }
        catch (ArithmeticException e) { throw bad(at(key), "must be a positive whole number"); }
    }

    String matching(String key, Pattern p, String hint, boolean required) {
        if (!required && !values.containsKey(key)) return "";
        return matching(get(key), at(key), p, hint);
    }

    static String matching(Object value, String name, Pattern p, String hint) {
        String text = string(value, name, 1, Integer.MAX_VALUE);
        if (!p.matcher(text).matches()) throw bad(name, "must be " + hint);
        return text;
    }

    String https(String key, boolean required) {
        if (!required && !values.containsKey(key)) return "";
        String text = string(key, 1, Integer.MAX_VALUE, required);
        try {
            URI uri = new URI(text);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() > 65535) throw badUrl(key);
        } catch (URISyntaxException e) { throw badUrl(key); }
        return text;
    }

    private IllegalArgumentException badUrl(String key) { return bad(at(key), "must be an https:// URL with a host"); }

    List<Object> array(String key, int min, int max) {
        if (!values.containsKey(key)) return Collections.emptyList();
        Object value = get(key);
        if (!(value instanceof List)) throw bad(at(key), "must be an array");
        List<Object> list = Json.array(value);
        if (list.size() < min) throw bad(at(key), "must not be empty");
        if (list.size() > max) throw bad(at(key), "must have at most " + max + " items");
        return list;
    }

    List<String> strings(String key, int min, int max, boolean ids) {
        List<Object> items = array(key, min, max);
        if (items.isEmpty()) return Collections.emptyList();
        List<String> strings = new ArrayList<>(items.size());
        for (Object item : items) {
            String name = at(key) + '[' + strings.size() + ']';
            strings.add(ids ? matching(item, name, ID, "a package id") : string(item, name, 0, Integer.MAX_VALUE));
        }
        return Collections.unmodifiableList(strings);
    }

    String at(String key) { return prefix + key; }
    static IllegalArgumentException bad(String name, String problem) { return new IllegalArgumentException(name + ' ' + problem); }
}
