package io.opentracing.contrib.dropwizard;

import com.google.inject.Injector;

public class InjectorHolder {
    private static Injector injector;

    public static void configure(final Injector injector) {
        InjectorHolder.injector = injector;
    }

    public static Injector getInjector() {
        return injector;
    }
}
