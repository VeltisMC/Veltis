package io.papermc.paper.plugin.provider.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InaccessibleObjectException;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

@NullMarked
@ApiStatus.Internal
public final class ProviderUtil {

    public static <T> T loadClass(final String clazz, final Class<T> classType, final ClassLoader loader) {
        try {
            final Class<?> jarClass = Class.forName(clazz, true, loader);
            final Class<? extends T> pluginClass;
            try {
                pluginClass = jarClass.asSubclass(classType);
            } catch (final ClassCastException ex) {
                throw new ClassCastException("class '%s' does not extend '%s'".formatted(clazz, classType));
            }
            final Constructor<? extends T> constructor = pluginClass.getDeclaredConstructor();
            try {
                constructor.setAccessible(true);
            } catch (final InaccessibleObjectException | SecurityException ex) {
                throw new RuntimeException("Inaccessible constructor");
            }
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load class " + clazz, e);
        }
    }
}
