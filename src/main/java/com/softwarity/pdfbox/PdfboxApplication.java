package com.softwarity.pdfbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PdfboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdfboxApplication.class, args);
    }
}
