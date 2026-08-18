package com.financeos.api.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.domain.user.AuthService;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GoogleCallbackRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger authLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        authLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        authLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        authLogger.detachAppender(appender);
    }

    @Test
    void testGoogleCallbackErrorReturns400WithValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/google/callback")
                        .param("error", "access_denied")
                        .param("state", "abc12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertFalse(appender.list.isEmpty());
        boolean foundErrorLog = appender.list.stream().anyMatch(event -> {
            Object[] args = event.getArgumentArray();
            return args != null && Arrays.stream(args)
                    .filter(arg -> arg instanceof StructuredArgument)
                    .anyMatch(arg -> arg.toString().contains("error=access_denied"));
        });

        assertTrue(foundErrorLog, "Emitted log line must contain StructuredArgument error=access_denied");
    }
}
