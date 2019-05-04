package io.opentracing.contrib.dropwizard.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class JaegerConfig {
    @NotEmpty
    private String serviceName;

    private boolean localOnly;

    @Min(1)
    private long maxTracesPerSecond;

    @Valid
    private ReporterConfig reporter;

    private boolean traceAll;

    private boolean traceSerialization;

    private String skipApiPathPattern;

    private boolean enableDynamicInjection;

    private List<String> decoratorPackages = new ArrayList<>();
}
