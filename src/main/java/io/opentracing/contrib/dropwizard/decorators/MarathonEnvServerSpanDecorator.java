package io.opentracing.contrib.dropwizard.decorators;

import io.opentracing.contrib.dropwizard.Constants;
import io.opentracing.Span;
import io.opentracing.contrib.dropwizard.annotations.JaegerSpanDecorator;
import io.opentracing.contrib.jaxrs2.server.ServerSpanDecorator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import java.util.Map;

import static io.opentracing.contrib.dropwizard.EnvUtils.extractEnvVars;

@Data
@Builder
@AllArgsConstructor
@JaegerSpanDecorator
public class MarathonEnvServerSpanDecorator implements ServerSpanDecorator {
    private Map<String, String> envVars;

    public MarathonEnvServerSpanDecorator() {
        this.envVars = extractEnvVars();
    }

    @Override
    public void decorateRequest(ContainerRequestContext requestContext, Span span) {
        span.setTag(Constants.APP_NAME, envVars.get(Constants.APP_NAME));
        span.setTag(Constants.APP_VERSION, envVars.get(Constants.APP_VERSION));
        span.setTag(Constants.APP_HOST, envVars.get(Constants.APP_HOST));
    }

    @Override
    public void decorateResponse(ContainerResponseContext responseContext, Span span) {

    }
}
