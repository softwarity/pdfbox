package com.softwarity.pdfbox.pdf;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Supplies the sRGB ICC profile embedded as the PDF/A output intent. Uses a bundled
 * {@code /icc/sRGB.icc} if present, otherwise the JDK built-in sRGB profile so generation stays
 * fully offline with zero configuration.
 */
@Component
public class ColorProfiles {

    private static final Logger log = LoggerFactory.getLogger(ColorProfiles.class);

    private final byte[] srgb;

    public ColorProfiles() {
        this.srgb = load();
    }

    private byte[] load() {
        ClassPathResource bundled = new ClassPathResource("icc/sRGB.icc");
        if (bundled.exists()) {
            try (InputStream in = bundled.getInputStream()) {
                return in.readAllBytes();
            } catch (Exception e) {
                log.warn("Could not read bundled sRGB.icc, falling back to JDK profile: {}", e.getMessage());
            }
        }
        return ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
    }

    public byte[] srgb() {
        return srgb;
    }
}
