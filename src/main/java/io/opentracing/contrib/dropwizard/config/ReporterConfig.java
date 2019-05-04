package io.opentracing.contrib.dropwizard.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReporterConfig {
    @Min(100)
    @Max(86400)
    private int flushIntervalInMs = 1000;

    @Min(8)
    @Max(8192)
    private int maxQueueSize = 100;

    @Min(100)
    @Max(10000)
    private int shutdownTimeInMs = 1000;

    @NotEmpty
    @Pattern(regexp = "http|udp")
    private String sender = "udp";

    @NotEmpty
    private String host = "localhost";

    private int port = 80;
}
