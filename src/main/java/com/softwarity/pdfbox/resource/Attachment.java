package com.softwarity.pdfbox.resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A file sent along with the HTML, cited by its plain name ({@code <img src="sigwx.svg">}).
 *
 * <p>It holds no bytes itself: {@link #content()} opens the upload where the servlet container keeps
 * it (in memory, or in a temporary file above {@code PDFBOX_UPLOAD_MEMORY_THRESHOLD}) and is only read
 * when the renderer actually needs the resource. So an upload is never copied as a whole, and the
 * bytes of one resource are only held while that resource is being decoded. Valid for the duration of
 * one request.
 *
 * @param name      the plain file name the document uses to cite it
 * @param mediaType the media type derived from the (validated) file type
 * @param size      the size in bytes
 * @param content   opens a fresh stream over the content, each time it is called
 */
public record Attachment(String name, String mediaType, long size, Content content) {

    /** Opens the content; each call returns a new stream from the start. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    /** An attachment backed by bytes already in memory (tests, programmatic use). */
    public static Attachment ofBytes(String name, String mediaType, byte[] data) {
        return new Attachment(name, mediaType, data.length, () -> new ByteArrayInputStream(data));
    }

    /** Reads the whole content (only when the renderer needs it). */
    public byte[] read() {
        try (InputStream in = content.open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read attached file '" + name + "'", e);
        }
    }

    /** CSS, SVG and HTML attachments: text that may contain characters of any script. */
    public boolean isText() {
        return mediaType.startsWith("text/") || mediaType.equals("image/svg+xml");
    }
}
