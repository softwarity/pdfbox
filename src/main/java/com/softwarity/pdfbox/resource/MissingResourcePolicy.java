package com.softwarity.pdfbox.resource;

import java.util.Locale;

/** What to do when a document references resources that cannot be resolved. */
public enum MissingResourcePolicy {

    /** Refuse to produce the PDF (HTTP 422 listing every unresolved resource). The default. */
    FAIL,
    /** Produce the PDF anyway and list the unresolved resources in a response header. */
    REPORT;

    /** Lenient parsing of the query value ({@code fail} / {@code report}, any case). */
    public static MissingResourcePolicy parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
