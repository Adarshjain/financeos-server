package com.financeos.statement;

import com.financeos.statement.parser.layout.LayoutRouting;
import com.financeos.statement.parser.layout.ShadowListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code financeos.statement.layout.shadow}: one count per shadow comparison or ACTIVE fallback, tagged {@code layout}
 * (the profile id) and {@code result} (identical | different | failed | fallback). Every comparison that is not
 * identical also logs one INFO line: layout id, counts of differing rows and fields and the NAMES of the differing
 * fields, never a value. Registers itself as the parser's shadow listener, so shadow runs only happen in an application
 * that has this component.
 */
@Component
public class StatementLayoutShadowMetrics implements ShadowListener {

    private static final Logger log = LoggerFactory.getLogger(StatementLayoutShadowMetrics.class);

    private final MeterRegistry registry;

    public StatementLayoutShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
        try {
            LayoutRouting.setShadowListener(this);
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Throwable t) {
            log.error("statement layout shadow listener could not be registered ({})", t.getClass().getSimpleName());
        }
    }

    @Override
    public void onComparison(Result r) {
        try {
            registry.counter("financeos.statement.layout.shadow", "layout", r.layoutId(),
                    "result", r.kind().name().toLowerCase(Locale.ROOT)).increment();
            if (!r.identical()) {
                log.info("statement layout {} {}: {} differing rows, {} differing fields {}", r.layoutId(),
                        r.kind().name().toLowerCase(Locale.ROOT), r.differingRows(), r.differingFields(), r.fieldNames());
            }
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Throwable e) {
            // recording never throws
        }
    }
}
