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
    NONE(PdfAConformance.NONE, false, 0f),

    PDF_A_1A(PdfAConformance.PDFA_1_A, true, 1.4f),
    PDF_A_1B(PdfAConformance.PDFA_1_B, false, 1.4f),

    PDF_A_2A(PdfAConformance.PDFA_2_A, true, 1.7f),
    PDF_A_2B(PdfAConformance.PDFA_2_B, false, 1.7f),
    PDF_A_2U(PdfAConformance.PDFA_2_U, false, 1.7f),

    PDF_A_3A(PdfAConformance.PDFA_3_A, true, 1.7f),
    PDF_A_3B(PdfAConformance.PDFA_3_B, false, 1.7f),
    PDF_A_3U(PdfAConformance.PDFA_3_U, false, 1.7f);

    private final PdfAConformance conformance;
    private final boolean accessible;
    private final float pdfVersion;

    PdfAStandard(PdfAConformance conformance, boolean accessible, float pdfVersion) {
        this.conformance = conformance;
        this.accessible = accessible;
        this.pdfVersion = pdfVersion;
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
}
