package com.financeos.gmail.accountdetect.job;

import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Jupiter savings statement PDF locked at the first scan: the first run of a build that knows partner-bank passwords reads it again
 * once (brand mails only), tries the passwords saved on the user's accounts at the brand's partner banks, opens it, and the statement
 * reads as the real bank's account. Made-up data.
 */
class AccountDetectBrandUnlockTest {

    private static final String PASSWORD = "made-up-pw-1";
    private static final String TEXT = String.join("\n",
            "Jupiter Money Savings Account Statement",
            "Statement for the period 01/04/2022 to 20/04/2022",
            "AC# 55550100123456",
            "IFSC: FDRL0005555",
            "Opening Balance 1,000.00",
            "Date Narration Debit Credit Balance",
            "05/04/2022 UPI ACME STORE 200.00 800.00",
            "Closing Balance 800.00");

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private UnlockTestStore store;
    private AccountDetectJobHandler handler;
    private JobExecutionContext ctx;
    private UnlockProfile profile;
    private FixtureMailSource source;
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger sinkCalls = new AtomicInteger();
    private final List<com.financeos.gmail.accountdetect.Detection> detections = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        User user = new User();
        user.setId(userId);
        GmailConnection connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        UnlockProfileLoader profiles = mock(UnlockProfileLoader.class);
        when(profiles.load(userId)).thenAnswer(i -> profile);

        byte[] locked;
        try (PDDocument doc = Loader.loadPDF(FixtureMailSource.pdf(TEXT, false)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", PASSWORD, new AccessPermission());
            p.setEncryptionKeyLength(128);
            doc.protect(p);
            doc.save(out);
            locked = out.toByteArray();
        }
        FixtureMailSource.Mail mail = new FixtureMailSource.Mail("m1", "Jupiter <statements@jupiter.money>", Instant.parse("2022-04-20T04:00:00Z"),
                "Your Jupiter account statement is ready",
                "Hi, your account statement for April 2022 is attached. The statement PDF is password protected. Statement period 01/04/2022 to 20/04/2022.",
                List.of(new FixtureMailSource.Att("att-m1-0", "statement.pdf", true, null)));
        source = new FixtureMailSource(List.of(mail)) {
            @Override
            public Attachments attachments() {
                return (m, a) -> {
                    downloads.incrementAndGet();
                    return locked;
                };
            }
        };
        store = new UnlockTestStore();
        handler = new AccountDetectJobHandler(connections, senders, store, c -> source, new FirstScanGuard(), mock(AccountDetectTriggers.class),
                (u, d, c) -> {
                    sinkCalls.incrementAndGet();
                    detections.add(d);
                }, mock(JdbcTemplate.class), profiles, 12, 50, 120);
        ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId, false));
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
    }

    private AccountDetectFact row() {
        return store.rows(userId).get(0);
    }

    /** What the previous build stored for the mail: the same facts stamped with reader version 2. */
    private void storeAsPreviousBuild() {
        FactsCodec.Facts f = FactsCodec.decode(row().getFacts());
        row().setFacts(FactsCodec.encode(f.derived(), f.statements(), f.bodyFetched(), f.headerVote(), f.incompleteRuns(), StatementPdfReader.BRAND_ONLY_SINCE - 1));
    }

    @Test
    void aLockedBrandPdfStoredByThePreviousBuildIsReadAgainOnceWithThePartnerBanksSavedPassword() throws Exception {
        run();   // the first scan: nothing saved, the PDF stays closed
        assertThat(row().getUnlockState()).isIn(UnlockState.LOCKED, UnlockState.FAILED);
        storeAsPreviousBuild();
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("csb", List.of(PASSWORD)));
        int before = downloads.get();

        run();

        assertThat(downloads.get() - before).isEqualTo(1);   // that PDF, once
        assertThat(row().getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
        assertThat(row().getUnlockRule()).isEqualTo("SAVED");
        List<StatementContent> statements = FactsCodec.decode(row().getFacts()).statements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).bank()).isEqualTo("federal");   // IFSC FDRL: the real bank, not the brand

        run();   // current version now: no second download
        assertThat(downloads.get() - before).isEqualTo(1);
    }

    @Test
    void withoutAPasswordSavedAtAPartnerBankThePdfStaysClosedAndIsStampedSoItIsNotReadAgain() throws Exception {
        run();
        storeAsPreviousBuild();
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        int before = downloads.get();

        run();
        assertThat(downloads.get() - before).isEqualTo(1);
        assertThat(row().getUnlockState()).isNotEqualTo(UnlockState.UNLOCKED);
        run();
        assertThat(downloads.get() - before).isEqualTo(1);
    }

    @Test
    void aClosedPdfOfAnotherBankStoredByThePreviousBuildIsLeftAlone() throws Exception {
        run();
        FactsCodec.Facts f = FactsCodec.decode(row().getFacts());
        MailDerived d = f.derived();
        MailDerived hdfc = new MailDerived("hdfc", d.clusterId(), d.roleVote(), d.date(), d.clusterKind(), d.slots(), d.variants(), d.events(), d.appHits(), d.layout(), d.limits());
        row().setFacts(FactsCodec.encode(hdfc, f.statements(), f.bodyFetched(), f.headerVote(), f.incompleteRuns(), StatementPdfReader.BRAND_ONLY_SINCE - 1));
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("csb", List.of(PASSWORD)));
        int before = downloads.get();

        run();

        assertThat(downloads.get() - before).isZero();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run() throws Exception {
        return (Map<String, Object>) handler.execute(ctx);
    }
}
