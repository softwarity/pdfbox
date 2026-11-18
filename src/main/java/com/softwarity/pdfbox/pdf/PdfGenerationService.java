package com.softwarity.pdfbox.pdf;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;
import com.softwarity.pdfbox.resource.RenderResources;
import com.softwarity.pdfbox.resource.ResourceFetcher;
import com.softwarity.pdfbox.resource.UnresolvedResource;
import com.softwarity.pdfbox.resource.UnresolvedResourcesException;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer.SvgScriptMode;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
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
    private final ResourceFetcher fetcher;
    private final SvgSanitizer svgSanitizer;

    public PdfGenerationService(FontService fontService, HtmlNormalizer htmlNormalizer,
                                ColorProfiles colorProfiles, ResourceFetcher fetcher,
                                SvgSanitizer svgSanitizer) {
        this.fontService = fontService;
        this.htmlNormalizer = htmlNormalizer;
        this.colorProfiles = colorProfiles;
        this.fetcher = fetcher;
        this.svgSanitizer = svgSanitizer;
    }

    /** The PDF and the resources that could not be resolved (empty unless the policy is REPORT). */
    public record RenderResult(byte[] pdf, List<UnresolvedResource> unresolved) {}

    /**
     * Renders self-contained HTML (no attachments); any unresolvable resource fails the render.
     *
     * @throws UnresolvedResourcesException if the document references a resource that cannot be used
     */
    public byte[] render(String html, PdfAStandard standard) {
        return render(html, standard, Attachments.none(), MissingResourcePolicy.FAIL).pdf();
    }

    /**
     * Renders HTML to a PDF document.
     *
     * @param html        source HTML/CSS (tolerant of malformed markup)
     * @param standard    target output standard
     * @param attachments files the HTML cites by plain name (CSS, images, SVG, fonts)
     * @param policy      {@code FAIL}: throw {@link UnresolvedResourcesException} if any resource
     *                    cannot be resolved; {@code REPORT}: render anyway and return the list
     * @return the generated PDF bytes and the unresolved resources
     */
    public RenderResult render(String html, PdfAStandard standard, Attachments attachments,
                               MissingResourcePolicy policy) {
        // One resource policy per render: resolution, allow-list, fetch cache, unresolved list.
        // PDF/A-1 forbids transparency: flatten it over white at the source (images, SVG opacity);
        // the post-pass then neutralises whatever is left. Parts 2/3 keep real transparency.
        boolean flatten = standard.part() == 1;
        RenderResources resources = new RenderResources(attachments, fetcher, svgSanitizer, flatten);
        org.w3c.dom.Document doc = htmlNormalizer.toW3c(html, resources.svgInliner(null), flatten);

        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        // SVG through Batik: no script, and Batik may only read data: URIs by itself — every other
        // reference has been resolved (or refused) and inlined beforehand. One drawer per render: it
        // holds the fonts and user agent of this document.
        builder.useSVGDrawer(new BatikSVGDrawer(SvgScriptMode.SECURE, Set.of("data")));
        // The HTML, then each attached stylesheet / SVG read one at a time: the scripts they contain
        // decide which faces are registered (and whether Batik gets them).
        fontService.registerFonts(builder, attachments.textsAfter(html));

        // Every stylesheet, @font-face, image and SVG load goes through RenderResources. The stream
        // factories replace openhtmltopdf's defaults wholesale (no URL.openStream fallback for the
        // schemes it serves), and the access controller refuses every other scheme before any load.
        builder.useUriResolver(resources);
        builder.useExternalResourceAccessControl(resources::allow,
                ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
        builder.useProtocolsStreamImplementation(resources, RenderResources.SCHEMES);

        if (standard.isPdfA()) {
            builder.usePdfAConformance(standard.conformance());
            builder.useColorProfile(colorProfiles.srgb());
            if (standard.isAccessible()) {
                builder.usePdfUaAccessbility(true);
            }
        }

        builder.withW3cDocument(doc, "");

        byte[] pdf;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.toStream(out);
            builder.run();
            pdf = out.toByteArray();
        } catch (Exception e) {
            throw new PdfGenerationException(e.getMessage(), e);
        }

        List<UnresolvedResource> unresolved = resources.unresolved();
        if (!unresolved.isEmpty() && policy == MissingResourcePolicy.FAIL) {
            throw new UnresolvedResourcesException(unresolved);
        }
        try {
            return new RenderResult(standard.isPdfA() ? postProcessPdfA(pdf, standard) : pdf,
                    unresolved);
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

    /**
     * PDF/A-1 safety net (ISO 19005-1 §6.4), after transparency has been flattened at the source:
     * whatever is left — an opacity in an SVG {@code <style>} rule, a gradient under a transparent
     * group, a colour the flattener does not know — is made opaque rather than left non-conformant.
     * Constant alpha forced to 1, soft masks removed, blend modes normal, transparency groups dropped.
     */
    private static void neutralizeTransparency(PDDocument doc) {
        COSName extGState = COSName.EXT_G_STATE;
        for (COSObjectKey key : doc.getDocument().getXrefTable().keySet()) {
            COSObject object = doc.getDocument().getObjectFromPool(key);
            if (object == null || !(object.getObject() instanceof COSDictionary dict)) {
                continue;
            }
            if (extGState.equals(dict.getCOSName(COSName.TYPE)) || dict.containsKey(COSName.CA)
                    || dict.containsKey(COSName.CA_NS) || dict.containsKey(COSName.SMASK)
                    && !(dict instanceof COSStream)) {
                if (dict.containsKey(COSName.CA)) {
                    dict.setFloat(COSName.CA, 1f);
                }
                if (dict.containsKey(COSName.CA_NS)) {
                    dict.setFloat(COSName.CA_NS, 1f);
                }
                if (dict.containsKey(COSName.SMASK) && !(dict instanceof COSStream)) {
                    dict.setItem(COSName.SMASK, COSName.NONE);
                }
                if (dict.containsKey(COSName.BM)) {
                    dict.setItem(COSName.BM, COSName.getPDFName("Normal"));
                }
            }
            if (dict instanceof COSStream stream && COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                stream.removeItem(COSName.SMASK);
                stream.removeItem(COSName.getPDFName("SMaskInData"));
            }
            if (dict.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group
                    && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
                dict.removeItem(COSName.GROUP);
            }
        }
    }

    /**
     * PDF/A post-pass: clears image interpolation, neutralises leftover transparency for PDF/A-1, and
     * brings the header/catalog version to the one {@link PdfAStandard} declares.
     *
     * <p>The document is re-saved without compression: a compressed save would write object streams
     * and a cross-reference stream, which PDF/A-1 forbids (and the classic table is fine for 2/3).
     *
     * <p>Version: openhtmltopdf 1.1.114 already writes PDF/A-1 as {@code %PDF-1.4} with a classic
     * cross-reference table, so for part 1 there is nothing to change. Parts 2/3 come out as 1.6 and
     * are raised to 1.7: the version is set, the trailer keys left from the source cross-reference
     * stream are dropped so the rewritten classic trailer is clean, and the header line is patched in
     * place — PDFBox does not rewrite it (same length, so xref byte offsets stay valid).
     */
    private byte[] postProcessPdfA(byte[] pdf, PdfAStandard standard) throws Exception {
        float target = standard.pdfVersion();
        boolean raiseVersion;
        byte[] saved;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            raiseVersion = doc.getDocument().getVersion() != target; // header version, not the catalog's
            if (raiseVersion) {
                doc.getDocument().setVersion(target);
                doc.getDocumentCatalog().getCOSObject().setName(COSName.VERSION, String.valueOf(target));
                var trailer = doc.getDocument().getTrailer();
                for (COSName stale : new COSName[]{COSName.TYPE, COSName.W, COSName.INDEX,
                        COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.PREV,
                        COSName.getPDFName("XRefStm")}) {
                    trailer.removeItem(stale);
                }
            }
            clearImageInterpolation(doc);
            if (standard.part() == 1) {
                neutralizeTransparency(doc);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out, CompressParameters.NO_COMPRESSION);
                saved = out.toByteArray();
            }
        }
        String v = String.valueOf(target); // "1.7"
        if (raiseVersion && saved.length >= 8 && saved[0] == '%' && saved[1] == 'P' && saved[2] == 'D'
                && saved[3] == 'F' && saved[4] == '-') {
            saved[5] = (byte) v.charAt(0);
            saved[6] = (byte) v.charAt(1);
            saved[7] = (byte) v.charAt(2);
        }
        return saved;
    }
}
