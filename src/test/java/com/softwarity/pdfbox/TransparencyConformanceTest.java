package com.softwarity.pdfbox;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transparency against every PDF/A standard, checked with veraPDF.
 *
 * <p>PDF/A-2 and PDF/A-3 allow transparency: every case must conform. PDF/A-1 forbids it (ISO
 * 19005-1 §6.4): opaque content must conform, and the transparent cases below are the
 * <b>documented limitation</b> — pdfbox currently renders them faithfully and the result is NOT
 * PDF/A-1 conformant. These assertions pin that behaviour (and the exact clauses) so that whichever
 * policy is chosen for them (explicit failure or flattening) shows up here as a deliberate change.
 * CSS {@code rgba()}/{@code opacity} are not in that list: the engine already neutralises them for
 * PDF/A-1.
 */
@SpringBootTest
class TransparencyConformanceTest {

    @Autowired
    PdfGenerationService service;

    enum Case {
        SVG_OPAQUE_GRADIENT("<svg width='100' height='50'><defs><linearGradient id='g'>"
                + "<stop offset='0' stop-color='#f00'/><stop offset='1' stop-color='#00f'/></linearGradient>"
                + "</defs><rect width='100' height='50' fill='url(#g)'/></svg>"),
        PNG_OPAQUE("<img alt='opaque' src='data:image/png;base64," + png(false) + "'/>"),
        CSS_RGBA("<p style='background:rgba(255,0,0,0.5)'>rgba</p>"),
        CSS_OPACITY("<p style='opacity:0.5'>opacity</p>"),
        // Transparent cases, non-conformant in PDF/A-1 (clause veraPDF reports):
        SVG_OPACITY("<svg width='100' height='50'><rect width='100' height='50' fill='#f00' opacity='0.5'/></svg>",
                "6.4-5", "6.4-6"),
        SVG_FILL_OPACITY("<svg width='100' height='50'><rect width='100' height='50' fill='#f00' "
                + "fill-opacity='0.5'/></svg>", "6.4-5", "6.4-6"),
        SVG_STOP_OPACITY("<svg width='100' height='50'><defs><linearGradient id='g'>"
                + "<stop offset='0' stop-color='#f00' stop-opacity='0'/><stop offset='1' stop-color='#00f'/>"
                + "</linearGradient></defs><rect width='100' height='50' fill='url(#g)'/></svg>", "6.4-1"),
        PNG_ALPHA("<img alt='alpha' src='data:image/png;base64," + png(true) + "'/>", "6.4-2");

        final String markup;
        final List<String> pdfA1Clauses;

        Case(String markup, String... pdfA1Clauses) {
            this.markup = markup;
            this.pdfA1Clauses = Arrays.asList(pdfA1Clauses);
        }

        boolean transparent() {
            return !pdfA1Clauses.isEmpty();
        }
    }

    static Stream<Arguments> matrix() {
        return Arrays.stream(PdfAStandard.values())
                .filter(PdfAStandard::isPdfA)
                .flatMap(s -> Arrays.stream(Case.values()).map(c -> Arguments.of(s, c)));
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("matrix")
    void transparencyConformance(PdfAStandard standard, Case c) throws Exception {
        byte[] pdf = service.render("<html lang='en'><head><title>Transparency</title></head><body>"
                + "<h1>Transparency</h1>" + c.markup + "</body></html>", standard);
        List<String> failures = PdfTestSupport.veraPdfFailures(pdf, standard);

        if (standard.part() == 1 && c.transparent()) {
            assertThat(failures)
                    .as("documented PDF/A-1 limitation for %s", c)
                    .isNotEmpty()
                    .allMatch(f -> c.pdfA1Clauses.stream().anyMatch(f::startsWith)
                            || f.startsWith("6.4-"));
        } else {
            assertThat(failures).as("%s must conform to %s", c, standard).isEmpty();
        }
    }

    private static String png(boolean alpha) {
        try {
            BufferedImage img = new BufferedImage(40, 20,
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(alpha ? new Color(255, 0, 0, 128) : Color.RED);
            g.fillRect(0, 0, 40, 20);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
