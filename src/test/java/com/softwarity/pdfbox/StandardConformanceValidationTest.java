package com.softwarity.pdfbox;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.pdf.StandardInfoService;
import com.softwarity.pdfbox.resource.Attachment;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the self-describing {@code /pdf-info} PDF of every PDF/A standard against that same
 * standard, using veraPDF (the ISO reference validator). This is the real conformance gate: each
 * variant must actually pass the rules it claims to document.
 */
@SpringBootTest
class StandardConformanceValidationTest {

    @Autowired
    PdfGenerationService pdfService;

    @Autowired
    StandardInfoService standardInfoService;

    @BeforeAll
    static void initVeraPdf() {
        VeraGreenfieldFoundryProvider.initialise();
    }

    @ParameterizedTest(name = "{0} info PDF is valid {0}")
    @EnumSource(value = PdfAStandard.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void infoPdfConformsToItsOwnStandard(PdfAStandard standard) throws Exception {
        byte[] pdf = pdfService.render(standardInfoService.toHtml(standard), standard);

        PDFAFlavour flavour = PDFAFlavour.fromString(standard.part() + standard.level().toLowerCase());
        try (PDFAParser parser = Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            ValidationResult result = validator.validate(parser);

            assertThat(result.isCompliant())
                    .withFailMessage(() -> "Not " + standard + "-compliant. Failed checks:\n  "
                            + failures(result))
                    .isTrue();
        }
    }

    /**
     * The reference document for the flight-documentation use case: multi-script text (Latin,
     * Vietnamese, Hebrew, Arabic, Thai, Japanese), an inline SVG with text, an attached SVG, an
     * attached PNG and an attached WebP, with an attached stylesheet — valid in every standard.
     * Opaque content only: transparency in PDF/A-1 is the documented limitation covered by
     * {@link TransparencyConformanceTest}.
     */
    @ParameterizedTest(name = "reference document is valid {0}")
    @EnumSource(value = PdfAStandard.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void referenceDocumentConformsToEveryStandard(PdfAStandard standard) throws Exception {
        String html = "<!DOCTYPE html><html lang=\"en\"><head><title>Flight briefing</title>"
                + "<link rel=\"stylesheet\" href=\"briefing.css\"/></head><body>"
                + "<h1>Flight briefing LFPG – RJTT</h1>"
                + "<p>Tiếng Việt · <span lang=\"he\">עברית</span> · <span lang=\"ar\">العربية</span> · "
                + "<span lang=\"th\">ภาษาไทย</span> · <span lang=\"ja\">日本語の天気図</span></p>"
                + "<h2>Inline chart</h2>"
                + "<svg width=\"300\" height=\"120\" viewBox=\"0 0 300 120\" role=\"img\" aria-label=\"Front\">"
                + "<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#1e88e5\"/>"
                + "<stop offset=\"1\" stop-color=\"#e53935\"/></linearGradient></defs>"
                + "<rect width=\"300\" height=\"120\" fill=\"url(#g)\"/>"
                + "<path d=\"M10 100 Q 150 10 290 100\" stroke=\"#000000\" stroke-width=\"3\" fill=\"none\"/>"
                + "<text x=\"20\" y=\"40\" font-size=\"20\" fill=\"#ffffff\">FL350 CB</text></svg>"
                + "<h2>Attached charts</h2>"
                + "<img src=\"sigwx.svg\" width=\"300\" height=\"120\" alt=\"Significant weather chart\"/>"
                + "<img src=\"wind.png\" width=\"300\" height=\"60\" alt=\"Wind chart\"/>"
                + "<img src=\"logo.webp\" width=\"80\" height=\"40\" alt=\"Operator logo\"/>"
                + "</body></html>";
        Attachments attachments = Attachments.of(List.of(
                new Attachment("briefing.css", "text/css",
                        ("body{font-family:'Noto Sans','Noto Sans Hebrew','Noto Sans Arabic','Noto Sans Thai',"
                                + "'Noto Sans JP',sans-serif} h2{color:#0b3a8c}").getBytes(StandardCharsets.UTF_8)),
                new Attachment("sigwx.svg", "image/svg+xml", ("<svg xmlns=\"http://www.w3.org/2000/svg\" "
                        + "width=\"300\" height=\"120\"><rect width=\"300\" height=\"120\" fill=\"#ffffff\" "
                        + "stroke=\"#000000\"/><circle cx=\"150\" cy=\"60\" r=\"40\" fill=\"#43a047\"/>"
                        + "<text x=\"120\" y=\"65\" font-size=\"16\">ISOL CB</text></svg>")
                        .getBytes(StandardCharsets.UTF_8)),
                new Attachment("wind.png", "image/png", PdfTestSupport.png(300, 60, 0x2E7D32)),
                new Attachment("logo.webp", "image/webp",
                        Files.readAllBytes(Path.of("src/test/resources/attachments/blue.webp")))));

        var result = pdfService.render(html, standard, attachments, MissingResourcePolicy.FAIL);

        assertThat(result.unresolved()).isEmpty();
        assertThat(PdfTestSupport.veraPdfFailures(result.pdf(), standard))
                .as("reference document must be valid %s", standard)
                .isEmpty();
        assertThat(PdfTestSupport.embeddedFonts(result.pdf()))
                .anyMatch(f -> f.contains("NotoSansHebrew"))
                .anyMatch(f -> f.contains("NotoSansArabic"))
                .anyMatch(f -> f.contains("NotoSansThai"))
                .anyMatch(f -> f.contains("NotoSansJP"));
    }

    /** Negative control: proves the validator actually discriminates (a plain PDF fails PDF/A-1B). */
    @Test
    void plainPdfIsNotPdfA1bCompliant() throws Exception {
        byte[] pdf = pdfService.render(
                standardInfoService.toHtml(PdfAStandard.NONE), PdfAStandard.NONE);

        PDFAFlavour flavour = PDFAFlavour.fromString("1b");
        try (PDFAParser parser = Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            assertThat(validator.validate(parser).isCompliant())
                    .as("a plain PDF must NOT pass PDF/A-1B validation")
                    .isFalse();
        }
    }

    private static String failures(ValidationResult result) {
        return result.getTestAssertions().stream()
                .filter(a -> a.getStatus() == TestAssertion.Status.FAILED)
                .map(TestAssertion::getMessage)
                .distinct()
                .limit(15)
                .collect(Collectors.joining("\n  "));
    }
}
