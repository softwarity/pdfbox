package com.softwarity.pdfbox.pdf;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Discovers font files once at startup and registers them on every render so generation is fully
 * offline and PDF/A compliant (all glyphs embedded). A broad Noto set is bundled on the classpath so
 * the application supports Latin, Vietnamese, Hebrew, Arabic, Thai, Devanagari, Japanese/CJK… out of
 * the box; additional faces can be dropped into the configured filesystem directories. openhtmltopdf
 * performs per-glyph fallback across the registered families listed in the CSS font stack.
 *
 * <p>Only TrueType ({@code .ttf}) fonts are registered: the PDFBox renderer cannot embed OpenType/CFF
 * ({@code .otf}) outlines and throws while building the document, so such files are skipped.
 */
@Service
public class FontService {

    private static final Logger log = LoggerFactory.getLogger(FontService.class);

    /** Bundled fonts live under {@code src/main/resources/fonts} and ship inside the jar. */
    private static final String CLASSPATH_FONTS = "classpath*:fonts/**/*.ttf";

    /**
     * Extra family names registered as aliases for a discovered family, so HTML can reference a font
     * under the name people commonly type. The bundled {@code Noto Sans JP} also answers to the
     * canonical {@code Noto Sans CJK *} names (their Han glyphs are shared) instead of rendering tofu.
     */
    private static final Map<String, List<String>> FAMILY_ALIASES = Map.of(
            "Noto Sans JP", List.of("Noto Sans CJK JP", "Noto Sans CJK SC", "Noto Sans CJK TC",
                    "Noto Sans CJK KR", "Noto Sans CJK HK"));

    private final List<String> directories;
    private final List<FontFace> faces = new ArrayList<>();

    public FontService(@Value("${pdfbox.fonts.directories}") List<String> directories) {
        this.directories = directories;
    }

    /** A registrable face; backed either by a filesystem {@code file} or by in-memory {@code data}. */
    private record FontFace(File file, byte[] data, String family, int weight, FontStyle style) {}

    @PostConstruct
    void discover() {
        indexClasspathFonts();
        indexFilesystemFonts();
        log.info("Registered {} embeddable font face(s) ({} bundled on classpath, plus {})",
                faces.size(), faces.stream().filter(f -> f.file() == null).count(), directories);
    }

    private void indexClasspathFonts() {
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(CLASSPATH_FONTS)) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    String family = readFamily(new ByteArrayInputStream(data));
                    String lower = name.toLowerCase(Locale.ROOT);
                    faces.add(new FontFace(null, data, family, weightFromName(lower), styleFromName(lower)));
                } catch (Exception e) {
                    log.debug("Skipping unreadable bundled font {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan bundled fonts: {}", e.getMessage());
        }
    }

    private void indexFilesystemFonts() {
        for (String dir : directories) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(this::indexFile);
            } catch (IOException e) {
                log.warn("Could not scan font directory {}: {}", root, e.getMessage());
            }
        }
    }

    private void indexFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".otf")) {
            log.debug("Skipping OpenType/CFF font (unsupported by the PDF renderer): {}", path);
            return;
        }
        if (!name.endsWith(".ttf")) {
            return;
        }
        File file = path.toFile();
        try {
            String family = Font.createFont(Font.TRUETYPE_FONT, file).getFamily(Locale.ENGLISH);
            faces.add(new FontFace(file, null, family, weightFromName(name), styleFromName(name)));
        } catch (Exception e) {
            log.debug("Skipping unreadable font {}: {}", path, e.getMessage());
        }
    }

    private static String readFamily(InputStream in) throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT, in).getFamily(Locale.ENGLISH);
    }

    private static int weightFromName(String name) {
        if (name.contains("thin")) return 100;
        if (name.contains("extralight") || name.contains("ultralight")) return 200;
        if (name.contains("semibold") || name.contains("demibold")) return 600;
        if (name.contains("extrabold") || name.contains("ultrabold")) return 800;
        if (name.contains("black") || name.contains("heavy")) return 900;
        if (name.contains("light")) return 300;
        if (name.contains("medium")) return 500;
        if (name.contains("bold")) return 700;
        return 400;
    }

    private static FontStyle styleFromName(String name) {
        if (name.contains("italic") || name.contains("oblique")) {
            return FontStyle.ITALIC;
        }
        return FontStyle.NORMAL;
    }

    /** Registers every discovered face on the builder, embedding only the glyphs actually used. */
    public void registerFonts(PdfRendererBuilder builder) {
        for (FontFace f : faces) {
            register(builder, f, f.family());
            for (String alias : FAMILY_ALIASES.getOrDefault(f.family(), List.of())) {
                register(builder, f, alias);
            }
        }
    }

    private static void register(PdfRendererBuilder builder, FontFace f, String family) {
        if (f.file() != null) {
            builder.useFont(f.file(), family, f.weight(), f.style(), true);
        } else {
            FSSupplier<InputStream> supplier = () -> new ByteArrayInputStream(f.data());
            builder.useFont(supplier, family, f.weight(), f.style(), true);
        }
    }

    public int registeredFontCount() {
        return faces.size();
    }
}
