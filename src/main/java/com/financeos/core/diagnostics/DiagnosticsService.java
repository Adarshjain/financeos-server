package com.financeos.core.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RequestSummary;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RootCause;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.TimelineEntry;
import com.financeos.core.diagnostics.dto.RawLogEntry;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.AppConfigProperties;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DiagnosticsService {

    private static final Pattern CROCKFORD_PATTERN = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{8}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_]{1,64}$");

    private static final Set<String> WHITELISTED_FIELDS = Set.of(
            "code", "route", "status", "durationMs", "errorCode", "errorId",
            "exceptionClass", "oraCode", "task", "providerId", "jobType",
            "details", "method", "reason", "slow", "clientIp", "userAgent",
            "requestId", "sessionId", "userId", "model", "httpStatus", "retryAfterMs",
            "version"
    );

    private final AppConfigProperties appConfig;
    private final LokiQueryClient lokiClient;
    private final RootCauseAnalyzer rootCauseAnalyzer;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DiagnosticsService(
            AppConfigProperties appConfig,
            LokiQueryClient lokiClient,
            RootCauseAnalyzer rootCauseAnalyzer,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.lokiClient = lokiClient;
        this.rootCauseAnalyzer = rootCauseAnalyzer;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    private record CollectedLogData(
            String resolvedType,
            String requestId,
            String errorId,
            boolean truncated,
            List<LokiQueryClient.LokiLogLine> lines
    ) {}

    public DiagnosticsLookupResponse lookup(
            UUID adminUserId,
            String ref,
            String type,
            Instant since,
            Instant until) {

        lokiClient.validateRef(ref);
        String resolvedType = resolveRefType(ref, type);

        log.info("Admin diagnostics lookup",
                StructuredArguments.keyValue("event", Events.DIAGNOSTICS_LOOKUP),
                StructuredArguments.keyValue("adminUserId", adminUserId),
                StructuredArguments.keyValue("refType", resolvedType),
                StructuredArguments.keyValue("ref", ref)
        );

        int lookbackDays = appConfig.getDiagnostics().getLoki().getLookbackDays();
        CollectedLogData data = collectRawLines(ref, resolvedType, since, until, lookbackDays);

        if (data.lines().isEmpty()) {
            RootCause notFound = rootCauseAnalyzer.notFound(lookbackDays);
            return new DiagnosticsLookupResponse(
                    ref, data.resolvedType(), data.requestId(), data.errorId(), false, false, null, notFound, List.of(), false
            );
        }

        // Build timeline and parse request summary
        RequestSummary requestSummary = null;
        List<TimelineEntry> timeline = new ArrayList<>();

        for (LokiQueryClient.LokiLogLine line : data.lines()) {
            TimelineEntry entry = parseTimelineEntry(line);
            timeline.add(entry);

            if (requestSummary == null && "http.request".equals(entry.event())) {
                requestSummary = buildRequestSummary(entry, line.at());
            }
        }

        RootCause rootCause = rootCauseAnalyzer.analyze(timeline, requestSummary, lookbackDays);

        return new DiagnosticsLookupResponse(
                ref,
                data.resolvedType(),
                data.requestId(),
                data.errorId(),
                true,
                data.truncated(),
                requestSummary,
                rootCause,
                timeline,
                true
        );
    }

    public List<RawLogEntry> raw(
            UUID adminUserId,
            String ref,
            String type,
            Instant since,
            Instant until) {

        lokiClient.validateRef(ref);
        String resolvedType = resolveRefType(ref, type);

        log.info("Admin diagnostics raw lookup",
                StructuredArguments.keyValue("event", Events.DIAGNOSTICS_LOOKUP),
                StructuredArguments.keyValue("adminUserId", adminUserId),
                StructuredArguments.keyValue("refType", resolvedType),
                StructuredArguments.keyValue("ref", ref)
        );

        int lookbackDays = appConfig.getDiagnostics().getLoki().getLookbackDays();
        CollectedLogData data = collectRawLines(ref, resolvedType, since, until, lookbackDays);

        List<RawLogEntry> rawEntries = new ArrayList<>(data.lines().size());
        for (LokiQueryClient.LokiLogLine line : data.lines()) {
            rawEntries.add(new RawLogEntry(line.at(), line.source(), line.labels(), line.line()));
        }
        return rawEntries;
    }

    private CollectedLogData collectRawLines(
            String ref,
            String resolvedType,
            Instant since,
            Instant until,
            int lookbackDays) {

        Instant end = until != null ? until : Instant.now();
        Instant start = since != null ? since : end.minus(Duration.ofDays(lookbackDays));
        int limit = appConfig.getDiagnostics().getLoki().getMaxLines();

        String requestId = null;
        String errorId = null;
        boolean truncated = false;
        List<LokiQueryClient.LokiLogLine> allLines = new ArrayList<>();

        if ("errorId".equals(resolvedType)) {
            errorId = ref.toUpperCase(Locale.ROOT);
            String logQl = String.format("{service=\"financeos-server\"} | json | errorId=\"%s\"", errorId);
            LokiQueryClient.LokiQueryResult res = lokiClient.query(logQl, start, end, limit);
            if (res.truncated()) truncated = true;

            if (res.lines().isEmpty()) {
                return new CollectedLogData(resolvedType, null, errorId, false, List.of());
            }

            allLines.addAll(res.lines());
            for (LokiQueryClient.LokiLogLine line : res.lines()) {
                String rid = extractFieldFromLine(line.line(), "requestId");
                if (isValidId(rid)) {
                    requestId = rid;
                    break;
                }
            }

            if (requestId == null) {
                // If requestId wasn't in json fields, try searching line text
                for (LokiQueryClient.LokiLogLine line : res.lines()) {
                    if (line.line().contains("requestId=")) {
                        int idx = line.line().indexOf("requestId=");
                        int endIdx = line.line().indexOf(",", idx);
                        if (endIdx < 0) endIdx = line.line().indexOf(" ", idx);
                        if (endIdx > idx + 10) {
                            String candidate = line.line().substring(idx + 10, endIdx).trim();
                            if (isValidId(candidate)) {
                                requestId = candidate;
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            requestId = ref;
        }

        if (isValidId(requestId)) {
            final String safeReqId = requestId;

            CompletableFuture<LokiQueryClient.LokiQueryResult> serverFuture = CompletableFuture.supplyAsync(() -> {
                String serverLogQl = String.format("{service=\"financeos-server\"} | json | requestId=\"%s\"", safeReqId);
                return lokiClient.query(serverLogQl, start, end, limit);
            });

            CompletableFuture<LokiQueryClient.LokiQueryResult> clientFuture = CompletableFuture.supplyAsync(() -> {
                String clientLogQl = String.format("{service=\"financeos-client\"} | json | requestId=\"%s\"", safeReqId);
                try {
                    return lokiClient.query(clientLogQl, start, end, limit);
                } catch (Exception e) {
                    log.warn("Failed fetching client logs for requestId {}: {}", safeReqId, e.getMessage());
                    return new LokiQueryClient.LokiQueryResult(List.of(), false);
                }
            });

            CompletableFuture<LokiQueryClient.LokiQueryResult> faroFuture = CompletableFuture.supplyAsync(() -> {
                String faroLogQl = String.format("{kind=~\".+\"} |= \"%s\"", safeReqId);
                try {
                    return lokiClient.query(faroLogQl, start, end, limit);
                } catch (Exception e) {
                    log.debug("Faro requestId lookup skipped or failed: {}", e.getMessage());
                    return new LokiQueryClient.LokiQueryResult(List.of(), false);
                }
            });

            LokiQueryClient.LokiQueryResult serverRes;
            try {
                serverRes = serverFuture.join();
            } catch (java.util.concurrent.CompletionException e) {
                // Unwrap so ApiStatusException (503 DIAGNOSTICS_UNAVAILABLE etc.) keeps its status instead of becoming a 500.
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw e;
            }
            LokiQueryClient.LokiQueryResult clientRes = clientFuture.join();
            LokiQueryClient.LokiQueryResult faroRes = faroFuture.join();

            if (serverRes.truncated() || clientRes.truncated() || faroRes.truncated()) {
                truncated = true;
            }

            allLines.addAll(serverRes.lines());
            allLines.addAll(clientRes.lines());
            allLines.addAll(faroRes.lines());

            // Extract sessionId & timestamp from server lines
            String sessionId = null;
            Instant requestAt = null;
            for (LokiQueryClient.LokiLogLine l : serverRes.lines()) {
                if (errorId == null) {
                    String eid = extractFieldFromLine(l.line(), "errorId");
                    if (isValidId(eid)) {
                        errorId = eid;
                    }
                }
                if (sessionId == null) {
                    String sid = extractFieldFromLine(l.line(), "sessionId");
                    if (isValidId(sid)) {
                        sessionId = sid;
                    }
                }
                if (requestAt == null) {
                    requestAt = l.at();
                }
            }

            // Client & Faro lines for sessionId (±5 min)
            if (isValidId(sessionId) && requestAt != null) {
                final String safeSessionId = sessionId;
                Instant sessionStart = requestAt.minus(Duration.ofMinutes(5));
                Instant sessionEnd = requestAt.plus(Duration.ofMinutes(5));

                CompletableFuture<LokiQueryClient.LokiQueryResult> clientSessionFuture = CompletableFuture.supplyAsync(() -> {
                    String sessionLogQl = String.format("{service=\"financeos-client\"} | json | sessionId=\"%s\"", safeSessionId);
                    try {
                        return lokiClient.query(sessionLogQl, sessionStart, sessionEnd, limit);
                    } catch (Exception e) {
                        log.warn("Failed fetching client session logs for sessionId {}: {}", safeSessionId, e.getMessage());
                        return new LokiQueryClient.LokiQueryResult(List.of(), false);
                    }
                });

                CompletableFuture<LokiQueryClient.LokiQueryResult> faroSessionFuture = CompletableFuture.supplyAsync(() -> {
                    String faroSessionLogQl = String.format("{kind=~\".+\"} |= \"%s\"", safeSessionId);
                    try {
                        return lokiClient.query(faroSessionLogQl, sessionStart, sessionEnd, limit);
                    } catch (Exception e) {
                        log.debug("Faro sessionId lookup skipped or failed: {}", e.getMessage());
                        return new LokiQueryClient.LokiQueryResult(List.of(), false);
                    }
                });

                LokiQueryClient.LokiQueryResult clientSessionRes = clientSessionFuture.join();
                LokiQueryClient.LokiQueryResult faroSessionRes = faroSessionFuture.join();

                if (clientSessionRes.truncated() || faroSessionRes.truncated()) {
                    truncated = true;
                }
                allLines.addAll(clientSessionRes.lines());
                allLines.addAll(faroSessionRes.lines());
            }
        }

        // Deduplicate lines by timestampNs + line
        Map<String, LokiQueryClient.LokiLogLine> dedupMap = new LinkedHashMap<>();
        for (LokiQueryClient.LokiLogLine l : allLines) {
            dedupMap.put(l.timestampNs() + ":" + l.line(), l);
        }
        List<LokiQueryClient.LokiLogLine> sortedLines = new ArrayList<>(dedupMap.values());
        sortedLines.sort(Comparator.comparingLong(LokiQueryClient.LokiLogLine::timestampNs));

        return new CollectedLogData(resolvedType, requestId, errorId, truncated, sortedLines);
    }

    private String resolveRefType(String ref, String type) {
        if ("requestId".equalsIgnoreCase(type)) return "requestId";
        if ("errorId".equalsIgnoreCase(type)) return "errorId";
        if (ref != null && CROCKFORD_PATTERN.matcher(ref).matches()) {
            return "errorId";
        }
        return "requestId";
    }

    private boolean isValidId(String id) {
        return id != null && SAFE_ID_PATTERN.matcher(id).matches();
    }

    private String extractFieldFromLine(String line, String field) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if (node.has(field) && !node.get(field).isNull() && !node.get(field).isMissingNode()) {
                String text = node.get(field).asText();
                if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private TimelineEntry parseTimelineEntry(LokiQueryClient.LokiLogLine logLine) {
        String message = logLine.line();
        String level = logLine.labels().getOrDefault("level", "INFO").toUpperCase();
        String event = null;
        String logger = null;
        String stackTrace = null;
        Map<String, Object> fields = new LinkedHashMap<>();

        try {
            JsonNode node = objectMapper.readTree(logLine.line());
            if (node.isObject()) {
                if (node.has("message") && !node.get("message").isNull()) {
                    message = node.get("message").asText();
                } else if (node.has("msg") && !node.get("msg").isNull()) {
                    message = node.get("msg").asText();
                }

                if (node.has("level") && !node.get("level").isNull()) {
                    level = node.get("level").asText().toUpperCase();
                }
                if (node.has("event") && !node.get("event").isNull()) {
                    event = node.get("event").asText();
                }
                if (node.has("logger_name") && !node.get("logger_name").isNull()) {
                    logger = node.get("logger_name").asText();
                } else if (node.has("logger") && !node.get("logger").isNull()) {
                    logger = node.get("logger").asText();
                }
                if (node.has("stack_trace") && !node.get("stack_trace").isNull()) {
                    stackTrace = node.get("stack_trace").asText();
                }

                node.fields().forEachRemaining(entry -> {
                    String k = entry.getKey();
                    if (WHITELISTED_FIELDS.contains(k)) {
                        JsonNode v = entry.getValue();
                        if (v == null || v.isNull() || v.isMissingNode()) {
                            return;
                        }
                        if (v.isNumber()) {
                            fields.put(k, v.numberValue());
                        } else if (v.isBoolean()) {
                            fields.put(k, v.booleanValue());
                        } else if (v.isObject() || v.isArray()) {
                            fields.put(k, objectMapper.convertValue(v, Map.class));
                        } else {
                            String txt = v.asText();
                            if (txt != null && !txt.isBlank() && !"null".equalsIgnoreCase(txt)) {
                                fields.put(k, txt);
                            }
                        }
                    }
                });
            }
        } catch (Exception ignored) {
            // Not a json line (e.g. faro logfmt or plaintext)
        }

        return new TimelineEntry(
                logLine.at(),
                logLine.source(),
                level,
                event,
                logger,
                message,
                fields,
                stackTrace
        );
    }

    private RequestSummary buildRequestSummary(TimelineEntry entry, Instant at) {
        Map<String, Object> f = entry.fields() != null ? entry.fields() : Collections.emptyMap();
        String method = (String) f.getOrDefault("method", "GET");
        String route = (String) f.getOrDefault("route", "UNMATCHED");
        int status = f.get("status") instanceof Number num ? num.intValue() : 200;
        long durationMs = f.get("durationMs") instanceof Number num ? num.longValue() : 0L;
        String userId = (String) f.get("userId");
        String version = (String) f.get("version");
        String userAgent = (String) f.get("userAgent");
        boolean slow = Boolean.TRUE.equals(f.get("slow"));

        String userEmail = null;
        if (userId != null && !userId.isBlank()) {
            try {
                userEmail = userRepository.findById(UUID.fromString(userId))
                        .map(com.financeos.domain.user.User::getEmail)
                        .orElse(null);
            } catch (Exception ignored) {}
        }

        return new RequestSummary(
                method,
                route,
                status,
                durationMs,
                at,
                userId,
                userEmail,
                version,
                userAgent,
                slow
        );
    }
}
