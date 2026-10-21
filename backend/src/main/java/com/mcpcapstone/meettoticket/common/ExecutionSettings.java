package com.mcpcapstone.meettoticket.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ExecutionSettings {
    private final String mode;

    public ExecutionSettings(@Value("${agent.mode:live}") String mode) {
        this.mode = mode.toUpperCase(Locale.ROOT);
    }

    public String mode() {
        return mode;
    }
}
