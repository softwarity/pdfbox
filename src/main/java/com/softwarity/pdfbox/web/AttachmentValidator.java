package com.softwarity.pdfbox.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.softwarity.pdfbox.resource.Attachment;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MediaTypes;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Turns the {@code resources} parts of a multipart request into {@link Attachments}, refusing
 * anything ambiguous or unexpected with a clear message (400), or too large (413). The part's file
 * name is what the HTML cites, so it must be a plain name: no {@code /}, {@code \}, {@code ..} nor
 * control character, and unique within the request. Accepted types: CSS, PNG, JPEG, GIF, WebP, SVG
 * and TrueType fonts (what the document renders), plus XML, JSON, CSV, plain text and PDF (files to
 * embed in the PDF with {@code <a href download>}) — checked on the content itself, not only on the
 * extension.
 */
@Component
class AttachmentValidator {

    /** Accepted extensions → the media type the content must actually have. */
    static final Map<String, String> ACCEPTED = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ttf", "font/ttf"),
            // Files to embed in the PDF (never rendered): invoices, data, documents.
            Map.entry("xml", "text/xml"),
            Map.entry("json", "application/json"),
            Map.entry("csv", "text/csv"),
            Map.entry("txt", "text/plain"),
            Map.entry("pdf", "application/pdf"));

    private final long maxPartSize;
    private final long maxTotalSize;

    AttachmentValidator(UploadProperties properties) {
        this.maxPartSize = properties.maxPartSize().toBytes();
        this.maxTotalSize = properties.maxTotalSize().toBytes();
    }

    Attachments validate(MultipartFile html, List<MultipartFile> parts) {
        long total = checkSize(html, "file");
        List<Attachment> attachments = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (MultipartFile part : parts == null ? List.<MultipartFile>of() : parts) {
            String name = part.getOriginalFilename();
            checkName(name);
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate attached file name '" + name
                        + "': each file of the 'resources' parts must have a unique name.");
            }
            total += checkSize(part, name);
            if (total > maxTotalSize) {
                throw new PayloadTooLargeException("The upload exceeds " + maxTotalSize
                        + " bytes in total (PDFBOX_UPLOAD_MAX_TOTAL_SIZE).");
            }
            // No copy of the upload: the attachment reads the part where the container keeps it
            // (memory, or a temporary file above the memory threshold), only when it is rendered.
            attachments.add(new Attachment(name, checkType(name, part), part.getSize(), part::getInputStream));
        }
        return Attachments.of(attachments);
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Every 'resources' part needs a file name: it is the name "
                    + "the HTML cites (e.g. <img src=\"logo.png\">).");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid attached file name '" + name + "': use a plain "
                    + "file name, without '/', '\\' or '..'.");
        }
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new IllegalArgumentException("Invalid attached file name '" + name.replaceAll("\\p{Cntrl}", "?")
                    + "': control characters are not allowed.");
        }
    }

    private long checkSize(MultipartFile part, String label) {
        if (part.getSize() > maxPartSize) {
            throw new PayloadTooLargeException("Part '" + label + "' is " + part.getSize()
                    + " bytes; the limit is " + maxPartSize + " bytes per part (PDFBOX_UPLOAD_MAX_PART_SIZE).");
        }
        return part.getSize();
    }

    /**
     * The media type for {@code name}, after checking the content really is of that type — on its
     * first bytes (magic numbers), or streamed for a stylesheet; never by loading the whole file.
     */
    private static String checkType(String name, MultipartFile part) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String expected = ACCEPTED.get(extension);
        if (expected == null) {
            throw new IllegalArgumentException("Unsupported attached file type '" + name + "'. Accepted: "
                    + "CSS (.css), images (.png, .jpg/.jpeg, .gif, .webp, .svg), TrueType fonts (.ttf) and, "
                    + "to embed in the PDF, .xml, .json, .csv, .txt and .pdf files.");
        }
        String actual = MediaTypes.sniff(head(part));
        switch (expected) {
            case "text/css" -> {
                if (!isUtf8Text(part)) {
                    throw mismatch(name, "a UTF-8 text stylesheet", actual);
                }
            }
            case "text/xml", "application/json", "text/csv", "text/plain" -> {
                if (!isUtf8Text(part)) {
                    throw mismatch(name, "a UTF-8 text file", actual);
                }
            }
            case "application/pdf" -> {
                if (!isPdf(head(part))) {
                    throw mismatch(name, "a PDF document", actual);
                }
            }
            case "image/svg+xml" -> {
                if (!expected.equals(actual)) {
                    throw mismatch(name, "an SVG document", actual);
                }
            }
            case "font/ttf" -> {
                if ("font/otf".equals(actual)) {
                    throw new IllegalArgumentException("'" + name + "' is an OpenType/CFF font, which the "
                            + "PDF renderer cannot embed: convert it to TrueType outlines first.");
                }
                if (!expected.equals(actual)) {
                    throw mismatch(name, "a TrueType font", actual);
                }
            }
            default -> {
                if (!expected.equals(actual)) {
                    throw mismatch(name, "a " + expected.substring(6).toUpperCase(java.util.Locale.ROOT)
                            + " image", actual);
                }
            }
        }
        return expected;
    }

    private static IllegalArgumentException mismatch(String name, String expected, String actual) {
        return new IllegalArgumentException("'" + name + "' is not " + expected + " (its content "
                + (actual == null ? "is not a recognised type" : "looks like " + actual) + ").");
    }

    /** The first {@value #SNIFF_BYTES} bytes: enough for every magic number and an SVG root. */
    private static byte[] head(MultipartFile part) {
        try (InputStream in = part.getInputStream()) {
            return in.readNBytes(SNIFF_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final int SNIFF_BYTES = 1024;

    /** A PDF starts with {@code %PDF-} (within the first KB, as readers tolerate leading bytes). */
    private static boolean isPdf(byte[] head) {
        String start = new String(head, StandardCharsets.ISO_8859_1);
        return start.contains("%PDF-");
    }

    /** Streams the part through a strict UTF-8 decoder; a NUL byte also disqualifies it as CSS. */
    private static boolean isUtf8Text(MultipartFile part) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(part.getInputStream(), decoder)) {
            char[] buffer = new char[8192];
            for (int n; (n = reader.read(buffer)) != -1; ) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == 0) {
                        return false;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
