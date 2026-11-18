package com.softwarity.pdfbox.resource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.imageio.ImageIO;

/** Media-type detection (declared type, file extension, magic bytes) and WebP → PNG conversion. */
public final class MediaTypes {

    private MediaTypes() {
    }

    /** Best media type: a specific declared one, else from the extension, else sniffed. */
    public static String of(String declared, String path, byte[] data) {
        String type = declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.equals("application/octet-stream") && !type.equals("text/plain")) {
            return type;
        }
        String byExtension = fromExtension(path);
        if (byExtension != null) {
            return byExtension;
        }
        String sniffed = sniff(data);
        return sniffed != null ? sniffed : type.isEmpty() ? "application/octet-stream" : type;
    }

    /** Media type implied by a file extension, or {@code null}. */
    public static String fromExtension(String path) {
        if (path == null) {
            return null;
        }
        String p = path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (p.substring(dot + 1)) {
            case "css" -> "text/css";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "ttf" -> "font/ttf";
            case "html", "htm" -> "text/html";
            default -> null;
        };
    }

    /** Media type recognised from the content's magic bytes, or {@code null}. */
    public static String sniff(byte[] d) {
        if (d == null || d.length < 4) {
            return null;
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "image/gif";
        }
        if (isWebp(d)) {
            return "image/webp";
        }
        if ((d[0] == 0 && d[1] == 1 && d[2] == 0 && d[3] == 0)
                || (d[0] == 't' && d[1] == 'r' && d[2] == 'u' && d[3] == 'e')) {
            return "font/ttf";
        }
        if (d[0] == 'O' && d[1] == 'T' && d[2] == 'T' && d[3] == 'O') {
            return "font/otf";
        }
        if (looksLikeSvg(d)) {
            return "image/svg+xml";
        }
        return null;
    }

    public static boolean isWebp(byte[] d) {
        return d != null && d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    /** True for SVG: declared as such, named {@code .svg}, or an XML document whose root is {@code svg}. */
    public static boolean isSvg(String mediaType, String uri, byte[] data) {
        if ("image/svg+xml".equals(mediaType)) {
            return true;
        }
        String u = uri == null ? "" : uri.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if ((q >= 0 ? u.substring(0, q) : u).endsWith(".svg")) {
            return true;
        }
        return mediaType != null && (mediaType.contains("xml") || mediaType.startsWith("text/plain"))
                && looksLikeSvg(data);
    }

    private static boolean looksLikeSvg(byte[] d) {
        int n = Math.min(d.length, 1024);
        String head = new String(d, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<svg");
    }

    /** Raster formats that can carry transparency (WebP is converted to PNG before this check). */
    public static boolean mayHaveAlpha(String mediaType) {
        return "image/png".equals(mediaType) || "image/gif".equals(mediaType);
    }

    /**
     * Composites an image with an alpha channel (or a transparent palette entry) over white and
     * returns it as an opaque PNG; an image without transparency is returned unchanged. PDF/A-1
     * forbids soft masks, so this is how such an image is "flattened" for it.
     */
    public static byte[] flattenOnWhite(byte[] image) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                throw new IllegalArgumentException("unreadable image");
            }
            if (!source.getColorModel().hasAlpha()) {
                return image;
            }
            BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = opaque.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            g.drawImage(source, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(opaque, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable image: " + e.getMessage(), e);
        }
    }

    /**
     * PDFBox cannot embed WebP; it is decoded (TwelveMonkeys ImageIO plugin) and re-encoded as a
     * lossless PNG, alpha channel included.
     */
    public static byte[] webpToPng(byte[] webp) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
            if (image == null) {
                throw new IllegalArgumentException("unreadable WebP image");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable WebP image: " + e.getMessage(), e);
        }
    }
}
