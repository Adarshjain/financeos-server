package com.financeos.core.diagnostics.dto;

import java.time.Instant;
import java.util.Map;

public record RawLogEntry(
        Instant at,
        String source,
        Map<String, String> labels,
        String line) {}
