package org.embulk.util.config;

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

final class TaskDeserializer<T> extends JsonDeserializer<T> {
    public TaskDeserializer(ObjectMapper nestedObjectMapper, ModelManager model, Class<T> iface) {
        this.nestedObjectMapper = nestedObjectMapper;
        this.model = model;
        this.iface = iface;
        this.mappings = getterMappings(iface);
        this.injects = injectEntries(iface);
    }

    protected Multimap<String, FieldEntry> getterMappings(Class<?> iface) {
        ImmutableMultimap.Builder<String, FieldEntry> builder = ImmutableMultimap.builder();
        for (Map.Entry<String, Method> getter : TaskInvocationHandler.fieldGetters(iface).entries()) {
            Method getterMethod = getter.getValue();
            String fieldName = getter.getKey();

            if (getterMethod.getAnnotation(ConfigInject.class) != null) {
                // InjectEntry
                continue;
            }

            Type fieldType = getterMethod.getGenericReturnType();

            final Optional<String> jsonKey = getJsonKey(getterMethod, fieldName);
            if (!jsonKey.isPresent()) {
                // skip this field
                continue;
            }
            final Optional<String> defaultJsonString = getDefaultJsonString(getterMethod);
            builder.put(jsonKey.get(), new FieldEntry(fieldName, fieldType, defaultJsonString));
        }
        return builder.build();
    }

    protected List<InjectEntry> injectEntries(Class<?> iface) {
        ImmutableList.Builder<InjectEntry> builder = ImmutableList.builder();
        for (Map.Entry<String, Method> getter : TaskInvocationHandler.fieldGetters(iface).entries()) {
            Method getterMethod = getter.getValue();
            String fieldName = getter.getKey();
            ConfigInject inject = getterMethod.getAnnotation(ConfigInject.class);
            if (inject != null) {
                // InjectEntry
                builder.add(new InjectEntry(fieldName, getterMethod.getReturnType()));
            }
        }
        return builder.build();
    }

    protected Optional<String> getJsonKey(final Method getterMethod, final String fieldName) {
        return Optional.of(fieldName);
    }

    protected Optional<String> getDefaultJsonString(final Method getterMethod) {
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
        Map<String, Object> objects = new ConcurrentHashMap<String, Object>();
        HashMultimap<String, FieldEntry> unusedMappings = HashMultimap.<String, FieldEntry>create(mappings);

        String key;
        JsonToken current = jp.getCurrentToken();
        if (current == JsonToken.START_OBJECT) {
            current = jp.nextToken();
            key = jp.getCurrentName();
        } else {
            key = jp.nextFieldName();
        }

        for (; key != null; key = jp.nextFieldName()) {
            JsonToken t = jp.nextToken(); // to get to value
            final Collection<FieldEntry> fields = mappings.get(key);
            if (fields.isEmpty()) {
                jp.skipChildren();
            } else {
                final JsonNode children = nestedObjectMapper.readValue(jp, JsonNode.class);
                for (final FieldEntry field : fields) {
                    final Object value = nestedObjectMapper.convertValue(children, new GenericTypeReference(field.getType()));
                    if (value == null) {
                        throw new JsonMappingException("Setting null to a task field is not allowed. Use Optional<T> to represent null.");
                    }
                    objects.put(field.getName(), value);
                    if (!unusedMappings.remove(key, field)) {
                        throw new JsonMappingException(String.format(
                                "FATAL: Expected to be a bug in Embulk. Mapping \"%s: (%s) %s\" might have already been processed, or not in %s.",
                                key,
                                field.getType().toString(),
                                field.getName(),
                                this.iface.toString()));
                    }
                }
            }
        }

        // set default values
        for (Map.Entry<String, FieldEntry> unused : unusedMappings.entries()) {
            FieldEntry field = unused.getValue();
            if (field.getDefaultJsonString().isPresent()) {
                Object value = nestedObjectMapper.readValue(field.getDefaultJsonString().get(), new GenericTypeReference(field.getType()));
                if (value == null) {
                    throw new JsonMappingException("Setting null to a task field is not allowed. Use Optional<T> to represent null.");
                }
                objects.put(field.getName(), value);
            } else {
                // required field
                throw new JsonMappingException("Field '" + unused.getKey() + "' is required but not set", jp.getCurrentLocation());
            }
        }

        // inject
        ImmutableSet.Builder<String> injectedFields = ImmutableSet.builder();
        for (InjectEntry inject : injects) {
            objects.put(inject.getName(), model.getInjectedInstance(inject.getType()));
            injectedFields.add(inject.getName());
        }

        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[] {iface},
                new TaskInvocationHandler(model, iface, objects, injectedFields.build()));
    }

    private static class FieldEntry {
        public FieldEntry(String name, Type type, Optional<String> defaultJsonString) {
            this.name = name;
            this.type = type;
            this.defaultJsonString = defaultJsonString;
        }

        public String getName() {
            return name;
        }

        public Type getType() {
            return type;
        }

        public Optional<String> getDefaultJsonString() {
            return defaultJsonString;
        }

        private final String name;
        private final Type type;
        private final Optional<String> defaultJsonString;
    }

    private static class InjectEntry {
        public InjectEntry(String name, Class<?> type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public Class<?> getType() {
            return type;
        }

        private final String name;
        private Class<?> type;
    }

    private final ObjectMapper nestedObjectMapper;
    private final ModelManager model;
    private final Class<?> iface;
    private final Multimap<String, FieldEntry> mappings;
    private final List<InjectEntry> injects;
}
