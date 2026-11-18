package com.softwarity.pdfbox.pdf;

import java.io.InputStream;
import java.util.Properties;

/**
 * Versions of the libraries that lay out and write the PDF, read once from the jars actually on the
 * classpath (not from the build file, so the value cannot drift from what runs).
 *
 * <p>Clients record {@link #header()} — returned as {@code X-Pdfbox-Engine} on every PDF response —
 * so that a layout change can be traced to an engine upgrade. The same versions go into the PDF's
 * {@code Producer} ({@link #producer()}), where they travel with the file.
 */
public final class EngineInfo {

    /** Response header carrying {@link #header()}. */
    public static final String HEADER = "X-Pdfbox-Engine";

    private static final String OPENHTMLTOPDF = openHtmlToPdfVersion();
    private static final String PDFBOX = orUnknown(org.apache.pdfbox.util.Version.getVersion());
    private static final String BATIK = orUnknown(org.apache.batik.Version.getVersion());

    private EngineInfo() {
    }

    /** Product tokens, e.g. {@code openhtmltopdf/1.1.114 pdfbox/3.0.8 batik/1.20}. */
    public static String header() {
        return "openhtmltopdf/" + OPENHTMLTOPDF + " pdfbox/" + PDFBOX + " batik/" + BATIK;
    }

    /** The PDF {@code Producer}, e.g. {@code openhtmltopdf 1.1.114 (PDFBox 3.0.8, Batik 1.20)}. */
    public static String producer() {
        return "openhtmltopdf " + OPENHTMLTOPDF + " (PDFBox " + PDFBOX + ", Batik " + BATIK + ")";
    }

    private static String openHtmlToPdfVersion() {
        String resource = "/META-INF/maven/io.github.openhtmltopdf/openhtmltopdf-core/pom.properties";
        try (InputStream in = com.openhtmltopdf.util.XRLog.class.getResourceAsStream(resource)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                return orUnknown(p.getProperty("version"));
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }

    private static String orUnknown(String version) {
        return version == null || version.isBlank() ? "unknown" : version.trim();
    }
}
