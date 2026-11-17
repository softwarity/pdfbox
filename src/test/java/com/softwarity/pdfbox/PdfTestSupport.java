package com.softwarity.pdfbox;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import com.softwarity.pdfbox.pdf.PdfAStandard;

/** Shared helpers to inspect generated PDFs: what is drawn, which fonts and images are embedded. */
final class PdfTestSupport {

    private PdfTestSupport() {
    }

    /** Rasterizes one page at 72 dpi (1 px per PDF point). */
    static BufferedImage renderPage(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(page, 1f);
        }
    }

    /** Counts the pixels of the first page whose RGB value satisfies {@code test}. */
    static long countPixels(byte[] pdf, IntPredicate test) throws IOException {
        BufferedImage img = renderPage(pdf, 0);
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (test.test(img.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Roughly pure red (the colour the test images and SVGs are painted with). */
    static boolean red(int rgb) {
        return ((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60;
    }

    static boolean green(int rgb) {
        return ((rgb >> 16) & 0xFF) < 60 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) < 60;
    }

    static boolean blue(int rgb) {
        return ((rgb >> 16) & 0xFF) < 60 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) > 200;
    }

    static boolean dark(int rgb) {
        return ((rgb >> 16) & 0xFF) < 90 && ((rgb >> 8) & 0xFF) < 90 && (rgb & 0xFF) < 90;
    }

    /** Names of every font used on any page, including inside form XObjects. */
    static Set<String> embeddedFonts(byte[] pdf) throws IOException {
        Set<String> names = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                collect(page.getResources(), names, new HashSet<>());
            }
        }
        return names;
    }

    /** Number of image XObjects drawn on any page, including inside form XObjects. */
    static int imageCount(byte[] pdf) throws IOException {
        Set<String> images = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                collect(page.getResources(), new HashSet<>(), images);
            }
        }
        return images.size();
    }

    private static void collect(PDResources resources, Set<String> fonts, Set<String> images)
            throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            if (font != null) {
                fonts.add(font.getName());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            var xobject = resources.getXObject(name);
            if (xobject instanceof PDFormXObject form) {
                collect(form.getResources(), fonts, images);
            } else if (xobject instanceof PDImageXObject) {
                images.add(String.valueOf(xobject.getCOSObject().hashCode()) + name.getName());
            }
        }
    }

    // ---------------------------------------------------------------- veraPDF

    private static volatile boolean veraReady;

    /** Validates {@code pdf} against the flavour of {@code standard}; returns the distinct failures. */
    static List<String> veraPdfFailures(byte[] pdf, PdfAStandard standard) throws Exception {
        if (!veraReady) {
            synchronized (PdfTestSupport.class) {
                if (!veraReady) {
                    VeraGreenfieldFoundryProvider.initialise();
                    veraReady = true;
                }
            }
        }
        PDFAFlavour flavour = PDFAFlavour.fromString(standard.part() + standard.level().toLowerCase());
        try (PDFAParser parser = Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            ValidationResult result = validator.validate(parser);
            return result.getTestAssertions().stream()
                    .filter(a -> a.getStatus() == TestAssertion.Status.FAILED)
                    .map(a -> a.getRuleId().getClause() + "-" + a.getRuleId().getTestNumber() + ": "
                            + a.getMessage())
                    .distinct()
                    .toList();
        }
    }
}
