package io.github.calendula597.testplatform.platformserver.testcase;

/**
 * Neutralises spreadsheet/CSV injection: values starting with formula
 * trigger characters are prefixed so Excel never evaluates them.
 */
public final class CellSanitizer {

    private CellSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replace("\r\n", "\n");
        if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) {
            return "'" + safe;
        }
        return safe;
    }
}
