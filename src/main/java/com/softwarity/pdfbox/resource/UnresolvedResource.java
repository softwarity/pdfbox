package com.softwarity.pdfbox.resource;

/**
 * A resource the document references but that could not be used.
 *
 * @param reference the reference as written in the document (or in the stylesheet / SVG citing it)
 * @param reason    why it was not used
 * @param detail    a human-readable explanation
 */
public record UnresolvedResource(String reference, Reason reason, String detail) {

    public enum Reason {
        /** Never fetched: scheme or host not permitted ({@code file:}, {@code jar:}, unlisted host…). */
        NOT_ALLOWED("not-allowed"),
        /** No attached file of that name, or the remote server answered 404/410. */
        NOT_FOUND("not-found"),
        /** The remote fetch did not complete within {@code PDFBOX_FETCH_TIMEOUT}. */
        TIMEOUT("timeout"),
        /** Larger than {@code PDFBOX_FETCH_MAX_BYTES}. */
        TOO_LARGE("too-large"),
        /** Any other failure: HTTP error status, connection refused, invalid data URI… */
        FETCH_ERROR("fetch-error");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** Stable, lower-case code used in the 422 body and the response header. */
        public String code() {
            return code;
        }
    }
}
