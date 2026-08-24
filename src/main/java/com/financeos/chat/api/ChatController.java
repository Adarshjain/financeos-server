package com.financeos.chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.db.ChatFeatureState;
import com.financeos.chat.orchestrator.*;
import com.financeos.core.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatFeatureState featureState;
    private final ChatQuotaService quotaService;
    private final ChatOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChatController(ChatFeatureState featureState,
                          ChatQuotaService quotaService,
                          ChatOrchestrator orchestrator,
                          ObjectMapper objectMapper) {
        this.featureState = featureState;
        this.quotaService = quotaService;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@RequestBody ChatStreamRequest request) {
        if (!featureState.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Chat-Error", "CHAT_DISABLED")
                    .build();
        }

        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!quotaService.tryConsumeMessageQuota(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-Chat-Error", "CHAT_QUOTA_EXCEEDED")
                    .build();
        }

        if (!quotaService.tryAcquireConcurrency()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-Chat-Error", "CHAT_BUSY")
                    .build();
        }

        SseEmitter emitter = new SseEmitter(120_000L); // 120s timeout

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug("Heartbeat send failed: {}", e.getMessage());
            }
        }, 15, 15, TimeUnit.SECONDS);

        executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            int msgLength = (request.messages() != null && !request.messages().isEmpty())
                    ? request.messages().get(request.messages().size() - 1).content().length()
                    : 0;

            try {
                UserContext.setCurrentUserId(userId);

                ChatEventSink sink = new ChatEventSink() {
                    @Override
                    public void onStatus(String statusMessage) {
                        try {
                            ObjectNode node = objectMapper.createObjectNode();
                            node.put("status", statusMessage);
                            emitter.send(SseEmitter.event().name("status").data(node.toString()));
                        } catch (IOException e) {
                            log.debug("Failed to stream status event: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onTrace(ChatTraceEntry traceEntry) {
                        try {
                            String json = objectMapper.writeValueAsString(traceEntry);
                            emitter.send(SseEmitter.event().name("trace").data(json));
                        } catch (IOException e) {
                            log.debug("Failed to stream trace event: {}", e.getMessage());
                        }
                    }
                };

                ChatAnswer answer = orchestrator.run(request.messages(), sink);

                ObjectNode finalNode = objectMapper.createObjectNode();
                if (answer.answer() != null) {
                    finalNode.put("answer", answer.answer());
                }
                if (answer.clarify() != null) {
                    finalNode.put("clarify", answer.clarify());
                }

                emitter.send(SseEmitter.event().name("final").data(finalNode.toString()));
                emitter.complete();

                long durationMs = System.currentTimeMillis() - startTime;
                log.info("Chat stream completed: userId={}, msgLength={}, steps={}, durationMs={}",
                        userId, msgLength, answer.traces().size(), durationMs);

            } catch (Exception e) {
                log.error("Error during chat streaming execution for user {}: {}", userId, e.getMessage(), e);
                try {
                    ObjectNode errNode = objectMapper.createObjectNode();
                    errNode.put("code", "EXECUTION_ERROR");
                    errNode.put("message", "Something went wrong while answering. Please try again.");
                    emitter.send(SseEmitter.event().name("error").data(errNode.toString()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                heartbeatTask.cancel(true);
                quotaService.releaseConcurrency();
                UserContext.clear();
            }
        });

        return ResponseEntity.ok(emitter);
    }

    @PostMapping
    public ResponseEntity<ChatResponseDto> syncChat(@RequestBody ChatStreamRequest request) {
        if (!featureState.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!quotaService.tryConsumeMessageQuota(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        if (!quotaService.tryAcquireConcurrency()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        try {
            List<ChatTraceEntry> traces = new ArrayList<>();
            ChatEventSink sink = new ChatEventSink() {
                @Override
                public void onStatus(String statusMessage) {}

                @Override
                public void onTrace(ChatTraceEntry traceEntry) {
                    traces.add(traceEntry);
                }
            };

            ChatAnswer answer = orchestrator.run(request.messages(), sink);
            ChatResponseDto dto = new ChatResponseDto(answer.answer(), answer.clarify(), answer.traces());
            return ResponseEntity.ok(dto);

        } finally {
            quotaService.releaseConcurrency();
        }
    }
}
