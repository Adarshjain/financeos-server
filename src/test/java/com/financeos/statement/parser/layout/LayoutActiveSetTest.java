package com.financeos.statement.parser.layout;

import com.financeos.statement.StatementParserSettings;
import com.financeos.statement.parser.ParserLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The layouts application.yml switches to ACTIVE: exactly the ones that passed the corpus comparison, all known ids. */
class LayoutActiveSetTest {

    private static final Set<String> ACTIVE = Set.of(
            "axis-card-dtda-2022", "axis-card-dtda-2024", "axis-card-dtdace-2021",
            "axis-card-dtdace-2024a", "axis-card-dtdace-2024b", "hdfc-card-dtda-2018",
            "hdfc-card-dttda-2025", "hdfc-card-dttdra-2026", "icici-card-sdtdaa-2021",
            "icici-card-sdtdra-2023");

    private static final String PREFIX = "app.statement.layouts.";

    @BeforeEach
    @AfterEach
    void reset() {
        LayoutRouting.reset();
    }

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    void applicationYmlListsExactlyTheApprovedLayoutsAsActive() {
        Set<String> listed = new TreeSet<>();
        applicationYml().forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith(PREFIX)) {
                assertEquals("ACTIVE", v.toString(), key);
                listed.add(key.substring(PREFIX.length()));
            }
        });
        assertEquals(new TreeSet<>(ACTIVE), listed);
    }

    @Test
    void everyListedIdIsAKnownProfileAndTheRestStayShadow() {
        MockEnvironment env = new MockEnvironment();
        applicationYml().forEach((k, v) -> env.setProperty(k.toString(), v.toString()));
        new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, env);

        Set<String> known = new TreeSet<>();
        for (LayoutProfile p : LayoutRouting.registry().profiles()) {
            known.add(p.id());
            ReaderMode expected = ACTIVE.contains(p.id()) ? ReaderMode.ACTIVE : ReaderMode.SHADOW;
            assertEquals(expected, LayoutRouting.modeOf(p.id()), p.id());
        }
        assertTrue(known.containsAll(ACTIVE), "unknown id among " + ACTIVE);
    }
}
