package org.veltismc.api.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginMeta {
    String name();
    String[] authors() default {"Unknown"};
    String version() default "0";
    String description() default "";
}
