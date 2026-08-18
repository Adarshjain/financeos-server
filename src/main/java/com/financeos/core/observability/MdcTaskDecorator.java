package com.financeos.core.observability;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * TaskDecorator that propagates SLF4J MDC context map across async thread boundaries.
 * Intentionally not registered in a Spring configuration until a real async (@Async) or
 * scheduled-executor path requiring MDC context propagation exists.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
