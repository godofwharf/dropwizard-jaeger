package com.phonepe.platform.tracing;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.phonepe.platform.tracing.config.JaegerConfig;
import com.phonepe.platform.tracing.config.ReporterConfig;
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
import io.opentracing.Span;
import io.opentracing.contrib.jaxrs2.server.ServerSpanDecorator;
import io.opentracing.contrib.jaxrs2.server.ServerTracingDynamicFeature;
import io.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;
import io.opentracing.propagation.Format;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.eclipse.microprofile.opentracing.Traced;

import javax.servlet.DispatcherType;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class JaegerBundle<T extends Configuration> implements ConfiguredBundle<T> {
    @Override
    public void initialize(final Bootstrap<?> bootstrap) {

    }

    @Override
    public void run(final T configuration, final Environment environment) throws Exception {
        log.info("Setting up jaeger tracer");
        final JaegerConfig jaegerConfig = getJaegerConfig(configuration);
        Map<String, String> envVars = extractEnvVars();
        List<ServerSpanDecorator> serverSpanDecorators = ImmutableList.of(
                new ServerSpanDecorator() {
                    @Override
                    public void decorateRequest(ContainerRequestContext requestContext, Span span) {
                        span.setTag(Constants.APP_NAME, envVars.get(Constants.APP_NAME));
                        span.setTag(Constants.APP_VERSION, envVars.get(Constants.APP_VERSION));
                        span.setTag(Constants.HOSTNAME, envVars.get(Constants.HOSTNAME));
                    }

                    @Override
                    public void decorateResponse(ContainerResponseContext responseContext, Span span) {

                    }
                }, ServerSpanDecorator.STANDARD_TAGS);
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
                .withDecorators(serverSpanDecorators)
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

    private Map<String, String> extractEnvVars() {
        return ImmutableMap.of(
                Constants.APP_NAME, envOrDefault(Constants.MARATHON_APP_LABEL_NAME, "NA"),
                Constants.APP_VERSION, envOrDefault(Constants.MARATHON_APP_LABEL_VERSION, "NA"),
                Constants.HOSTNAME, envOrDefault(Constants.HOSTNAME, "localhost")
        );
    }

    private String envOrDefault(final String var, final String defaultValue) {
        return !Strings.isNullOrEmpty(System.getenv(var)) ? System.getenv(var) : defaultValue;
    }

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
