package com.financeos.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobWorker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADMIN_EMAILS blank: the open-to-all admin stays open for diagnostics, but the Failures tool admits nobody. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.emails=", "app.invite.code=test-invite-code"})
class AdminFailuresOpenAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private JobWorker jobWorker;

    @Test
    void blankAdminEmailsReturns403ForEveryone() throws Exception {
        Cookie user = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-open-user@example.test", "userPass123!");
        mockMvc.perform(get("/api/v1/admin/failures/summary").cookie(user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failures/jobs").cookie(user)).andExpect(status().isForbidden());
    }

    @Test
    void blankAdminEmailsKeepsAdminTrueButNotFailuresAdmin() throws Exception {
        Cookie user = AdminFailuresTestSupport.login(mockMvc, mapper, "failures-open-user@example.test", "userPass123!");
        mockMvc.perform(get("/api/v1/auth/me").cookie(user))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.failuresAdmin").value(false));
    }
}
