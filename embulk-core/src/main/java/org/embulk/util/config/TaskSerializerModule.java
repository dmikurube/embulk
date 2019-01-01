package org.embulk.util.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

final class TaskSerializerModule extends SimpleModule {
    public TaskSerializerModule(final ObjectMapper nestedObjectMapper) {
        super();
        addSerializer(Task.class, new TaskSerializer(nestedObjectMapper));
    }
}
