package com.labelixa;

import java.util.Map;

/**
 * WIRE VOCABULARY: the API's own field names that this client reads by name,
 * in one file.
 *
 * <p>Some fields of the Labelixa API are named in Turkish. They are PROTOCOL:
 * renaming them here would not translate anything, it would simply stop
 * reading the server's answer. So they are not translated, and they are not
 * hidden either. They live in this single file so the publish guard can
 * exempt exactly one file from the "no Turkish words" rule instead of losing
 * that rule across the whole package. The guard still checks this file for
 * ticket ids, non-English characters and audit markers.
 *
 * <p>Reference: https://labelixa.com/docs/api
 */
public final class Contract {

    /** The field of an error answer (any non-2xx) that carries the server's sentence. */
    public static final String ERROR_FIELD = "hata";

    private Contract() {
    }

    /**
     * The sentence of an API error answer ({@code {"<ERROR_FIELD>": "..."}}),
     * or the body as it came when it has any other shape.
     *
     * @param raw the response body
     * @return the server's sentence, or {@code raw} unchanged
     */
    public static String serverText(String raw) {
        try {
            Map<String, Object> body = Json.parseObject(raw);
            Object sentence = body.get(ERROR_FIELD);
            if (sentence instanceof String) {
                return (String) sentence;
            }
        } catch (IllegalArgumentException notAnObject) {
            // Not a JSON object: keep the raw text.
        }
        return raw;
    }
}
