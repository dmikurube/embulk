package org.embulk.util.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.lang.reflect.Proxy;

final class TaskSerializer extends JsonSerializer<Task> {
    public TaskSerializer(final ObjectMapper nestedObjectMapper) {
        this.nestedObjectMapper = nestedObjectMapper;
    }

    @Override
    public void serialize(final Task value, final JsonGenerator jgen, final SerializerProvider provider) throws IOException {
        if (value instanceof Proxy) {
            Object handler = Proxy.getInvocationHandler(value);
            if (handler instanceof TaskInvocationHandler) {
                TaskInvocationHandler h = (TaskInvocationHandler) handler;
                Map<String, Object> objects = h.getObjects();
                jgen.writeStartObject();
                for (final Map.Entry<String, Object> pair : objects.entrySet()) {
                    if (h.getInjectedFields().contains(pair.getKey())) {
                        continue;
                    }
                    jgen.writeFieldName(pair.getKey());
                    nestedObjectMapper.writeValue(jgen, pair.getValue());
                }
                jgen.writeEndObject();
                return;
            }
        }
        // TODO exception class & message
        throw new UnsupportedOperationException("Serializing Task is not supported");
    }

    private final ObjectMapper nestedObjectMapper;
}
