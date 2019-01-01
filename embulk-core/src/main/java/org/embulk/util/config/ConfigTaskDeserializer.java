package org.embulk.util.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.embulk.config.ModelManager;

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

final class ConfigTaskDeserializer<T> extends TaskDeserializer<T> {
    public ConfigTaskDeserializer(final ObjectMapper nestedObjectMapper, final ModelManager model, final Class<T> iface) {
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
