package com.softwarity.pdfbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One render slot, 200 ms queue timeout: while a render is busy (fetching a slow resource from an
 * allowed host), a second render is answered 503 + Retry-After, routes that do not render still
 * answer, and the slot is released afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pdfbox.render.max-concurrent=1",
        "pdfbox.render.queue-timeout=200ms",
        "pdfbox.fetch.timeout=5s"})
class RenderLimitTest {

    static final CountingHttpServer SERVER;

    static {
        try {
            SERVER = new CountingHttpServer().slow("/slow.png", "image/png", PdfTestSupport.redPng(), 1_500);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void policy(DynamicPropertyRegistry registry) {
        registry.add("pdfbox.fetch.allowed-hosts", SERVER::authority);
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpRequest render(String html) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/pdf"))
                .header("Content-Type", "text/html")
                .POST(HttpRequest.BodyPublishers.ofString(html))
                .build();
    }

    @Test
    void secondConcurrentRenderGets503UntilTheSlotIsFree() throws Exception {
        CompletableFuture<HttpResponse<String>> busy = client.sendAsync(
                render("<img src=\"" + SERVER.url("/slow.png") + "\" alt=\"s\"/>"),
                HttpResponse.BodyHandlers.ofString());
        while (SERVER.requestCount("/slow.png") == 0) {
            Thread.sleep(10); // the first render now holds the only slot
        }

        HttpResponse<String> refused = client.send(render("<p>x</p>"), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(refused.body()).contains("PDFBOX_RENDER_MAX_CONCURRENT");
        assertThat(health.statusCode()).isEqualTo(200);

        assertThat(busy.get().statusCode()).isEqualTo(200);
        assertThat(client.send(render("<p>x</p>"), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }
}
