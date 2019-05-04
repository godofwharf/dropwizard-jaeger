package io.opentracing.contrib.dropwizard.annotations;

import java.lang.annotation.*;

/**
 *
 */
@Documented
@Target(value = ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JaegerSpanDecorator {
    boolean enable() default true;
}
