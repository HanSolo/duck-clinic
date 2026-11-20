package eu.hansolo.mcp.client.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Internal. Encoding of header values such as {@code Mcp-Name}: the mirror of the server's decoder. */
public final class HeaderValues {

    private static final String PREFIX = "=?base64?";
    private static final String SUFFIX = "?=";


    private HeaderValues() {
    }


    /** Returns the value unchanged if it is printable ASCII without leading or trailing blanks, otherwise {@code =?base64?...?=} with padding. */
    public static String encode(String value) {
        if (isPlain(value)) {
            return value;
        }
        return PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + SUFFIX;
    }

    private static boolean isPlain(String value) {
        if (value.isEmpty() || value.startsWith(PREFIX) || value.charAt(0) == ' ' || value.charAt(0) == '\t' || value.charAt(value.length() - 1) == ' ' || value.charAt(value.length() - 1) == '\t') {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
