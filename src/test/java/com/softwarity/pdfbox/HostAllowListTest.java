package com.softwarity.pdfbox;

import java.net.URI;
import java.util.List;

import com.softwarity.pdfbox.resource.HostAllowList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostAllowListTest {

    private final HostAllowList list = new HostAllowList(List.of("geoserver.example.svc", "Maps.Example.org:8443", " "));

    @Test
    void hostWithoutPortAdmitsOnlyTheSchemeDefaultPort() {
        assertThat(list.permits(URI.create("http://geoserver.example.svc/wms"))).isTrue();
        assertThat(list.permits(URI.create("https://geoserver.example.svc/wms"))).isTrue();
        assertThat(list.permits(URI.create("http://geoserver.example.svc:80/wms"))).isTrue();
        assertThat(list.permits(URI.create("http://geoserver.example.svc:8080/wms"))).isFalse();
    }

    @Test
    void hostWithPortAdmitsThatPortOnlyCaseInsensitively() {
        assertThat(list.permits(URI.create("https://maps.example.org:8443/t.png"))).isTrue();
        assertThat(list.permits(URI.create("https://maps.example.org/t.png"))).isFalse();
    }

    @Test
    void exactHostsOnlyAndHttpOnly() {
        assertThat(list.permits(URI.create("http://evil.geoserver.example.svc/x"))).isFalse();
        assertThat(list.permits(URI.create("http://geoserver.example.svc.evil.com/x"))).isFalse();
        assertThat(list.permits(URI.create("ftp://geoserver.example.svc/x"))).isFalse();
        assertThat(list.permits(URI.create("file://geoserver.example.svc/etc/passwd"))).isFalse();
    }

    @Test
    void emptyListPermitsNothing() {
        HostAllowList empty = new HostAllowList(List.of());
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.permits(URI.create("http://localhost/"))).isFalse();
    }

    @Test
    void invalidEntriesFailAtStartup() {
        for (String bad : List.of("*.example.org", "http://example.org", "example.org/path", "host:99999999")) {
            assertThatThrownBy(() -> new HostAllowList(List.of(bad)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PDFBOX_FETCH_ALLOWED_HOSTS");
        }
    }
}
