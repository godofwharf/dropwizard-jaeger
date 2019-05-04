package io.opentracing.contrib.dropwizard;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.Injector;
import io.opentracing.contrib.dropwizard.annotations.JaegerSpanDecorator;
import io.opentracing.contrib.dropwizard.config.JaegerConfig;
import io.opentracing.contrib.dropwizard.config.ReporterConfig;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.clock.SystemClock;
import io.jaegertracing.internal.metrics.InMemoryMetricsFactory;
import io.jaegertracing.internal.metrics.Metrics;
import io.jaegertracing.internal.propagation.B3TextMapCodec;
import io.jaegertracing.internal.reporters.InMemoryReporter;
import io.jaegertracing.internal.reporters.RemoteReporter;
import io.jaegertracing.internal.samplers.RateLimitingSampler;
import io.jaegertracing.spi.Reporter;
import io.jaegertracing.thrift.internal.senders.HttpSender;
import io.jaegertracing.thrift.internal.senders.UdpSender;
import io.opentracing.contrib.jaxrs2.server.ServerSpanDecorator;
import io.opentracing.contrib.jaxrs2.server.ServerTracingDynamicFeature;
import io.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;
import io.opentracing.propagation.Format;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.eclipse.microprofile.opentracing.Traced;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.servlet.DispatcherType;
import javax.ws.rs.Path;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public abstract class JaegerBundle<T extends Configuration> implements ConfiguredBundle<T> {
    @Override
    public void initialize(final Bootstrap<?> bootstrap) {

    }

    @Override
    public void run(final T configuration, final Environment environment) throws Exception {
        log.info("Setting up jaeger tracer");
        final JaegerConfig jaegerConfig = getJaegerConfig(configuration);
        List<ServerSpanDecorator> decorators = new ArrayList<>();
        if (!jaegerConfig.getDecoratorPackages().isEmpty()) {
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(
                            jaegerConfig.getDecoratorPackages()
                                .stream()
                                .map(packagePath -> ClasspathHelper.forPackage(packagePath))
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList()))
                    .setScanners(new SubTypesScanner(), new TypeAnnotationsScanner()));
            Set<Class<?>> types = reflections.getTypesAnnotatedWith(JaegerSpanDecorator.class, true);
            types
                    .stream()
                    .filter(type -> type.getDeclaredAnnotation(JaegerSpanDecorator.class).enable())
                    .forEach(type -> {
                        try {
                            Constructor<?>[] constructors = type.getConstructors();
                            if (constructors.length == 0) {
                                log.warn("No zero arg constructor found for class {}", type.getCanonicalName());
                            }
                            ServerSpanDecorator decorator = (ServerSpanDecorator) type.newInstance();
                            long injectConstructorsCount = Stream.of(constructors)
                                    .filter(constructor -> constructor.getDeclaredAnnotation(Inject.class) != null)
                                    .count();
                            if (injectConstructorsCount == 1 && jaegerConfig.isEnableDynamicInjection()) {
                                Injector injector = InjectorHolder.getInjector();
                                if (injector != null) {
                                    injector.injectMembers(decorator);
                                } else {
                                    log.warn("enableDynamicInjection is set to true whereas no injector is provided, " +
                                            "please make sure you call InjectorHolder.configure(injector)");
                                }
                            }
                            decorators.add(decorator);
                        } catch (Exception e) {
                            log.error("Error creating decorator from subType {}", type.getCanonicalName(), e);
                            throw new RuntimeException(e);
                        }
                    });
        }
        decorators.add(ServerSpanDecorator.STANDARD_TAGS);
        final JaegerTracer tracer = new JaegerTracer.Builder(jaegerConfig.getServiceName())
                .withClock(new SystemClock())
                .registerExtractor(Format.Builtin.HTTP_HEADERS, new B3TextMapCodec.Builder().build())
                .registerInjector(Format.Builtin.HTTP_HEADERS, new B3TextMapCodec.Builder().build())
                .withExpandExceptionLogs()
                .withManualShutdown()
                .withMetricsFactory(new InMemoryMetricsFactory())
                .withReporter(getReporter(jaegerConfig.getReporter(), jaegerConfig.isLocalOnly()))
                .withSampler(new RateLimitingSampler(jaegerConfig.getMaxTracesPerSecond()))
                .build();
        GlobalTracer.register(tracer);
        val builder = new ServerTracingDynamicFeature.Builder(tracer)
                .withDecorators(decorators)
                .withJoinExistingActiveSpan(false)
                .withTraceSerialization(jaegerConfig.isTraceSerialization())
                .withSkipPattern(jaegerConfig.getSkipApiPathPattern())
                .withOperationNameProvider((clazz, method) -> requestContext -> {
                            Traced traced = method.getDeclaredAnnotation(Traced.class);
                            if (traced != null && !Strings.isNullOrEmpty(traced.operationName())) {
                                return traced.operationName();
                            }
                            Path resourceClassPath = clazz.getDeclaredAnnotation(Path.class);
                            Path resourceMethodPath = method.getDeclaredAnnotation(Path.class);
                            return String.format("%s %s%s", requestContext.getMethod(), resourceClassPath.value(), resourceMethodPath.value());
                        });
        if (!jaegerConfig.isTraceAll()) {
            builder.withTraceNothing();
        }
        environment.jersey().register(builder.build());
        environment.servlets().addFilter("spanFinishingFilter", new SpanFinishingFilter())
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");;
        log.info("Jaeger bundle is ready for usage");
        Runtime.getRuntime().addShutdownHook(new Thread(tracer::close, "jaeger-shutdown-hook"));
    }

    protected abstract JaegerConfig getJaegerConfig(final T configuration);

    private Reporter getReporter(final ReporterConfig reporterConfig, final boolean localOnly) {
        if (localOnly) {
            return new InMemoryReporter();
        }
        return new RemoteReporter.Builder()
                .withCloseEnqueueTimeout(reporterConfig.getShutdownTimeInMs())
                .withFlushInterval(reporterConfig.getFlushIntervalInMs())
                .withMaxQueueSize(reporterConfig.getMaxQueueSize())
                .withMetrics(new Metrics(new InMemoryMetricsFactory()))
                .withSender(reporterConfig.getSender().equalsIgnoreCase("http") ?
                        new HttpSender.Builder(String.format("http://%s:%d/api/traces", reporterConfig.getHost(), reporterConfig.getPort())).build() :
                        new UdpSender(reporterConfig.getHost(), reporterConfig.getPort(), 0))
                .build();
    }
}
