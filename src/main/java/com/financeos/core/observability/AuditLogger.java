package com.financeos.core.observability;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Audit logger for financial state mutations.
 * Emits event=audit.mutation with explicit entity, actor, source, and amount changes.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("com.financeos.core.observability.Audit");

    public void mutation(
            String entity,
            Object entityId,
            String operation,
            String actor,
            String source,
            List<String> changedFields,
            Object amountBefore,
            Object amountAfter,
            String currency) {

        List<String> fields = changedFields != null ? changedFields : Collections.emptyList();
        String curr = currency != null ? currency : "INR";
        String act = actor != null ? actor : "system";

        log.info("Audit mutation: entity={}, entityId={}, operation={}, actor={}, source={}",
                entity, entityId, operation, act, source,
                StructuredArguments.keyValue("event", Events.AUDIT_MUTATION),
                StructuredArguments.keyValue("entity", entity),
                StructuredArguments.keyValue("entityId", entityId != null ? entityId.toString() : ""),
                StructuredArguments.keyValue("operation", operation),
                StructuredArguments.keyValue("actor", act),
                StructuredArguments.keyValue("source", source != null ? source : "manual"),
                StructuredArguments.keyValue("changedFields", String.join(",", fields)),
                StructuredArguments.keyValue("amountBefore", amountBefore != null ? amountBefore : ""),
                StructuredArguments.keyValue("amountAfter", amountAfter != null ? amountAfter : ""),
                StructuredArguments.keyValue("currency", curr));
    }
}
