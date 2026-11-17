package com.softwarity.pdfbox;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.softwarity.pdfbox.PdfTestSupport.countPixels;
import static com.softwarity.pdfbox.PdfTestSupport.embeddedFonts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SVG written inline in the HTML is drawn by Batik — as vector art, with scripts never executed and
 * text drawn with the bundled fonts.
 */
@SpringBootTest
class SvgRenderingTest {

    @Autowired
    PdfGenerationService service;

    @Test
    void inlineSvgIsDrawn() throws Exception {
        byte[] pdf = service.render("<p>Map</p><svg width=\"200\" height=\"100\" viewBox=\"0 0 20 10\">"
                + "<rect width=\"20\" height=\"10\" fill=\"#ff0000\"/></svg>", PdfAStandard.PDF_A_2B);

        // 200x100 CSS px = 150x75 pt at 72 dpi; the viewBox scales the 20x10 rect to fill it all.
        assertThat(countPixels(pdf, PdfTestSupport::red)).isGreaterThan(10_000);
    }

    @Test
    void svgScriptAndEventHandlersNeverRun() throws Exception {
        try (CountingHttpServer server = new CountingHttpServer()) {
            String svg = "<svg width=\"200\" height=\"100\" onload=\"document.getElementById('r')"
                    + ".setAttribute('fill','#00ff00')\">"
                    + "<rect id=\"r\" width=\"200\" height=\"100\" fill=\"#ff0000\"/>"
                    + "<script type=\"text/ecmascript\">document.getElementById('r').setAttribute('fill',"
                    + "'#00ff00'); new java.net.URL('" + server.url("/from-script") + "').openStream();"
                    + "</script></svg>";

            byte[] pdf = service.render(svg, PdfAStandard.NONE);

            assertThat(countPixels(pdf, PdfTestSupport::red)).isGreaterThan(10_000);
            assertThat(countPixels(pdf, PdfTestSupport::green)).isZero();
            assertThat(server.requestCount()).isZero();
        }
    }

    @Test
    void svgTextIsDrawnWithBundledFontsNotHostFonts() throws Exception {
        // A family the host cannot have: without the bundled fallback stack Batik would use a host
        // font (AWT "Dialog"/"SansSerif"). With it, the text must come out exactly as when the bundled
        // face (Liberation Sans in the test profile, Noto in production) is named explicitly.
        byte[] unknown = service.render(svgText("NoSuchFontAnywhere"), PdfAStandard.PDF_A_2U);
        byte[] bundled = service.render(svgText("Liberation Sans"), PdfAStandard.PDF_A_2U);

        assertThat(countPixels(unknown, PdfTestSupport::dark)).isGreaterThan(200);
        assertThat(sameRaster(unknown, bundled)).isTrue();
        // Drawn as vector outlines of the bundled face: no font dictionary to embed, PDF/A-safe.
        assertThat(embeddedFonts(unknown)).noneMatch(f -> f.contains("Dialog") || f.contains("Arial"));
    }

    private static String svgText(String family) {
        return "<svg width=\"400\" height=\"100\"><text x=\"10\" y=\"60\" font-size=\"48\" font-family=\""
                + family + "\" fill=\"#000000\">Hamburgefonstiv</text></svg>";
    }

    private static boolean sameRaster(byte[] a, byte[] b) throws Exception {
        var ia = PdfTestSupport.renderPage(a, 0);
        var ib = PdfTestSupport.renderPage(b, 0);
        if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight()) {
            return false;
        }
        for (int y = 0; y < ia.getHeight(); y++) {
            for (int x = 0; x < ia.getWidth(); x++) {
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
