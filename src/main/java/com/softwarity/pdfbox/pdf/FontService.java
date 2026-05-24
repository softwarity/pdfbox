package com.softwarity.pdfbox.pdf;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Discovers font files once at startup and registers them on every render so generation is fully
 * offline and PDF/A compliant (all glyphs embedded). openhtmltopdf performs per-glyph fallback
 * across the registered families, so a single document can mix Latin, Vietnamese, Hebrew, Japanese…
 */
@Service
public class FontService {

    private static final Logger log = LoggerFactory.getLogger(FontService.class);

    private final List<String> directories;
    private final List<FontFace> faces = new ArrayList<>();

    public FontService(@Value("${pdfbox.fonts.directories}") List<String> directories) {
        this.directories = directories;
    }

    private record FontFace(File file, String family, int weight, FontStyle style) {}

    @PostConstruct
    void discover() {
        for (String dir : directories) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(this::index);
            } catch (IOException e) {
                log.warn("Could not scan font directory {}: {}", root, e.getMessage());
            }
        }
        log.info("Registered {} embeddable font face(s) from {}", faces.size(), directories);
    }

    private void index(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ttf") && !name.endsWith(".otf")) {
            return;
        }
        File file = path.toFile();
        try {
            Font awt = Font.createFont(Font.TRUETYPE_FONT, file);
            String family = awt.getFamily(Locale.ENGLISH);
            faces.add(new FontFace(file, family, weightFromName(name), styleFromName(name)));
        } catch (Exception e) {
            log.debug("Skipping unreadable font {}: {}", path, e.getMessage());
        }
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
            builder.useFont(f.file(), f.family(), f.weight(), f.style(), true);
        }
    }

    public int registeredFontCount() {
        return faces.size();
    }
}
