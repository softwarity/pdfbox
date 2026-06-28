package com.softwarity.pdfbox.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder.PdfAConformance;

/**
 * Public output standards exposed by the API, mapped to the openhtmltopdf conformance levels.
 *
 * <p>Levels ending in {@code A} are accessible/tagged (PDF/UA-style structure), {@code B} are
 * visual-only and {@code U} add guaranteed Unicode mapping.
 */
public enum PdfAStandard {

    /** Plain PDF, no PDF/A constraints. */
    NONE(PdfAConformance.NONE, false, 0f, 0, "—"),

    PDF_A_1A(PdfAConformance.PDFA_1_A, true, 1.4f, 1, "A"),
    PDF_A_1B(PdfAConformance.PDFA_1_B, false, 1.4f, 1, "B"),

    PDF_A_2A(PdfAConformance.PDFA_2_A, true, 1.7f, 2, "A"),
    PDF_A_2B(PdfAConformance.PDFA_2_B, false, 1.7f, 2, "B"),
    PDF_A_2U(PdfAConformance.PDFA_2_U, false, 1.7f, 2, "U"),

    PDF_A_3A(PdfAConformance.PDFA_3_A, true, 1.7f, 3, "A"),
    PDF_A_3B(PdfAConformance.PDFA_3_B, false, 1.7f, 3, "B"),
    PDF_A_3U(PdfAConformance.PDFA_3_U, false, 1.7f, 3, "U");

    private final PdfAConformance conformance;
    private final boolean accessible;
    private final float pdfVersion;
    private final int part;
    private final String level;

    PdfAStandard(PdfAConformance conformance, boolean accessible, float pdfVersion,
                 int part, String level) {
        this.conformance = conformance;
        this.accessible = accessible;
        this.pdfVersion = pdfVersion;
        this.part = part;
        this.level = level;
    }

    public PdfAConformance conformance() {
        return conformance;
    }

    /** Required PDF header/catalog version: 1.4 for PDF/A-1, 1.7 for PDF/A-2 and PDF/A-3. */
    public float pdfVersion() {
        return pdfVersion;
    }

    public boolean isPdfA() {
        return this != NONE;
    }

    /** True for the accessible/tagged ("A") levels, which also benefit from PDF/UA tagging. */
    public boolean isAccessible() {
        return accessible;
    }

    /** PDF/A part number (1, 2 or 3), or 0 for {@link #NONE}. Part drives the base feature set. */
    public int part() {
        return part;
    }

    /** Conformance level: {@code "A"} (accessible), {@code "B"} (basic) or {@code "U"} (Unicode). */
    public String level() {
        return level;
    }
}
