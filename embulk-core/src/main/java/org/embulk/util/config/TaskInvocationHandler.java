package org.embulk.util.config;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class TaskInvocationHandler implements InvocationHandler {
    public TaskInvocationHandler(
            // final ModelManager model,
            final Class<?> iface,
            final Map<String, Object> objects,
            final Set<String> injectedFields) {
        // this.model = model;
        this.iface = iface;
        this.objects = objects;
        this.injectedFields = injectedFields;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
        final String methodName = method.getName();

        switch (methodName) {
            /*
            case "validate":
                checkArgumentLength(method, 0, methodName);
                this.model.validate(proxy);
                return proxy;
            */

            /*
            case "dump":
                checkArgumentLength(method, 0, methodName);
                return invokeDump();
            */

            case "toString":
                checkArgumentLength(method, 0, methodName);
                return invokeToString();

            case "hashCode":
                checkArgumentLength(method, 0, methodName);
                return invokeHashCode();

            case "equals":
                checkArgumentLength(method, 1, methodName);
                if (args[0] instanceof Proxy) {
                    final Object otherHandler = Proxy.getInvocationHandler(args[0]);
                    return invokeEquals(otherHandler);
                }
                return false;

            default: {
                String fieldName;
                fieldName = getterFieldNameOrNull(methodName);
                if (fieldName != null) {
                    if (method.isDefault() && !this.objects.containsKey(fieldName)) {
                        // If and only if the method has default implementation, and @Config is not annotated there,
                        // it is tried to call the default implementation directly without proxying.
                        //
                        // methodWithDefaultImpl.invoke(proxy) without this hack would cause infinite recursive calls.
                        //
                        // See hints:
                        // https://rmannibucau.wordpress.com/2014/03/27/java-8-default-interface-methods-and-jdk-dynamic-proxies/
                        // https://stackoverflow.com/questions/22614746/how-do-i-invoke-java-8-default-methods-reflectively
                        //
                        // This hack is required to support `org.joda.time.DateTimeZone` in some Tasks, for example
                        // TimestampParser.Task and TimestampParser.TimestampColumnOption.
                        //
                        // TODO: Remove the hack once a cleaner way is found, or Joda-Time is finally removed.
                        // https://github.com/embulk/embulk/issues/890
                        if (CONSTRUCTOR_MethodHandles_Lookup != null) {
                            synchronized (CONSTRUCTOR_MethodHandles_Lookup) {
                                boolean hasSetAccessible = false;
                                try {
                                    CONSTRUCTOR_MethodHandles_Lookup.setAccessible(true);
                                    hasSetAccessible = true;
                                } catch (final SecurityException ex) {
                                    // Skip handling default implementation in case of errors.
                                }

                                if (hasSetAccessible) {
                                    try {
                                        return CONSTRUCTOR_MethodHandles_Lookup
                                                .newInstance(
                                                        method.getDeclaringClass(),
                                                        MethodHandles.Lookup.PUBLIC
                                                                | MethodHandles.Lookup.PRIVATE
                                                                | MethodHandles.Lookup.PROTECTED
                                                                | MethodHandles.Lookup.PACKAGE)
                                                .unreflectSpecial(method, method.getDeclaringClass())
                                                .bindTo(proxy)
                                                .invokeWithArguments();
                                    } catch (final Throwable ex) {
                                        // Skip handling default implementation in case of errors.
                                    } finally {
                                        CONSTRUCTOR_MethodHandles_Lookup.setAccessible(false);
                                    }
                                }
                            }
                        }
                    }
                    checkArgumentLength(method, 0, methodName);
                    return this.invokeGetter(method, fieldName);
                }
                fieldName = setterFieldNameOrNull(methodName);
                if (fieldName != null) {
                    checkArgumentLength(method, 1, methodName);
                    this.invokeSetter(method, fieldName, args[0]);
                    return this;
                }
            }
        }

        throw new IllegalArgumentException(String.format("Undefined method '%s'", methodName));
    }

    /**
     * Returns a Multimap from fieldName Strings to their getter Methods.
     *
     * It expects to be called only from TaskSerDe. Multimap is used inside org.embulk.config.
     */
    static Multimap<String, Method> fieldGetters(final Class<?> iface) {
        ImmutableMultimap.Builder<String, Method> builder = ImmutableMultimap.builder();
        for (Method method : iface.getMethods()) {
            String methodName = method.getName();
            String fieldName = getterFieldNameOrNull(methodName);
            if (fieldName != null && hasExpectedArgumentLength(method, 0)
                    && (!method.isDefault() || method.getAnnotation(Config.class) != null)) {
                // If the method has default implementation, and @Config is not annotated there, the method is kept.
                builder.put(fieldName, method);
            }
        }
        return builder.build();
    }

    // visible for ModelManager.AccessorSerializer
    Map<String, Object> getObjects() {
        return this.objects;
    }

    // visible for ModelManager.AccessorSerializer
    Set<String> getInjectedFields() {
        return this.injectedFields;
    }

    private Object invokeGetter(final Method method, final String fieldName) {
        return this.objects.get(fieldName);
    }

    private void invokeSetter(final Method method, final String fieldName, final Object value) {
        if (value == null) {
            this.objects.remove(fieldName);
        } else {
            this.objects.put(fieldName, value);
        }
    }

    private Map<String, Object> getSerializableFields() {
        final Map<String, Object> data = new HashMap<String, Object>(objects);
        for (final String injected : this.injectedFields) {
            data.remove(injected);
        }
        return data;
    }

    /*
    private TaskSource invokeDump() {
        return new DataSourceImpl(model, model.writeObjectAsObjectNode(this.getSerializableFields()));
    }
    */

    private String invokeToString() {
        final StringBuilder builder = new StringBuilder();
        builder.append(this.iface.getName());
        builder.append(this.getSerializableFields());
        return builder.toString();
    }

    private int invokeHashCode() {
        return this.objects.hashCode();
    }

    private boolean invokeEquals(final Object other) {
        return (other instanceof TaskInvocationHandler)
                && this.objects.equals(((TaskInvocationHandler) other).objects);
    }

    private static String getterFieldNameOrNull(final String methodName) {
        if (methodName.startsWith("get")) {
            return methodName.substring(3);
        }
        return null;
    }

    private static String setterFieldNameOrNull(final String methodName) {
        if (methodName.startsWith("set")) {
            return methodName.substring(3);
        }
        return null;
    }

    private static boolean hasExpectedArgumentLength(final Method method, final int expected) {
        return method.getParameterTypes().length == expected;
    }

    private static void checkArgumentLength(final Method method, final int expected, final String methodName) {
        if (!hasExpectedArgumentLength(method, expected)) {
            throw new IllegalArgumentException(
                    String.format("Method '%s' expected %d argument but got %d arguments",
                                  methodName, expected, method.getParameterTypes().length));
        }
    }

    static {
        Constructor<MethodHandles.Lookup> constructorMethodHandlesLookupTemporary = null;
        try {
            constructorMethodHandlesLookupTemporary =
                    MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        } catch (NoSuchMethodException | SecurityException ex) {
            constructorMethodHandlesLookupTemporary = null;
        } finally {
            CONSTRUCTOR_MethodHandles_Lookup = constructorMethodHandlesLookupTemporary;
        }
    }

    private static final Constructor<MethodHandles.Lookup> CONSTRUCTOR_MethodHandles_Lookup;

    // private final ModelManager model;
    private final Class<?> iface;
    private final Map<String, Object> objects;
    private final Set<String> injectedFields;
}
