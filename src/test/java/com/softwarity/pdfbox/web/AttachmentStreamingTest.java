package com.softwarity.pdfbox.web;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attachments are never copied as a whole: validation reads only their first bytes (or streams a
 * stylesheet), and the renderer opens each one only when it needs it. Proved with parts whose
 * {@code getBytes()} — the whole-copy API — fails.
 */
@SpringBootTest
class AttachmentStreamingTest {

    @Autowired
    AttachmentValidator validator;

    @Autowired
    PdfGenerationService service;

    /** A part that can only be streamed. */
    static final class StreamOnly extends MockMultipartFile {
        StreamOnly(String name, String filename, String type, byte[] content) {
            super(name, filename, type, content);
        }

        @Override
        public byte[] getBytes() {
            throw new AssertionError("getBytes() copies the whole upload and must not be used");
        }
    }

    @Test
    void attachmentsAreStreamedNotCopied() throws Exception {
        var html = new StreamOnly("file", "a.html", "text/html", "<p>x</p>".getBytes());
        var css = new StreamOnly("resources", "page.css", "text/css",
                ".box{background:#ff0000;width:300px;height:100px}".getBytes());
        var png = new StreamOnly("resources", "red.png", "image/png", redPng());

        Attachments attachments = validator.validate(html, List.of(css, png));
        var result = service.render("<link rel=\"stylesheet\" href=\"page.css\"/><div class=\"box\"></div>"
                + "<img src=\"red.png\" width=\"200\" height=\"100\" alt=\"r\"/>", PdfAStandard.PDF_A_2B,
                attachments, MissingResourcePolicy.FAIL);

        try (var doc = Loader.loadPDF(result.pdf())) {
            var img = new PDFRenderer(doc).renderImage(0, 1f);
            long red = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60) {
                        red++;
                    }
                }
            }
            assertThat(red).as("CSS box + PNG drawn from streamed attachments").isGreaterThan(20_000);
        }
    }

    private static byte[] redPng() throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
