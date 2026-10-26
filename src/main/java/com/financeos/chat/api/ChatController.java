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
    private final ChatStreamService streamService;
    private final ObjectMapper objectMapper;
    private final com.financeos.chat.db.ChatProperties chatProperties;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChatController(ChatFeatureState featureState,
                          ChatQuotaService quotaService,
                          ChatOrchestrator orchestrator,
                          ChatStreamService streamService,
                          ObjectMapper objectMapper,
                          com.financeos.chat.db.ChatProperties chatProperties) {
        this.featureState = featureState;
        this.quotaService = quotaService;
        this.orchestrator = orchestrator;
        this.streamService = streamService;
        this.objectMapper = objectMapper;
        this.chatProperties = chatProperties;
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

        if (!quotaService.tryAcquireConcurrency()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-Chat-Error", "CHAT_BUSY")
                    .build();
        }

        final ChatStreamService.Prepared prepared;
        try {
            streamService.validate(userId, request);
            prepared = streamService.prepare(userId, request);
        } catch (RuntimeException e) {
            quotaService.releaseConcurrency();
            throw e;
        }

        SseEmitter emitter = new SseEmitter(chatProperties.getStream().getEmitterTimeoutSeconds() * 1000L);

        // A disconnected client must not keep a worker (and a concurrency permit) alive:
        // interrupt the worker on timeout/error, and when a heartbeat fails to send.
        final java.util.concurrent.atomic.AtomicReference<Future<?>> taskRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean disconnected = new java.util.concurrent.atomic.AtomicBoolean(false);

        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug("Heartbeat send failed (client likely gone), cancelling worker: {}", e.getMessage());
                disconnected.set(true);
                Future<?> task = taskRef.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        }, 15, 15, TimeUnit.SECONDS);

        Future<?> workerTask = executorService.submit(() -> {
            try {
                UserContext.setCurrentUserId(userId);
                ChatStreamService.Outcome outcome = streamService.execute(userId, prepared,
                        (name, data) -> emitter.send(SseEmitter.event().name(name).data(data)),
                        disconnected::get);
                if (outcome == ChatStreamService.Outcome.ERROR) {
                    emitter.completeWithError(new IllegalStateException("chat execution failed"));
                } else if (outcome == ChatStreamService.Outcome.DONE) {
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("Chat stream worker failed for user {}: {}", userId, e.getMessage(), e);
                emitter.completeWithError(e);
            } finally {
                heartbeatTask.cancel(true);
                quotaService.releaseConcurrency();
                UserContext.clear();
            }
        });
        taskRef.set(workerTask);
        emitter.onTimeout(() -> { disconnected.set(true); workerTask.cancel(true); });
        emitter.onError((t) -> { disconnected.set(true); workerTask.cancel(true); });

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
            ChatResponseDto dto = new ChatResponseDto(answer.answer(), answer.clarify(), answer.clarifyOptions(), answer.blocks(), answer.traces());
            return ResponseEntity.ok(dto);

        } finally {
            quotaService.releaseConcurrency();
        }
    }
}
