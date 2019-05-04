package io.opentracing.contrib.dropwizard;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class EnvUtils {
    public static Map<String, String> extractEnvVars() {
        return ImmutableMap.of(
                Constants.APP_NAME, envOrDefault(Constants.MARATHON_APP_LABEL_NAME, "NA"),
                Constants.APP_VERSION, envOrDefault(Constants.MARATHON_APP_LABEL_VERSION, "NA"),
                Constants.APP_HOST, envOrDefault(Constants.MARATHON_HOSTNAME, "localhost")
        );
    }

    private static String envOrDefault(final String var, final String defaultValue) {
        return !Strings.isNullOrEmpty(System.getenv(var)) ? System.getenv(var) : defaultValue;
    }
}
