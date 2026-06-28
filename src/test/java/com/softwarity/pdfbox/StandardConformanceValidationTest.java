package com.softwarity.pdfbox;

import java.io.ByteArrayInputStream;
import java.util.stream.Collectors;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.pdf.StandardInfoService;

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
        byte[] pdf = pdfService.render(standardInfoService.toHtml(standard), standard, null);

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

    /** Negative control: proves the validator actually discriminates (a plain PDF fails PDF/A-1B). */
    @Test
    void plainPdfIsNotPdfA1bCompliant() throws Exception {
        byte[] pdf = pdfService.render(
                standardInfoService.toHtml(PdfAStandard.NONE), PdfAStandard.NONE, null);

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
