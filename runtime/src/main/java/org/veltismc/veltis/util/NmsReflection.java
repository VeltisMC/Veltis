package org.veltismc.veltis.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NmsReflection {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ClassValue<ClassEntry> CLASS_CACHE = new ClassValue<>() {
        @Override
        protected ClassEntry computeValue(Class<?> type) {
            return new ClassEntry(type);
        }
    };

    private NmsReflection() {}

    public static ClassEntry of(Class<?> clazz) {
        return CLASS_CACHE.get(clazz);
    }

    public static ClassEntry ofName(String name) {
        try {
            var cl = Class.forName(name);
            return CLASS_CACHE.get(cl);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("NMS class not found: " + name, e);
        }
    }

    public static final class ClassEntry {
        private final Class<?> clazz;
        private final Map<String, MethodHandle> methodCache = new ConcurrentHashMap<>();
        private final Map<String, MethodHandle> fieldCache = new ConcurrentHashMap<>();
        private final Map<String, MethodHandle> staticFieldCache = new ConcurrentHashMap<>();

        ClassEntry(Class<?> clazz) {
            this.clazz = clazz;
        }

        public Class<?> get() {
            return clazz;
        }

        public MethodHandle method(String name, Class<?>... paramTypes) {
            var key = name + ":" + descriptors(paramTypes);
            return methodCache.computeIfAbsent(key, k -> {
                try {
                    var method = clazz.getMethod(name, paramTypes);
                    method.setAccessible(true);
                    return LOOKUP.unreflect(method);
                } catch (Exception e) {
                    throw new IllegalArgumentException("No method " + clazz.getName() + "." + name, e);
                }
            });
        }

        public MethodHandle methodExact(Class<?> returnType, String name, Class<?>... paramTypes) {
            var key = "E:" + name + ":" + descriptors(paramTypes) + "->" + returnType.getName();
            return methodCache.computeIfAbsent(key, k -> {
                try {
                    var mt = MethodType.methodType(returnType, paramTypes);
                    return LOOKUP.findVirtual(clazz, name, mt);
                } catch (Exception e) {
                    try {
                        var method = clazz.getMethod(name, paramTypes);
                        method.setAccessible(true);
                        return LOOKUP.unreflect(method);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("No method " + clazz.getName() + "." + name, ex);
                    }
                }
            });
        }

        public MethodHandle staticMethod(String name, Class<?> returnType, Class<?>... paramTypes) {
            var key = "S:" + name + ":" + descriptors(paramTypes) + "->" + returnType.getName();
            return methodCache.computeIfAbsent(key, k -> {
                try {
                    var mt = MethodType.methodType(returnType, paramTypes);
                    return LOOKUP.findStatic(clazz, name, mt);
                } catch (Exception e) {
                    try {
                        var method = clazz.getMethod(name, paramTypes);
                        method.setAccessible(true);
                        return LOOKUP.unreflect(method);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("No static method " + clazz.getName() + "." + name, ex);
                    }
                }
            });
        }

        public MethodHandle field(String name) {
            return fieldCache.computeIfAbsent(name, k -> {
                try {
                    var field = findField(clazz, name);
                    field.setAccessible(true);
                    return LOOKUP.unreflectGetter(field);
                } catch (Exception e) {
                    throw new IllegalArgumentException("No field " + clazz.getName() + "." + name, e);
                }
            });
        }

        public MethodHandle fieldSetter(String name) {
            return fieldCache.computeIfAbsent("SET:" + name, k -> {
                try {
                    var field = findField(clazz, name);
                    field.setAccessible(true);
                    return LOOKUP.unreflectSetter(field);
                } catch (Exception e) {
                    throw new IllegalArgumentException("No field " + clazz.getName() + "." + name, e);
                }
            });
        }

        public MethodHandle staticField(String name) {
            return staticFieldCache.computeIfAbsent(name, k -> {
                try {
                    var field = findField(clazz, name);
                    field.setAccessible(true);
                    return LOOKUP.unreflectGetter(field);
                } catch (Exception e) {
                    throw new IllegalArgumentException("No static field " + clazz.getName() + "." + name, e);
                }
            });
        }

        public Object invoke(Object instance, String name, Object... args) {
            try {
                var types = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
                return method(name, types).invoke(instance, args);
            } catch (Throwable e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Failed to invoke " + clazz.getName() + "." + name, e);
            }
        }

        public Object invokeV(Object instance, String name, Class<?>[] paramTypes, Object... args) {
            try {
                return method(name, paramTypes).invoke(instance, args);
            } catch (Throwable e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Failed to invoke " + clazz.getName() + "." + name, e);
            }
        }

        public Object getField(Object instance, String name) {
            try {
                return field(name).invoke(instance);
            } catch (Throwable e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Failed to get field " + clazz.getName() + "." + name, e);
            }
        }

        public void setField(Object instance, String name, Object value) {
            try {
                fieldSetter(name).invoke(instance, value);
            } catch (Throwable e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Failed to set field " + clazz.getName() + "." + name, e);
            }
        }

        public Object getStatic(String name) {
            try {
                return staticField(name).invoke();
            } catch (Throwable e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Failed to get static field " + clazz.getName() + "." + name, e);
            }
        }

        private static Field findField(Class<?> clazz, String name) {
            for (var c = clazz; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {}
            }
            try {
                return clazz.getField(name);
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException("Field not found: " + clazz.getName() + "." + name, e);
            }
        }

        private static String descriptors(Class<?>... types) {
            if (types.length == 0) return "";
            var sb = new StringBuilder();
            for (var t : types) sb.append(t.getName()).append(",");
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    }
}
