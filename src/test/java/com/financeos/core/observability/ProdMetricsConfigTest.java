package com.financeos.core.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** application-prod.yml on top of application.yml must give SLO buckets for http and financeos.op, no percentile histogram. */
class ProdMetricsConfigTest {

    @Test
    void mergedProdConfig_hasSloBucketsForBoth_andNoPercentilesHistogram() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // prod has the higher precedence, as the active profile's file does
        for (String file : List.of("application-prod.yml", "application.yml")) {
            for (PropertySource<?> ps : loader.load(file, new ClassPathResource(file))) {
                sources.addLast(ps);
            }
        }
        MetricsProperties props = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("management.metrics", MetricsProperties.class).get();

        assertThat(props.getDistribution().getSlo().get("http.server.requests")).hasSize(4);
        assertThat(props.getDistribution().getSlo().get("financeos.op")).hasSize(9);
        assertThat(props.getDistribution().getPercentilesHistogram()).doesNotContainValue(true);
    }
}
