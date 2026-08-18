package com.financeos.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MdcTaskDecoratorTest {

    private MdcTaskDecorator taskDecorator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        taskDecorator = new MdcTaskDecorator();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        MDC.clear();
    }

    @Test
    void testMdcContextPropagatedToWorkerThread() throws Exception {
        MDC.put("requestId", "async-req-999");
        MDC.put("userId", "1001");

        CountDownLatch latch = new CountDownLatch(1);
        String[] capturedMdc = new String[2];

        Runnable task = taskDecorator.decorate(() -> {
            capturedMdc[0] = MDC.get("requestId");
            capturedMdc[1] = MDC.get("userId");
            latch.countDown();
        });

        executor.submit(task);
        assertTrue(latch.await(3, TimeUnit.SECONDS));

        assertEquals("async-req-999", capturedMdc[0]);
        assertEquals("1001", capturedMdc[1]);
    }

    @Test
    void testWorkerThreadMdcClearedAfterExecution() throws Exception {
        MDC.put("requestId", "temp-req");
        CountDownLatch latch = new CountDownLatch(1);
        String[] workerMdcAfterTask = new String[1];

        Runnable task = taskDecorator.decorate(() -> {
            // Execution
        });

        executor.submit(() -> {
            task.run();
            workerMdcAfterTask[0] = MDC.get("requestId");
            latch.countDown();
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(workerMdcAfterTask[0], "Worker thread MDC must be cleared after task execution");
    }
}
