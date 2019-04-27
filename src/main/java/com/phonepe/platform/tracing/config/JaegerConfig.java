package com.phonepe.platform.tracing.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.Valid;
import javax.validation.constraints.Min;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}
