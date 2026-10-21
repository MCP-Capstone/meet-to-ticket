package com.mcpcapstone.meettoticket.common;

import org.springframework.stereotype.Component;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonCodec {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public JsonNode read(String value) {
        return mapper.readTree(value);
    }

    public <T> T convert(JsonNode value, Class<T> type) {
        return mapper.treeToValue(value, type);
    }

    public JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }
}
