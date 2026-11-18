package com.softwarity.pdfbox;

import com.softwarity.pdfbox.pdf.EngineInfo;
import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code X-Pdfbox-Engine} on every PDF response, and the same versions in the PDF {@code Producer}. */
@SpringBootTest
@AutoConfigureMockMvc
class EngineInfoTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PdfGenerationService service;

    @Test
    void headerNamesTheVersionsActuallyOnTheClasspath() {
        assertThat(EngineInfo.header())
                .matches("openhtmltopdf/\\d+\\.\\d+\\.\\d+ pdfbox/\\d+\\.\\d+\\.\\d+ batik/\\d+\\.\\d+(\\.\\d+)?")
                .contains("pdfbox/" + org.apache.pdfbox.util.Version.getVersion());
    }

    @Test
    void everyPdfEndpointSendsTheHeader() throws Exception {
        mockMvc.perform(post("/api/v1/pdf").contentType(MediaType.TEXT_HTML).content("<p>x</p>"))
                .andExpect(status().isOk())
                .andExpect(header().string(EngineInfo.HEADER, EngineInfo.header()));
        mockMvc.perform(multipart("/api/v1/pdf/upload")
                        .file(new MockMultipartFile("file", "a.html", "text/html", "<p>x</p>".getBytes())))
                .andExpect(status().isOk())
                .andExpect(header().string(EngineInfo.HEADER, EngineInfo.header()));
        mockMvc.perform(get("/api/v1/pdf-info?standard=PDF_A_2B"))
                .andExpect(status().isOk())
                .andExpect(header().string(EngineInfo.HEADER, EngineInfo.header()));
    }

    @Test
    void errorsDoNotClaimAnEngine() throws Exception {
        mockMvc.perform(post("/api/v1/pdf").contentType(MediaType.TEXT_HTML).content("<img src='nope.png'>"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().doesNotExist(EngineInfo.HEADER));
    }

    @Test
    void producerCarriesTheSameVersionsInEveryStandard() throws Exception {
        for (PdfAStandard standard : PdfAStandard.values()) {
            byte[] pdf = service.render("<p>x</p>", standard);
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                assertThat(doc.getDocumentInformation().getProducer()).as("%s", standard)
                        .isEqualTo(EngineInfo.producer());
            }
            if (standard.isPdfA()) {
                // PDF/A requires the XMP pdf:Producer to match the Info dictionary.
                assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).as("%s", standard)
                        .contains(EngineInfo.producer());
                assertThat(PdfTestSupport.veraPdfFailures(pdf, standard)).as("%s", standard).isEmpty();
            }
        }
    }
}
