package com.softwarity.pdfbox;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-level integration tests covering every {@link PdfAStandard}: the PDF header version, the
 * PDF/A identification block (part + conformance) and the tagged structure of the accessible "A"
 * levels. Assertions inspect the raw bytes — the PDF/A pass re-saves uncompressed, so the catalog and
 * the XMP metadata are plain ASCII in the output.
 */
@SpringBootTest
class PdfStandardsIntegrationTest {

    @Autowired
    PdfGenerationService service;

    private static final String HTML = "<h1>Title</h1><p>Tiếng Việt</p>";

    @ParameterizedTest
    @EnumSource(value = PdfAStandard.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void everyPdfAStandardCarriesTheRightVersionAndConformance(PdfAStandard standard) {
        byte[] pdf = service.render(HTML, standard, null);
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        String body = new String(pdf, StandardCharsets.ISO_8859_1);

        // Header version: 1.4 for part 1, 1.7 for parts 2 and 3.
        assertThat(head).startsWith("%PDF-" + standard.pdfVersion());

        // PDF/A identification (part + conformance level) lives in the uncompressed XMP block.
        // Name layout is PDF_A_<part><level>, e.g. PDF_A_2U -> part '2', level 'U'.
        char part = standard.name().charAt(6);
        char level = standard.name().charAt(standard.name().length() - 1);
        assertThat(Pattern.compile("pdfaid:part\\D{0,3}" + part).matcher(body).find())
                .as("pdfaid:part = %s for %s", part, standard).isTrue();
        assertThat(Pattern.compile("pdfaid:conformance\\W{0,3}" + level).matcher(body).find())
                .as("pdfaid:conformance = %s for %s", level, standard).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PdfAStandard.class, names = {"PDF_A_1A", "PDF_A_2A", "PDF_A_3A"})
    void accessibleLevelsEmitTaggedStructure(PdfAStandard standard) {
        byte[] pdf = service.render(HTML, standard, null);
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        // Tagged / PDF-UA output carries a structure tree and a MarkInfo dictionary.
        assertThat(body).contains("StructTreeRoot");
        assertThat(body).contains("MarkInfo");
    }

    @Test
    void basicLevelIsNotTagged() {
        byte[] pdf = service.render(HTML, PdfAStandard.PDF_A_2B, null);
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        // The visual "B" level must not enable the accessibility tagging.
        assertThat(body).doesNotContain("StructTreeRoot");
    }

    @Test
    void plainPdfHasNoPdfAIdentification() {
        byte[] pdf = service.render(HTML, PdfAStandard.NONE, null);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).doesNotContain("pdfaid:part");
    }
}
