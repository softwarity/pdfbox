package com.softwarity.pdfbox.web;

import com.softwarity.pdfbox.resource.MissingResourcePolicy;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** {@code ?onMissingResource=fail|report}, case-insensitive. A bad value becomes a clear 400. */
@Component
class MissingResourcePolicyConverter implements Converter<String, MissingResourcePolicy> {

    @Override
    public MissingResourcePolicy convert(String source) {
        return MissingResourcePolicy.parse(source);
    }
}
