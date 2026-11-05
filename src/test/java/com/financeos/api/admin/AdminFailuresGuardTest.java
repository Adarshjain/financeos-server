package com.financeos.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobWorker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fail-closed admin guard on the Failures endpoints: only an email listed in ADMIN_EMAILS passes. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.emails=failures-guard-admin@example.test", "app.invite.code=test-invite-code"})
class AdminFailuresGuardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private JobWorker jobWorker;

    @Test
    void nonAdminGets403OnEveryEndpoint() throws Exception {
        Cookie user = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-guard-user@example.test", "userPass123!");
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/admin/failures/summary").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failures/jobs").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failures/gmail").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failures/jobs/" + id).cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failures/gmail/" + id).cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/failures/gmail/" + id + "/retry").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/failures/jobs/" + id + "/retry").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/failures/retry-all").cookie(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"gmail\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/failures/jobs/" + id + "/dismiss").cookie(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/failures/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void explicitAdminGets200() throws Exception {
        Cookie admin = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-guard-admin@example.test", "adminPass123!");
        mockMvc.perform(get("/api/v1/admin/failures/summary").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs").isNumber());
    }

    @Test
    void meExposesFailuresAdminOnlyForTheExplicitAdmin() throws Exception {
        Cookie admin = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-guard-admin@example.test", "adminPass123!");
        Cookie user = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-guard-user@example.test", "userPass123!");
        mockMvc.perform(get("/api/v1/auth/me").cookie(admin)).andExpect(jsonPath("$.failuresAdmin").value(true));
        mockMvc.perform(get("/api/v1/auth/me").cookie(user)).andExpect(jsonPath("$.failuresAdmin").value(false));
    }
}
