package com.financeos.domain.job;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class JobHandlerRegistry {

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public JobHandlerRegistry(List<JobHandler> handlerList) {
        for (JobHandler handler : handlerList) {
            if (handlers.containsKey(handler.type())) {
                throw new IllegalStateException("Duplicate JobHandler registered for type: " + handler.type());
            }
            handlers.put(handler.type(), handler);
        }
    }

    public JobHandler get(JobType type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No JobHandler registered for type: " + type);
        }
        return handler;
    }
}
