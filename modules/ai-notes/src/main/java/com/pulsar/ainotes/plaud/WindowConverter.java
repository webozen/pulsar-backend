package com.pulsar.ainotes.plaud;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class WindowConverter implements Converter<String, Window> {
    @Override
    public Window convert(String source) {
        return Window.fromValue(source);
    }
}
