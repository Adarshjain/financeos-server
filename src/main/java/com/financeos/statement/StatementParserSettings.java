package com.financeos.statement;

import com.financeos.statement.parser.ParserLimits;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRouting;
import com.financeos.statement.parser.layout.ReaderMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Applies the configured statement-parser limits and layout reader modes to the static parser at startup. Layout modes:
 * {@code app.statement.layouts.default} (OFF or SHADOW; SHADOW when unset; never ACTIVE) and one
 * {@code app.statement.layouts.<profile id>} per profile (OFF, SHADOW or ACTIVE).
 */
@Component
public class StatementParserSettings {

    private static final Logger log = LoggerFactory.getLogger(StatementParserSettings.class);

    public StatementParserSettings(
            @Value("${app.statement.max-pdf-pages:" + ParserLimits.DEFAULT_MAX_PDF_PAGES + "}") int maxPdfPages) {
        ParserLimits.setMaxPdfPages(maxPdfPages);
    }

    @Autowired
    public StatementParserSettings(
            @Value("${app.statement.max-pdf-pages:" + ParserLimits.DEFAULT_MAX_PDF_PAGES + "}") int maxPdfPages,
            Environment env) {
        this(maxPdfPages);
        try {
            ReaderMode def = readMode("default", env.getProperty("app.statement.layouts.default"), false);
            LayoutRouting.setDefaultMode(def);
            Set<String> known = new HashSet<>();
            for (LayoutProfile p : LayoutRouting.registry().profiles()) {
                known.add(p.id());
                LayoutRouting.setMode(p.id(),
                        readMode(p.id(), env.getProperty("app.statement.layouts." + p.id()), true));
            }
            if (env instanceof ConfigurableEnvironment ce) {
                for (PropertySource<?> ps : ce.getPropertySources()) {
                    if (ps instanceof EnumerablePropertySource<?> eps) {
                        for (String name : eps.getPropertyNames()) {
                            if (name.startsWith(PREFIX) && !name.equals(PREFIX + "default")
                                    && !known.contains(name.substring(PREFIX.length()))) {
                                log.warn("{} names no known layout profile and is ignored", name);
                            }
                        }
                    }
                }
            }

        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Throwable t) {
            // layout settings never fail startup: the parser keeps reading with v1 only
            log.error("statement layout settings could not be applied ({}); layouts stay on their defaults",
                    t.getClass().getSimpleName());
        }
    }

    private static final String PREFIX = "app.statement.layouts.";

    /**
     * An unparseable value is an ERROR and counts as SHADOW (boot never fails); {@code default} may only be OFF or
     * SHADOW, ACTIVE is per profile id only (default=ACTIVE is an ERROR and counts as SHADOW).
     */
    private static ReaderMode readMode(String key, String value, boolean perProfile) {
        ReaderMode mode;
        try {
            mode = ReaderMode.parse(value);
        } catch (IllegalArgumentException e) {
            log.error("{}{} is not OFF, SHADOW or ACTIVE; using SHADOW", PREFIX, key);
            return ReaderMode.SHADOW;
        }
        if (!perProfile && mode == ReaderMode.ACTIVE) {
            log.error("{}default cannot be ACTIVE (set it per layout id); using SHADOW", PREFIX);
            return ReaderMode.SHADOW;
        }
        return mode;
    }
}
