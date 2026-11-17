package com.softwarity.pdfbox.resource;

/**
 * A file sent along with the HTML, cited by its plain name ({@code <img src="sigwx.svg">}). Held in
 * memory for the duration of one request only.
 *
 * @param name      the plain file name the document uses to cite it
 * @param mediaType the media type derived from the (validated) file type
 * @param data      the content
 */
public record Attachment(String name, String mediaType, byte[] data) {

    /** CSS, SVG and HTML attachments: text that may contain characters of any script. */
    public boolean isText() {
        return mediaType.startsWith("text/") || mediaType.equals("image/svg+xml");
    }
}
