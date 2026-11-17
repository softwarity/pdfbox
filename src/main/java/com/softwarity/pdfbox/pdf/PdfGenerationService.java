package com.softwarity.pdfbox.pdf;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Set;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer.SvgScriptMode;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

@Service
public class PdfGenerationService {

    private final FontService fontService;
    private final HtmlNormalizer htmlNormalizer;
    private final ColorProfiles colorProfiles;

    public PdfGenerationService(FontService fontService, HtmlNormalizer htmlNormalizer,
                                ColorProfiles colorProfiles) {
        this.fontService = fontService;
        this.htmlNormalizer = htmlNormalizer;
        this.colorProfiles = colorProfiles;
    }

    /** Renders HTML to a PDF document (see {@link #render(String, PdfAStandard, String)}). */
    public byte[] render(String html, PdfAStandard standard) {
        return render(html, standard, null);
    }

    /**
     * Renders HTML to a PDF document.
     *
     * @param html     source HTML/CSS (tolerant of malformed markup)
     * @param standard target output standard
     * @param baseUri  optional base URI to resolve relative resources; usually {@code null} since
     *                 everything should be inlined for offline generation
     * @return the generated PDF bytes
     */
    public byte[] render(String html, PdfAStandard standard, String baseUri) {
        org.w3c.dom.Document doc = htmlNormalizer.toW3c(html);

        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        // SVG through Batik: no script, and Batik may only read data: URIs by itself — every other
        // reference has been resolved (or refused) and inlined beforehand. One drawer per render: it
        // holds the fonts and user agent of this document.
        builder.useSVGDrawer(new BatikSVGDrawer(SvgScriptMode.SECURE, Set.of("data")));
        fontService.registerFonts(builder, html, mentionsSvg(html));

        if (standard.isPdfA()) {
            builder.usePdfAConformance(standard.conformance());
            builder.useColorProfile(colorProfiles.srgb());
            if (standard.isAccessible()) {
                builder.usePdfUaAccessbility(true);
            }
        }

        builder.withW3cDocument(doc, baseUri == null ? "" : baseUri);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.toStream(out);
            builder.run();
            byte[] pdf = out.toByteArray();
            return standard.isPdfA() ? normalizeVersion(pdf, standard.pdfVersion()) : pdf;
        } catch (Exception e) {
            throw new PdfGenerationException(e.getMessage(), e);
        }
    }

    /**
     * Every PDF/A part forbids {@code /Interpolate true} on images (ISO 19005-1 6.2.4, -2/-3 6.2.8).
     * Batik paints an SVG group with {@code opacity} as an offscreen image and the graphics bridge
     * flags it interpolated, which alone would make the file non-conformant even in PDF/A-2/3, where
     * the transparency itself is allowed. The flag is only a smoothing hint for viewers, so clearing it
     * changes nothing that PDF/A cares about.
     */
    private static void clearImageInterpolation(PDDocument doc) {
        for (COSObjectKey key : doc.getDocument().getXrefTable().keySet()) {
            COSObject object = doc.getDocument().getObjectFromPool(key);
            if (object != null && object.getObject() instanceof COSStream stream
                    && COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))
                    && stream.containsKey(COSName.INTERPOLATE)) {
                stream.setBoolean(COSName.INTERPOLATE, false);
            }
        }
    }

    /** Cheap pre-check: hand the bundled fonts to Batik only when the document may contain SVG. */
    private static boolean mentionsSvg(String content) {
        return content != null && content.toLowerCase(Locale.ROOT).contains("svg");
    }

    /**
     * Re-saves the document with the PDF version required by the target PDF/A part and without object
     * streams, so the output uses a classic cross-reference table — object/xref streams are forbidden
     * in PDF/A-1 and the byte-identical header keeps every validator happy.
     */
    private byte[] normalizeVersion(byte[] pdf, float version) throws Exception {
        String v = String.valueOf(version); // "1.4" or "1.7"
        byte[] saved;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            doc.getDocument().setVersion(version);
            doc.getDocumentCatalog().getCOSObject().setName(COSName.VERSION, v);
            // The source used a cross-reference stream; drop its dictionary keys so the rewritten
            // classic-table trailer is clean (a hybrid/stream trailer is invalid in PDF/A-1).
            var trailer = doc.getDocument().getTrailer();
            for (COSName stale : new COSName[]{COSName.TYPE, COSName.W, COSName.INDEX,
                    COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.PREV,
                    COSName.getPDFName("XRefStm")}) {
                trailer.removeItem(stale);
            }
            clearImageInterpolation(doc);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out, CompressParameters.NO_COMPRESSION);
                saved = out.toByteArray();
            }
        }
        // PDFBox refuses to downgrade the header line; patch it in place (same length keeps xref
        // offsets valid) so PDF/A-1 reports the mandatory "%PDF-1.4".
        if (saved.length >= 8 && saved[0] == '%' && saved[1] == 'P' && saved[2] == 'D'
                && saved[3] == 'F' && saved[4] == '-') {
            saved[5] = (byte) v.charAt(0);
            saved[6] = (byte) v.charAt(1);
            saved[7] = (byte) v.charAt(2);
        }
        return saved;
    }
}
