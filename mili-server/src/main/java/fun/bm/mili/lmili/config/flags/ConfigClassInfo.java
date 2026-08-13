package fun.bm.mili.lmili.config.flags;

import fun.bm.mili.lmili.enums.EnumConfigCategory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigClassInfo {
    EnumConfigCategory category();

    String name();

    String[] directory() default {};

    String comments() default "";
}
