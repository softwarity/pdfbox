package com.softwarity.pdfbox;

import java.nio.charset.StandardCharsets;

import com.softwarity.pdfbox.pdf.PdfAStandard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer integration tests for the {@code /api/v1} endpoints: the multipart upload, the standards
 * listing, error mapping (bad input -> 400 text/plain), the {@code filename} parameter and the
 * default standard. Spins the full application context with a MockMvc client.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PdfApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private static final String HTML = "<h1>Hello</h1><p>Tiếng Việt</p>";

    @Test
    void uploadEndpointReturnsPdfAndDerivesFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.html", MediaType.TEXT_HTML_VALUE,
                HTML.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/pdf/upload").file(file).param("standard", "PDF_A_2B"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("invoice.pdf")));
    }

    @Test
    void uploadEndpointDerivesOutputNameWhenFilenameOmitted() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.html", MediaType.TEXT_HTML_VALUE,
                HTML.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/pdf/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("statement.pdf")));
    }

    @Test
    void uploadEndpointRejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.html", MediaType.TEXT_HTML_VALUE, new byte[0]);
        mockMvc.perform(multipart("/api/v1/pdf/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void standardsEndpointListsEveryStandard() throws Exception {
        mockMvc.perform(get("/api/v1/standards"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(PdfAStandard.values().length)))
                .andExpect(jsonPath("$", hasItems("NONE", "PDF_A_1B", "PDF_A_2U", "PDF_A_3U")));
    }

    @Test
    void unknownStandardIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pdf?standard=NOT_A_STANDARD")
                        .contentType(MediaType.TEXT_HTML).content(HTML))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void filenameParamDrivesContentDisposition() throws Exception {
        mockMvc.perform(post("/api/v1/pdf?filename=report.pdf")
                        .contentType(MediaType.TEXT_HTML).content(HTML))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("report.pdf")));
    }

    @Test
    void defaultsToPdfA1bWhenStandardOmitted() throws Exception {
        byte[] pdf = mockMvc.perform(post("/api/v1/pdf")
                        .contentType(MediaType.TEXT_HTML).content(HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        // The (test) configured default is PDF_A_1B, i.e. header version 1.4.
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
    }
}
