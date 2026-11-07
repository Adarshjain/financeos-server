package com.financeos.api.gmail;

import static com.financeos.gmail.view.MimeContentTest.leaf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "View email" over HTTP: the owner reads the mail, anybody else gets a 404 and never reaches Gmail. */
@SpringBootTest
@AutoConfigureMockMvc
class GmailMessageContentControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @MockBean private GmailApiClient gmailApiClient;

    private ApiTestClient owner;
    private ApiTestClient stranger;
    private User ownerUser;
    private User strangerUser;
    private GmailProcessedMessage ledger;

    @BeforeEach
    void setUp() throws Exception {
        String a = "view-email-owner-" + UUID.randomUUID() + "@example.test";
        String b = "view-email-other-" + UUID.randomUUID() + "@example.test";
        owner = ApiTestClient.signUp(mockMvc, mapper, a);
        stranger = ApiTestClient.signUp(mockMvc, mapper, b);
        ownerUser = userRepository.findByEmail(a).orElseThrow();
        strangerUser = userRepository.findByEmail(b).orElseThrow();
        GmailConnection connection = new GmailConnection();
        connection.setUser(ownerUser);
        connection.setEmail(a);
        connection.setEncryptedRefreshToken("token");
        connection = connectionRepository.save(connection);
        ledger = new GmailProcessedMessage();
        ledger.setConnection(connection);
        ledger.setUser(ownerUser);
        ledger.setGmailMessageId("gm-1");
        ledger.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        ledger = messageRepository.save(ledger);
        when(gmailApiClient.createGmailService(any())).thenReturn(mock(Gmail.class));
        Message m = new Message().setInternalDate(1_700_000_000_000L).setPayload(
                leaf("text/html", "<p>Hello <img src=\"https://t/x.gif\" alt=\"logo\"></p><script>x()</script>", StandardCharsets.UTF_8,
                        "Subject", "Statement"));
        when(gmailApiClient.getMessage(any(), org.mockito.ArgumentMatchers.eq("gm-1"))).thenReturn(m);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(ownerUser.getId(), strangerUser.getId()));
    }

    @Test
    void ownerSeesTheSanitizedMail() throws Exception {
        JsonNode body = owner.getJson("/api/v1/gmail/messages/" + ledger.getId() + "/content", 200);
        assertEquals("Statement", body.get("subject").asText());
        String html = body.get("html").asText();
        assertTrue(html.contains("Hello") && html.contains("logo"));
        assertFalse(html.contains("<img") || html.contains("script"));
    }

    @Test
    void anotherUsersLedgerIdIs404AndGmailIsNeverCalled() throws Exception {
        stranger.getJson("/api/v1/gmail/messages/" + ledger.getId() + "/content", 404);
        org.mockito.Mockito.verifyNoInteractions(gmailApiClient);
        stranger.getJson("/api/v1/gmail/messages/" + UUID.randomUUID() + "/content", 404);
    }
}
