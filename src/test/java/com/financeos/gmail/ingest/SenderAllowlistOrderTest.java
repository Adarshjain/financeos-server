package com.financeos.gmail.ingest;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The senders list is oldest first, so a sender the user added comes before the document senders a later connect adds. */
@SpringBootTest
class SenderAllowlistOrderTest {

    @Autowired private SenderAllowlistService service;
    @Autowired private GmailSenderRepository senderRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @AfterEach
    void tearDown() {
        if (user == null) return;
        senderRepository.deleteAll(senderRepository.findByUserId(user.getId()));
        userRepository.delete(user);
    }

    @Test
    void sendersAreListedOldestFirstWhateverTheInsertOrder() {
        user = new User();
        user.setDisplayName("sender-order");
        user.setEmail("sender-order-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
        // saved newest first, so the order of the rows on disk is the reverse of the expected list
        sender("nps-statements@mailer.proteantech.in", t0.plusSeconds(120));
        sender("cra@nsdl.co.in", t0.plusSeconds(60));
        sender("alerts@bank.test", t0);

        assertThat(service.getSenders(user.getId())).extracting(GmailSender::getSenderAddress)
                .containsExactly("alerts@bank.test", "cra@nsdl.co.in", "nps-statements@mailer.proteantech.in");
    }

    private void sender(String address, Instant createdAt) {
        GmailSender s = new GmailSender();
        s.setUser(user);
        s.setName(address);
        s.setSenderAddress(address);
        s.setEnabled(true);
        s.setCreatedAt(createdAt);
        senderRepository.save(s);
    }
}
