package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.statement.parser.ProtectedPdfs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The saved statement password is also tried in lowercase and uppercase; the one that opened is used to parse. */
class StatementReconciliationPasswordCaseTest {

    private static final String MESSAGE_ID = "msg-case";

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);

    private String parsedWith(String pdfPassword, String savedPassword) throws Exception {
        GmailIngestProperties properties = new GmailIngestProperties();
        StatementReconciliationService service = new StatementReconciliationService(gmailEngine, statementParser,
                mock(TransactionRepository.class), processed, properties, accountRepository, accountResolver,
                new TransactionMatcher(), new ReviewStatusManager(), persistence, mock(CardRepository.class),
                new ParkedMessageSettler(processed, accountResolver, properties));
        User user = new User();
        user.setId(UUID.randomUUID());
        GmailConnection connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        Account acc = new Account();
        acc.setId(UUID.randomUUID());
        acc.setBankDetails(new AccountBankDetails(acc, null, "1234", savedPassword));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(ProtectedPdfs.pdf(pdfPassword));
        when(accountRepository.findByUserIdAndHasStatementPassword(user.getId())).thenReturn(List.of(acc));
        when(processed.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());
        when(statementParser.parse(any(), any())).thenReturn(new StatementExtractionResult(false, List.of(), null, null, null,
                null, "stop here", null));

        service.reconcile(connection, new GmailMessage(MESSAGE_ID, Instant.parse("2026-10-05T00:00:00Z"), "alerts@bank.example",
                "Statement", "", "body", null, List.of(new GmailAttachment("att-1", "stmt.pdf", "application/pdf", null))));

        org.mockito.ArgumentCaptor<String> pwd = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statementParser).parse(any(), pwd.capture());
        return pwd.getValue();
    }

    @Test
    void uppercaseSavedPasswordOpensALowercasePdf() throws Exception {
        assertThat(parsedWith("abcd1234", "ABCD1234")).isEqualTo("abcd1234");
    }

    @Test
    void anotherAccountsExactPasswordWinsOverACaseVariant() throws Exception {
        Account upper = new Account();
        upper.setId(UUID.randomUUID());
        upper.setBankDetails(new AccountBankDetails(upper, null, "1111", "Abcd1234"));
        Account exact = new Account();
        exact.setId(UUID.randomUUID());
        exact.setBankDetails(new AccountBankDetails(exact, null, "2222", "abcd1234"));

        StatementReconciliationService.PasswordMatch match = StatementReconciliationService.matchByPassword(
                List.of(upper, exact), ProtectedPdfs.pdf("abcd1234"), true);

        assertThat(match.account()).isSameAs(exact);
        assertThat(match.password()).isEqualTo("abcd1234");
    }

    @Test
    void noSavedPasswordOpensTheFile() throws Exception {
        Account acc = new Account();
        acc.setId(UUID.randomUUID());
        acc.setBankDetails(new AccountBankDetails(acc, null, "1111", "wrong999"));

        assertThat(StatementReconciliationService.matchByPassword(List.of(acc), ProtectedPdfs.pdf("abcd1234"), true)).isNull();
    }

    @Test
    void savedPasswordThatOpensAsGivenIsUnchanged() throws Exception {
        assertThat(parsedWith("ABCD1234", "ABCD1234")).isEqualTo("ABCD1234");
    }
}
