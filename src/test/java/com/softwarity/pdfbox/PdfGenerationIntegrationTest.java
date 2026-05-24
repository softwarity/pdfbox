package com.softwarity.pdfbox;

import java.nio.charset.StandardCharsets;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PdfGenerationIntegrationTest {

    @Autowired
    PdfGenerationService service;

    @Autowired
    MockMvc mockMvc;

    private static final String HTML = "<h1>Hello</h1><p>Tiếng Việt</p>";

    @Test
    void rendersPlainPdf() {
        byte[] pdf = service.render(HTML, PdfAStandard.NONE, null);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void rendersPdfA1bWithConformanceMetadata() {
        byte[] pdf = service.render(HTML, PdfAStandard.PDF_A_1B, null);
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        // PDF/A-1 mandates header version 1.4.
        assertThat(head).startsWith("%PDF-1.4");
        // PDF/A identification is written as an uncompressed XMP metadata stream.
        assertThat(body).contains("pdfaid:part");
    }

    @Test
    void rendersPdfA2bWithVersion17() {
        byte[] pdf = service.render(HTML, PdfAStandard.PDF_A_2B, null);
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        assertThat(head).startsWith("%PDF-1.7");
    }

    @Test
    void rendersMalformedHtml() {
        byte[] pdf = service.render("<h1>unclosed <b>bold", PdfAStandard.PDF_A_2B, null);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void endpointReturnsPdf() throws Exception {
        mockMvc.perform(post("/api/v1/pdf?standard=PDF_A_1B")
                        .contentType(MediaType.TEXT_HTML)
                        .content(HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }
}
