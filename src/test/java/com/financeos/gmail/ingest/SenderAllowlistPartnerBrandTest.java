package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.classify.BankSenderDomains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A bank's senders include the fintech brands (Fi, Jupiter) that mail statements for its accounts. */
class SenderAllowlistPartnerBrandTest {

    private GmailSenderRepository senderRepository;
    private GmailSenderRemovalRepository removalRepository;
    private SenderAllowlistService service;
    private UUID userId;
    private final LocalDate from = LocalDate.of(2021, 12, 1);

    @BeforeEach
    void setUp() {
        senderRepository = mock(GmailSenderRepository.class);
        removalRepository = mock(GmailSenderRemovalRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of());
        when(removalRepository.findByUserId(userId)).thenReturn(List.of());
        when(senderRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SenderAllowlistService(senderRepository, userRepository, mock(GmailSyncCursorService.class),
                mock(ApplicationEventPublisher.class), removalRepository, BankSenderDomains.defaults());
    }

    private GmailSender sender(String address, String bankId, LocalDate floor) {
        GmailSender s = new GmailSender();
        s.setId(UUID.randomUUID());
        s.setSenderAddress(address);
        s.setBankId(bankId);
        s.setBackfillFrom(floor);
        return s;
    }

    @Test
    void federalAddsFiAndJupiterTaggedWithFederalAndFloored() {
        List<GmailSender> added = service.addBankSenders(userId, "federal", from);

        assertThat(added).extracting(GmailSender::getSenderAddress)
                .contains("federalbank.co.in", "fi.money", "jupiter.money");
        assertThat(added).allSatisfy(s -> {
            assertThat(s.getBankId()).isEqualTo("federal");
            assertThat(s.getBackfillFrom()).isEqualTo(from);
        });
    }

    @Test
    void csbAddsCsbBankInAndJupiterButNotFi() {
        List<GmailSender> added = service.addBankSenders(userId, "csb", from);

        assertThat(added).extracting(GmailSender::getSenderAddress)
                .containsExactlyInAnyOrder("csb.co.in", "csb.bank.in", "jupiter.money");
        assertThat(added).allSatisfy(s -> assertThat(s.getBankId()).isEqualTo("csb"));
    }

    @Test
    void removedAndExistingBrandAddressesAreSkipped() {
        GmailSenderRemoval removed = new GmailSenderRemoval();
        removed.setSenderAddress("Fi.Money");
        when(removalRepository.findByUserId(userId)).thenReturn(List.of(removed));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of(sender("JUPITER.money", null, null)));

        List<GmailSender> added = service.addBankSenders(userId, "federal", from);

        assertThat(added).extracting(GmailSender::getSenderAddress).doesNotContain("fi.money", "jupiter.money");
        assertThat(added).extracting(GmailSender::getSenderAddress).contains("federalbank.co.in");
    }

    @Test
    void secondAccountLowersAnExistingBrandSenderFloor() {
        GmailSender existing = sender("jupiter.money", "csb", LocalDate.of(2026, 1, 1));
        when(senderRepository.findByUserId(userId)).thenReturn(List.of(existing));

        service.addBankSenders(userId, "csb", from);

        assertThat(existing.getBackfillFrom()).isEqualTo(from);
    }
}
