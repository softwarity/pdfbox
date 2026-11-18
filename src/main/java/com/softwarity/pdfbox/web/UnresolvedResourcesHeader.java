package com.softwarity.pdfbox.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.softwarity.pdfbox.resource.UnresolvedResource;

/**
 * Formats unresolved resources for the {@value #NAME} response header as an RFC 8941 structured
 * list: {@code "logo.png";reason=not-found, "https://x.org/a.png";reason=not-allowed}.
 *
 * <p>Header values are ASCII: in each reference, {@code %} and every non-printable or non-ASCII
 * byte (UTF-8) is percent-encoded, so decoding once gives the reference as written. The list is cut
 * at {@value #MAX_LENGTH} characters to stay well under server header limits; {@value #COUNT}
 * always carries the full count.
 */
final class UnresolvedResourcesHeader {

    static final String NAME = "X-Pdfbox-Unresolved-Resources";
    static final String COUNT = "X-Pdfbox-Unresolved-Count";
    static final int MAX_LENGTH = 4096;

    private UnresolvedResourcesHeader() {
    }

    static String format(List<UnresolvedResource> unresolved) {
        StringBuilder out = new StringBuilder();
        for (UnresolvedResource u : unresolved) {
            String member = "\"" + escape(u.reference()) + "\";reason=" + u.reason().code();
            int extra = (out.isEmpty() ? 0 : 2) + member.length();
            if (out.length() + extra > MAX_LENGTH) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(member);
        }
        return out.toString();
    }

    private static String escape(String reference) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : reference.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '%' || c < 0x20 || c > 0x7E) {
                out.writeBytes(String.format("%%%02X", c).getBytes(StandardCharsets.US_ASCII));
            } else if (c == '"' || c == '\\') {
                out.write('\\');
                out.write(c);
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.US_ASCII);
    }
}
