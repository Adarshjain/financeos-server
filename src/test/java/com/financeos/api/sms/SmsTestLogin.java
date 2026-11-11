package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.support.ApiTestClient;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Signs a test phone in through the real device login and returns its token. */
final class SmsTestLogin {

    private SmsTestLogin() {
    }

    static String token(MockMvc mockMvc, ObjectMapper mapper, String email) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/sms/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", ApiTestClient.PASSWORD,
                        "deviceName", "Test phone")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString()).get("token").asText();
    }
}
