package org.embulk.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class TaskSerDe {

    public static class ConfigTaskDeserializer<T> extends TaskDeserializer<T> {
        public ConfigTaskDeserializer(ObjectMapper nestedObjectMapper, ModelManager model, Class<T> iface) {
            super(nestedObjectMapper, model, iface);
        }

        @Override
        protected Optional<String> getJsonKey(final Method getterMethod, final String fieldName) {
            final Config a = getterMethod.getAnnotation(Config.class);
            if (a != null) {
                return Optional.of(a.value());
            } else {
                return Optional.empty();  // skip this field
            }
        }

        @Override
        protected Optional<String> getDefaultJsonString(final Method getterMethod) {
            final ConfigDefault a = getterMethod.getAnnotation(ConfigDefault.class);
            if (a != null && !a.value().isEmpty()) {
                return Optional.of(a.value());
            }
            return super.getDefaultJsonString(getterMethod);
        }
    }

    public static class TaskDeserializerModule extends Module {  // can't use just SimpleModule, due to generic types
        protected final ObjectMapper nestedObjectMapper;
        protected final ModelManager model;

        public TaskDeserializerModule(ObjectMapper nestedObjectMapper, ModelManager model) {
            this.nestedObjectMapper = nestedObjectMapper;
            this.model = model;
        }

        @Override
        public String getModuleName() {
            return "embulk.config.TaskSerDe";
        }

        @Override
        public Version version() {
            return Version.unknownVersion();
        }

        @Override
        public void setupModule(SetupContext context) {
            context.addDeserializers(new Deserializers.Base() {
                    @Override
                    public JsonDeserializer<?> findBeanDeserializer(
                            JavaType type,
                            DeserializationConfig config,
                            BeanDescription beanDesc) throws JsonMappingException {
                        Class<?> raw = type.getRawClass();
                        if (Task.class.isAssignableFrom(raw)) {
                            return newTaskDeserializer(raw);
                        }
                        return super.findBeanDeserializer(type, config, beanDesc);
                    }
                });
        }

        @SuppressWarnings("unchecked")
        protected JsonDeserializer<?> newTaskDeserializer(Class<?> raw) {
            return new TaskDeserializer(nestedObjectMapper, model, raw);
        }
    }

    public static class ConfigTaskDeserializerModule extends TaskDeserializerModule {
        public ConfigTaskDeserializerModule(ObjectMapper nestedObjectMapper, ModelManager model) {
            super(nestedObjectMapper, model);
        }

        @Override
        public String getModuleName() {
            return "embulk.config.ConfigTaskSerDe";
        }

        @Override
        @SuppressWarnings("unchecked")
        protected JsonDeserializer<?> newTaskDeserializer(Class<?> raw) {
            return new ConfigTaskDeserializer(nestedObjectMapper, model, raw);
        }
    }
}
