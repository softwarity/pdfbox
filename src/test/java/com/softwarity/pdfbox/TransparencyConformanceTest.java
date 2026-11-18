package com.softwarity.pdfbox;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.resource.Attachment;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transparency against every PDF/A standard, checked with veraPDF: every case must conform.
 *
 * <p>PDF/A-2 and PDF/A-3 allow transparency and keep it. PDF/A-1 forbids it (ISO 19005-1 §6.4): it is
 * flattened over white — a 50 % red becomes a solid pink — with a post-pass safety net for anything
 * the flattening could not fold.
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
        SVG_OPACITY("<svg width='100' height='50'><rect width='100' height='50' fill='#f00' opacity='0.5'/></svg>"),
        SVG_FILL_OPACITY("<svg width='100' height='50'><rect width='100' height='50' fill='#f00' "
                + "fill-opacity='0.5'/></svg>"),
        SVG_GROUP_OPACITY("<svg width='100' height='50'><g opacity='0.5' fill='red'><rect width='50' height='50'/>"
                + "<circle cx='75' cy='25' r='20' stroke='blue' stroke-width='4'/></g></svg>"),
        SVG_STYLE_AND_RGBA("<svg width='100' height='50'><rect width='50' height='50' style='fill:red;fill-opacity:.5'/>"
                + "<rect x='50' width='50' height='50' fill='rgba(0,0,255,0.5)'/></svg>"),
        SVG_STOP_OPACITY("<svg width='100' height='50'><defs><linearGradient id='g'>"
                + "<stop offset='0' stop-color='#f00' stop-opacity='0'/><stop offset='1' stop-color='#00f'/>"
                + "</linearGradient></defs><rect width='100' height='50' fill='url(#g)'/></svg>"),
        SVG_STYLE_RULE("<svg width='100' height='50'><style>.t{fill:red;opacity:.4}</style>"
                + "<rect class='t' width='100' height='50'/></svg>"),
        SVG_IMAGE_WITH_ALPHA("<svg width='100' height='50'><image width='100' height='50' "
                + "href='data:image/png;base64," + png(true) + "'/></svg>"),
        PNG_ALPHA("<img alt='alpha' src='data:image/png;base64," + png(true) + "'/>");

        final String markup;

        Case(String markup) {
            this.markup = markup;
        }
    }

    static Stream<Arguments> matrix() {
        return Arrays.stream(PdfAStandard.values())
                .filter(PdfAStandard::isPdfA)
                .flatMap(s -> Arrays.stream(Case.values()).map(c -> Arguments.of(s, c)));
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("matrix")
    void everyCaseConforms(PdfAStandard standard, Case c) throws Exception {
        byte[] pdf = service.render(document(c.markup), standard);

        assertThat(PdfTestSupport.veraPdfFailures(pdf, standard)).as("%s must conform to %s", c, standard).isEmpty();
    }

    @Test
    void pdfA1FlattensHalfTransparentRedToSolidPink() throws Exception {
        for (Case c : List.of(Case.SVG_OPACITY, Case.SVG_FILL_OPACITY, Case.PNG_ALPHA, Case.SVG_IMAGE_WITH_ALPHA)) {
            byte[] pdf = service.render(document(c.markup.replace("width='100' height='50'",
                    "width='300' height='150'")).replace("<img ", "<img width='300' height='150' "), PdfAStandard.PDF_A_1B);
            String raw = new String(pdf, StandardCharsets.ISO_8859_1);

            assertThat(PdfTestSupport.countPixels(pdf, TransparencyConformanceTest::pink)).as("pink for %s", c)
                    .isGreaterThan(5_000);
            assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::red)).as("no solid red for %s", c).isZero();
            assertThat(raw).as("no soft mask for %s", c).doesNotContain("/SMask <<").doesNotContain("/SMask ")
                    .doesNotContain("/ca 0.");
        }
    }

    @Test
    void attachedAndRemoteImagesAreFlattenedTooButOnlyInPdfA1() throws Exception {
        Attachments attachments = Attachments.of(List.of(Attachment.ofBytes("a.png", "image/png",
                Base64.getDecoder().decode(png(true)))));
        String html = "<img src='a.png' width='300' height='150' alt='a'/>";

        byte[] a1 = service.render(html, PdfAStandard.PDF_A_1B, attachments, MissingResourcePolicy.FAIL).pdf();
        byte[] a2 = service.render(html, PdfAStandard.PDF_A_2B, attachments, MissingResourcePolicy.FAIL).pdf();

        assertThat(new String(a1, StandardCharsets.ISO_8859_1)).doesNotContain("/SMask");
        assertThat(new String(a2, StandardCharsets.ISO_8859_1)).as("PDF/A-2 keeps the alpha channel")
                .contains("/SMask");
        assertThat(PdfTestSupport.countPixels(a1, TransparencyConformanceTest::pink)).isGreaterThan(20_000);
    }

    /** About 50 % red over white: (255, ~128, ~128). */
    static boolean pink(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r > 235 && g > 100 && g < 160 && b > 100 && b < 160;
    }

    private static String document(String markup) {
        return "<html lang='en'><head><title>Transparency</title></head><body><h1>Transparency</h1>"
                + markup + "</body></html>";
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
