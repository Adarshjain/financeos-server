package com.financeos.gmail.attention;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.api.gmail.dto.AttentionGroupsResponse.UnknownNumberGroup;
import com.financeos.api.gmail.dto.NumberGroupEmailResponse;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The emails of one unknown-number group: same predicate as the group's count, newest first, paged, own mail only. */
@SpringBootTest
class UnresolvedNumberServiceEmailsIntegrationTest {

    @Autowired private UnresolvedNumberService service;
    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private User other;
    private GmailConnection connection;
    private GmailConnection otherConnection;

    @BeforeEach
    void setUp() {
        user = saveUser("grp-emails");
        other = saveUser("grp-emails-other");
        connection = saveConnection(user);
        otherConnection = saveConnection(other);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), other.getId()));
    }

    @Test
    void listsOnlyTheGroupsMail_newestFirst_withKindAndAmount_andTheCountMatchesTheGroup() {
        GmailProcessedMessage oldest = save(connection, "3003", "alerts@hdfcbank.net", "ALERT", "2021-05-10T00:00:00Z", "100.50", "debit");
        GmailProcessedMessage newest = save(connection, "3003", "alerts@hdfcbank.net", "STATEMENT", "2025-04-10T00:00:00Z", null, null);
        save(connection, "3003", "alerts@icicibank.com", "ALERT", "2024-01-01T00:00:00Z", null, null); // another bank
        save(connection, "7358", "alerts@hdfcbank.net", "ALERT", "2024-01-01T00:00:00Z", null, null); // another number
        GmailProcessedMessage archived = save(connection, "3003", "alerts@hdfcbank.net", "ALERT", "2024-01-01T00:00:00Z", null, null);
        archived.setArchivedAt(Instant.now());
        messageRepository.save(archived);
        save(otherConnection, "3003", "alerts@hdfcbank.net", "ALERT", "2024-01-01T00:00:00Z", null, null); // another user

        Page<NumberGroupEmailResponse> page = service.emails(user.getId(), "hdfc", "3003", PageRequest.of(0, 10));

        UnknownNumberGroup group = service.groups(user.getId()).unknownNumbers().stream()
                .filter(g -> "hdfc".equals(g.bank()) && "3003".equals(g.last4())).findFirst().orElseThrow();
        assertThat(page.getTotalElements()).isEqualTo(group.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(e -> e.item().id()).containsExactly(newest.getId(), oldest.getId());
        assertThat(page.getContent()).extracting(NumberGroupEmailResponse::kind).containsExactly("STATEMENT", "ALERT");
        assertThat(page.getContent().get(1).amount()).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(page.getContent().get(1).direction()).isEqualTo("debit");
        assertThat(page.getContent().get(0).amount()).isNull();
        assertThat(page.getContent().get(0).item().connectionEmail()).isEqualTo(user.getEmail());
    }

    @Test
    void pagesServerSide_andAMissingBankMatchesOnlyMailWithNoBank() {
        for (int i = 1; i <= 5; i++) {
            save(connection, "5005", "someone@unknown.example", "ALERT", "2024-0" + i + "-01T00:00:00Z", null, null);
        }
        save(connection, "5005", "alerts@hdfcbank.net", "ALERT", "2024-06-01T00:00:00Z", null, null);

        Page<NumberGroupEmailResponse> first = service.emails(user.getId(), null, "5005", PageRequest.of(0, 2));
        Page<NumberGroupEmailResponse> last = service.emails(user.getId(), "", "5005", PageRequest.of(2, 2));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(last.getContent()).hasSize(1);
    }

    @Test
    void anotherUsersNumber_listsNothing() {
        save(otherConnection, "3003", "alerts@hdfcbank.net", "ALERT", "2024-01-01T00:00:00Z", null, null);

        assertThat(service.emails(user.getId(), "hdfc", "3003", PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    private GmailProcessedMessage save(GmailConnection conn, String last4, String sender, String kind, String internalDate, String amount, String direction) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress(sender);
        m.setPipelineKind(kind);
        m.setInternalDate(Instant.parse(internalDate));
        m.setSubject("Subject " + internalDate);
        m.setError("No single account matches card/account ending " + last4);
        if (amount != null) {
            m.setExtractAmount(new BigDecimal(amount));
            m.setExtractDirection(direction);
        }
        return messageRepository.save(m);
    }

    private GmailConnection saveConnection(User owner) {
        GmailConnection c = new GmailConnection();
        c.setUser(owner);
        c.setEmail(owner.getEmail());
        c.setEncryptedRefreshToken("refresh-token");
        return connectionRepository.save(c);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
