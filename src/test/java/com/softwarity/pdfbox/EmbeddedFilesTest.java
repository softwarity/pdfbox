package com.softwarity.pdfbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationException;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.resource.Attachment;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;
import com.softwarity.pdfbox.resource.UnresolvedResource;
import com.softwarity.pdfbox.resource.UnresolvedResourcesException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Files embedded in the PDF with {@code <a href="…" download="…">}: PDF/A-3 (incl. Factur-X invoices),
 * PDF/UA-1 and plain PDF embed them, conformant per veraPDF; PDF/A-1 and PDF/A-2 refuse them — and
 * report the refusal (422 / {@code X-Pdfbox-Unresolved-*}) instead of dropping the file silently.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbeddedFilesTest {

    @Autowired
    PdfGenerationService service;

    @Autowired
    MockMvc mockMvc;

    static final String INVOICE_HTML = "<html><head><title>Invoice INV-2026-042</title></head><body>"
            + "<h1>Invoice INV-2026-042</h1><p>Total due: 1 200.00 EUR</p>"
            + "<p><a href=\"factur-x.xml\" download=\"factur-x.xml\" title=\"Factur-X invoice\">"
            + "Structured invoice (XML)</a></p></body></html>";

    static byte[] facturX() throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/attachments/factur-x.xml"));
    }

    static byte[] facturX(String guideline) throws IOException {
        return new String(facturX(), StandardCharsets.UTF_8)
                .replace("urn:cen.eu:en16931:2017</ram:ID>", guideline + "</ram:ID>")
                .getBytes(StandardCharsets.UTF_8);
    }

    static Attachments attached(String name, String type, byte[] data) {
        return Attachments.of(List.of(Attachment.ofBytes(name, type, data)));
    }

    /** Embedded files by name, read the way invoice tools do: the catalog's EmbeddedFiles name tree. */
    static Map<String, PDComplexFileSpecification> embeddedFiles(PDDocument doc) throws IOException {
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        return names == null || names.getEmbeddedFiles() == null ? Map.of() : names.getEmbeddedFiles().getNames();
    }

    @ParameterizedTest(name = "Factur-X invoice in {0}")
    @EnumSource(value = PdfAStandard.class, names = {"PDF_A_3A", "PDF_A_3B", "PDF_A_3U"})
    void facturXInvoiceIsEmbeddedDeclaredAndConformant(PdfAStandard standard) throws Exception {
        byte[] xml = facturX();
        byte[] pdf = service.render(INVOICE_HTML, standard, attached("factur-x.xml", "text/xml", xml),
                MissingResourcePolicy.FAIL).pdf();

        assertThat(PdfTestSupport.veraPdfFailures(pdf, standard)).as("%s", standard).isEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDComplexFileSpecification spec = embeddedFiles(doc).get("factur-x.xml");
            assertThat(spec).as("factur-x.xml in the EmbeddedFiles name tree").isNotNull();
            assertThat(spec.getEmbeddedFile().toByteArray()).isEqualTo(xml); // byte for byte
            assertThat(spec.getEmbeddedFile().getSubtype()).isEqualTo("text/xml");
            assertThat(spec.getFileUnicode()).isEqualTo("factur-x.xml");
            assertThat(spec.getFileDescription()).isEqualTo("Factur-X invoice");
            assertThat(spec.getCOSObject().getCOSName(COSName.getPDFName("AFRelationship")).getName())
                    .isEqualTo("Alternative");
            COSArray af = (COSArray) doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
            assertThat(af).as("catalog /AF").isNotNull();
            assertThat(af.getObject(0)).isSameAs(spec.getCOSObject());

            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(xmp).contains("<fx:DocumentType>INVOICE</fx:DocumentType>",
                    "<fx:DocumentFileName>factur-x.xml</fx:DocumentFileName>", "<fx:Version>1.0</fx:Version>",
                    "<fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>",
                    "<pdfaSchema:namespaceURI>urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#</pdfaSchema:namespaceURI>");
        }
    }

    @Test
    void theConformanceLevelAndDefaultRelationshipFollowTheInvoiceProfile() throws Exception {
        byte[] pdf = service.render(INVOICE_HTML, PdfAStandard.PDF_A_3B,
                attached("factur-x.xml", "text/xml", facturX("urn:factur-x.eu:1p0:minimum")),
                MissingResourcePolicy.FAIL).pdf();
        assertThat(PdfTestSupport.veraPdfFailures(pdf, PdfAStandard.PDF_A_3B)).isEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(embeddedFiles(doc).get("factur-x.xml").getCOSObject()
                    .getCOSName(COSName.getPDFName("AFRelationship")).getName()).isEqualTo("Data");
            assertThat(new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8))
                    .contains("<fx:ConformanceLevel>MINIMUM</fx:ConformanceLevel>");
        }
    }

    @Test
    void anExplicitRelationshipWins() throws Exception {
        String html = INVOICE_HTML.replace("download=\"factur-x.xml\"", "download=\"factur-x.xml\" relationship=\"Source\"");
        byte[] pdf = service.render(html, PdfAStandard.PDF_A_3B, attached("factur-x.xml", "text/xml", facturX()),
                MissingResourcePolicy.FAIL).pdf();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(embeddedFiles(doc).get("factur-x.xml").getCOSObject()
                    .getCOSName(COSName.getPDFName("AFRelationship")).getName()).isEqualTo("Source");
        }
    }

    @Test
    void aFacturXFileWithoutAKnownProfileIsRefused() throws Exception {
        assertThatThrownBy(() -> service.render(INVOICE_HTML, PdfAStandard.PDF_A_3B,
                attached("factur-x.xml", "text/xml", "<note>not an invoice</note>".getBytes(StandardCharsets.UTF_8)),
                MissingResourcePolicy.FAIL))
                .isInstanceOf(PdfGenerationException.class)
                .hasMessageContaining("'factur-x.xml'")
                .hasMessageContaining("GuidelineSpecifiedDocumentContextParameter/ID is missing");
    }

    @Test
    void anyOtherFileIsEmbeddedAsAPlainAttachment() throws Exception {
        byte[] csv = "date;amount\n2026-11-18;1200.00\n".getBytes(StandardCharsets.UTF_8);
        String html = "<p><a href=\"data:text/csv;base64," + Base64.getEncoder().encodeToString(csv)
                + "\" download=\"figures.csv\">Figures</a></p>";
        byte[] pdf = service.render(html, PdfAStandard.PDF_A_3U, Attachments.none(), MissingResourcePolicy.FAIL).pdf();

        assertThat(PdfTestSupport.veraPdfFailures(pdf, PdfAStandard.PDF_A_3U)).isEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDComplexFileSpecification spec = embeddedFiles(doc).get("figures.csv");
            assertThat(spec.getEmbeddedFile().toByteArray()).isEqualTo(csv);
            assertThat(spec.getEmbeddedFile().getSubtype()).isEqualTo("text/csv");
            assertThat(spec.getCOSObject().getCOSName(COSName.getPDFName("AFRelationship")).getName())
                    .isEqualTo("Unspecified");
            assertThat(new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8))
                    .doesNotContain("fx:");
        }
    }

    @Test
    void pdfUa1EmbedsAndStaysAccessible() throws Exception {
        byte[] pdf = service.render(INVOICE_HTML, PdfAStandard.PDF_UA_1,
                attached("factur-x.xml", "text/xml", facturX()), MissingResourcePolicy.FAIL).pdf();
        assertThat(PdfTestSupport.veraPdfFailures(pdf, PdfAStandard.PDF_UA_1)).isEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(embeddedFiles(doc)).containsKey("factur-x.xml");
        }
    }

    @Test
    void plainPdfEmbedsAsAFileAttachmentAnnotation() throws Exception {
        byte[] xml = facturX();
        byte[] pdf = service.render(INVOICE_HTML, PdfAStandard.NONE, attached("factur-x.xml", "text/xml", xml),
                MissingResourcePolicy.FAIL).pdf();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDAnnotation annotation = doc.getPage(0).getAnnotations().stream()
                    .filter(PDAnnotationFileAttachment.class::isInstance).findFirst().orElseThrow();
            var spec = (PDComplexFileSpecification) ((PDAnnotationFileAttachment) annotation).getFile();
            assertThat(spec.getEmbeddedFile().toByteArray()).isEqualTo(xml);
        }
    }

    /** The regression: openhtmltopdf's default access control used to drop the file without a word. */
    @ParameterizedTest(name = "{0} refuses embedded files, loudly")
    @EnumSource(value = PdfAStandard.class, names = {"PDF_A_1A", "PDF_A_1B", "PDF_A_2A", "PDF_A_2B", "PDF_A_2U"})
    void pdfA1AndPdfA2RefuseEmbeddedFilesLoudly(PdfAStandard standard) throws Exception {
        Attachments invoice = attached("factur-x.xml", "text/xml", facturX());

        assertThatThrownBy(() -> service.render(INVOICE_HTML, standard, invoice, MissingResourcePolicy.FAIL))
                .isInstanceOf(UnresolvedResourcesException.class)
                .satisfies(e -> assertThat(((UnresolvedResourcesException) e).unresolved())
                        .singleElement()
                        .satisfies(u -> {
                            assertThat(u.reference()).isEqualTo("factur-x.xml");
                            assertThat(u.reason()).isEqualTo(UnresolvedResource.Reason.NOT_ALLOWED);
                            assertThat(u.detail()).contains("PDF/A-" + standard.part()).contains("PDF_A_3B");
                        }));

        var report = service.render(INVOICE_HTML, standard, invoice, MissingResourcePolicy.REPORT);
        assertThat(report.unresolved()).hasSize(1);
        try (PDDocument doc = Loader.loadPDF(report.pdf())) {
            assertThat(embeddedFiles(doc)).isEmpty();
        }
        assertThat(PdfTestSupport.veraPdfFailures(report.pdf(), standard)).isEmpty();
    }

    @Test
    void overTheApiAnAttachedFacturXIsEmbeddedAndARefusalIsA422() throws Exception {
        MockMultipartFile html = new MockMultipartFile("file", "invoice.html", MediaType.TEXT_HTML_VALUE,
                INVOICE_HTML.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile xml = new MockMultipartFile("resources", "factur-x.xml", "text/xml", facturX());

        byte[] pdf = mockMvc.perform(multipart("/api/v1/pdf").file(html).file(xml).param("standard", "PDF_A_3B"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(embeddedFiles(doc)).containsKey("factur-x.xml");
        }

        mockMvc.perform(multipart("/api/v1/pdf").file(html).file(xml).param("standard", "PDF_A_2B"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("factur-x.xml")))
                .andExpect(content().string(containsString("PDF/A-2 only allows embedding PDF/A documents")));
        mockMvc.perform(multipart("/api/v1/pdf").file(html).file(xml).param("standard", "PDF_A_2B")
                        .param("onMissingResource", "report"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfbox-Unresolved-Count", "1"));
    }
}
