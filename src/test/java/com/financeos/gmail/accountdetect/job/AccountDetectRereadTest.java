package com.financeos.gmail.accountdetect.job;

import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stored PDF facts written by an older reader version are read again by the next account-detect run: statements replaced,
 * the current version recorded, detection rerun and the sink called once; a PDF that cannot be fetched keeps its facts and is
 * not tried again until the reader changes again.
 */
class AccountDetectRereadTest {

    private static final String BOX = "hdfc-regalia-solo.json";
    private static final String CARD_TEXT = String.join("\n",
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card Member: RIYA SHARMA",
            "Card No: XXXX XXXX XXXX 7708",
            "Statement Date: 12/06/2025",
            "Credit Limit: Rs. 4,50,000.00",
            "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00");
    private static final String LOAN_TEXT = String.join("\n",
            "Name : SAMPLE NAME A",
            "Statement Date:20/03/2019     Loan No: 0036 0825 XXXX 7708",
            "Payment Due Date Total Dues Loan Amount",
            "05/04/2019 4,133.16 23,681",
            "Account Summary",
            "Transaction Information",
            "Terms and Condition Customer Rights Policy",
            "For HDFC Bank",
            "DEBIT CARD EMI Loan Statement");

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private UnlockTestStore store;
    private FixtureMailSource source;
    private String pdfText = CARD_TEXT;
    private static final String TERMS_TEXT = "HDFC Bank\nTerms and Conditions\nMost Important Terms and Conditions";
    private static final String PASSWORD = "RIYA1408";
    /** The mail's first attachment is a T&C PDF (id ending -tc), the statement second. */
    /** Statement PDFs are protected with {@link #PASSWORD}. */
    private boolean protectedPdfs;
    private UnlockProfile profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
    private boolean failFullWithAuth;
    private final List<Detection> detections = new ArrayList<>();
    private AccountDetectJobHandler handler;
    private JobExecutionContext ctx;

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
        store = new UnlockTestStore();
        source = new FixtureMailSource(FixtureMailSource.load(BOX)) {
            private byte[] render(String attachmentId) {
                try {
                    boolean terms = attachmentId.endsWith("-tc");
                    byte[] plain = FixtureMailSource.pdf(terms ? TERMS_TEXT : pdfText, false);
                    if (terms || !protectedPdfs) return plain;
                    try (PDDocument doc = Loader.loadPDF(plain); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                        StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", PASSWORD, new AccessPermission());
                        p.setEncryptionKeyLength(128);
                        doc.protect(p);
                        doc.save(out);
                        return out.toByteArray();
                    }
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Map<String, com.financeos.gmail.internal.GmailMessage> full(List<String> ids, java.util.function.Consumer<String> out) {
                if (failFullWithAuth && !ids.isEmpty()) throw new GmailEngineException(GmailError.AUTH_ERROR, "Google rejected authentication (401)");
                Map<String, com.financeos.gmail.internal.GmailMessage> r = super.full(ids, out);
                return r;
            }

            @Override
            public Attachments attachments() {
                Attachments base = super.attachments();
                return (messageId, attachmentId) -> {
                    byte[] plain = base.get(messageId, attachmentId);   // records the request, honours failAttachment
                    return plain == null ? null : render(attachmentId);
                };
            }
        };
        UnlockProfileLoader profiles = mock(UnlockProfileLoader.class);
        when(profiles.load(userId)).thenAnswer(i -> profile);
        handler = new AccountDetectJobHandler(connections, senders, store, c -> source, new FirstScanGuard(), mock(AccountDetectTriggers.class),
                (u, d, c) -> detections.add(d), mock(JdbcTemplate.class), profiles, 12, 50, 120);
        ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId, false));
    }

    /** Every statement mail gets a T&C PDF in front of its statement PDF. */
    private void addTermsPdfs() {
        source.mails.replaceAll(m -> {
            List<FixtureMailSource.Att> atts = new ArrayList<>(m.attachments());
            if (!atts.isEmpty()) atts.add(0, new FixtureMailSource.Att("att-" + m.id() + "-tc", "Terms.pdf", false, null));
            return new FixtureMailSource.Mail(m.id(), m.from(), m.date(), m.subject(), m.body(), atts);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run() throws Exception {
        return (Map<String, Object>) handler.execute(ctx);
    }

    private List<AccountDetectFact> pdfRows() {
        return store.rows(userId).stream().filter(f -> Boolean.TRUE.equals(f.getHasPdf()) && f.getPdfAttachmentId() != null).toList();
    }

    /** What an older reader stored for these mails: a credit card 7708, version 0. */
    private void storeAsOlderReader() {
        for (AccountDetectFact f : pdfRows()) {
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            StatementContent old = new StatementContent("PDF_MAIL", "hdfc", "7708", Kind.CREDIT_CARD, YearMonth.of(2019, 3), null, null, List.of(), false,
                    null, facts.derived().clusterId(), facts.derived().date(), null, null, "SAVED", "Y");
            f.setFacts(FactsCodec.encode(facts.derived(), List.of(old), facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns(), 0));
        }
    }

    private List<StatementContent> statements() {
        return store.facts(userId).stream().flatMap(f -> f.facts().statements().stream()).toList();
    }

    private long oldOnes() {
        return statements().stream().filter(s -> "7708".equals(s.last4())).count();
    }

    @Test
    void aFactStoredByAnOlderReaderIsReadAgainEvenWhenNothingIsNew() throws Exception {
        run();
        assertThat(pdfRows()).isNotEmpty();
        storeAsOlderReader();
        assertThat(statements()).isNotEmpty().allSatisfy(s -> assertThat(s.last4()).isEqualTo("7708"));
        pdfText = LOAN_TEXT;   // what the PDF reads as now
        source.attachmentRequested.clear();
        int sinkBefore = detections.size();

        Map<String, Object> result = run();   // no new mail: the "nothing new" shortcut must not skip the re-read

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(result.get("detection")).isNull();
        assertThat(statements()).isNotEmpty().allSatisfy(s -> {   // replaced: the loan document is no card
            assertThat(s.kind()).isNull();
            assertThat(s.last4()).isNull();
        });
        assertThat(store.rows(userId)).allSatisfy(f -> assertThat(FactsCodec.decode(f.getFacts()).readerVersion()).isEqualTo(StatementPdfReader.READER_VERSION));
        assertThat(source.attachmentRequested).hasSize(pdfRows().size());
        assertThat(detections).hasSize(sinkBefore + 1);   // detection rerun, sink called once
        assertThat(detections.get(sinkBefore).find("hdfc", "7708")).isNull();   // so the old suggestion is no longer reported

        source.attachmentRequested.clear();
        int after = detections.size();
        run();   // current version now: nothing is fetched again
        assertThat(source.attachmentRequested).isEmpty();
        assertThat(detections).hasSize(after + 1);   // the plain reused-detection path still calls the sink
    }

    private void markStale() {
        for (AccountDetectFact f : pdfRows()) {
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            f.setFacts(FactsCodec.encode(facts.derived(), facts.statements(), facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns(), 0));
        }
    }

    private long staleRows() {
        return store.rows(userId).stream().filter(f -> FactsCodec.decode(f.getFacts()).readerStale()).count();
    }

    @Test
    void severalFailedFetchesInARowStopThePassWithNothingStampedAndTheNextRunTriesAgain() throws Exception {
        run();
        storeAsOlderReader();
        pdfText = LOAN_TEXT;
        source.mails.forEach(m -> source.failAttachment.add(m.id()));
        int sinkBefore = detections.size();
        source.attachmentRequested.clear();

        run();

        assertThat(statements()).isNotEmpty().allSatisfy(s -> assertThat(s.last4()).isEqualTo("7708"));   // kept
        assertThat(staleRows()).isEqualTo(pdfRows().size());   // none stamped
        assertThat(source.attachmentRequested).hasSize(AccountDetectJobHandler.REREAD_STOP_AFTER_NULLS);   // it stopped
        assertThat(detections).hasSize(sinkBefore + 1);   // only the reused-detection sink call

        source.failAttachment.clear();
        run();
        assertThat(oldOnes()).isZero();
        assertThat(staleRows()).isZero();
    }

    @Test
    void anIsolatedFailedFetchKeepsItsOldFactsAndIsStampedWhileTheOthersAreRead() throws Exception {
        run();
        storeAsOlderReader();
        pdfText = LOAN_TEXT;
        String gone = pdfRows().stream().max(java.util.Comparator.comparing(AccountDetectFact::getInternalDate)).get().getMessageId();   // newest: first in the pass
        source.failAttachment.add(gone);

        run();

        List<AccountDetectFact> rows = pdfRows();
        assertThat(staleRows()).isZero();
        for (AccountDetectFact f : rows) {
            List<StatementContent> ss = FactsCodec.decode(f.getFacts()).statements();
            if (f.getMessageId().equals(gone)) assertThat(ss).allSatisfy(s -> assertThat(s.last4()).isEqualTo("7708"));
            else assertThat(ss).allSatisfy(s -> assertThat(s.last4()).isNull());
        }
        source.failAttachment.clear();
        source.attachmentRequested.clear();
        run();
        assertThat(source.attachmentRequested).isEmpty();   // the gone one is not retried every run
    }

    @Test
    void aMailWithATermsPdfAndAStatementPdfKeepsTheTermsReadingAndRefreshesTheStatement() throws Exception {
        addTermsPdfs();
        run();   // the scan reads both PDFs of each mail
        int perMail = statements().size() / pdfRows().size();
        assertThat(perMail).isEqualTo(2);
        assertThat(statements().stream().filter(s -> "7708".equals(s.last4())).count()).isEqualTo(pdfRows().size());
        markStale();
        pdfText = CARD_TEXT.replace("7708", "4721");   // what the statement PDF reads as now

        run();

        assertThat(statements()).hasSize(2 * pdfRows().size());
        assertThat(statements().stream().filter(s -> "4721".equals(s.last4())).count()).isEqualTo(pdfRows().size());
        assertThat(statements().stream().filter(s -> "7708".equals(s.last4()))).isEmpty();
        assertThat(statements().stream().filter(s -> s.kind() == null).count()).isEqualTo(pdfRows().size());   // the T&C reading stayed
        assertThat(staleRows()).isZero();
    }

    @Test
    void aProtectedPdfIsOpenedAgainWithTheSavedPasswordAndItsStateAndRuleAreSet() throws Exception {
        protectedPdfs = true;
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        run();
        assertThat(pdfRows()).isNotEmpty().allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(AccountDetectFact.UnlockState.UNLOCKED));
        for (AccountDetectFact f : pdfRows()) f.setUnlockRule(null);
        storeAsOlderReader();
        pdfText = LOAN_TEXT;

        run();

        assertThat(pdfRows()).allSatisfy(f -> {
            assertThat(f.getUnlockState()).isEqualTo(AccountDetectFact.UnlockState.UNLOCKED);
            assertThat(f.getUnlockRule()).isEqualTo("SAVED");
            assertThat(FactsCodec.decode(f.getFacts()).readerStale()).isFalse();
        });
        assertThat(oldOnes()).isZero();
        assertThat(statements()).isNotEmpty().allSatisfy(s -> assertThat(s.unlockRule()).isEqualTo("SAVED"));
    }

    @Test
    void aProtectedPdfThatCannotBeOpenedNowKeepsItsOldStatements() throws Exception {
        protectedPdfs = true;
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        run();
        storeAsOlderReader();
        profile = UnlockProfile.of(null, null);   // no password to try now
        pdfText = LOAN_TEXT;

        run();

        assertThat(oldOnes()).isEqualTo(pdfRows().size());
        assertThat(staleRows()).isZero();
    }

    @Test
    void aGmailAuthErrorStopsThePassWithNothingChangedAndTheRunStillFinishes() throws Exception {
        run();
        storeAsOlderReader();
        pdfText = LOAN_TEXT;
        failFullWithAuth = true;

        assertThat(run().get("status")).isEqualTo("DONE");

        assertThat(oldOnes()).isEqualTo(pdfRows().size());
        assertThat(staleRows()).isEqualTo(pdfRows().size());   // nothing stamped: the next run tries again
    }

    @Test
    void aRunReadsAtMostTheCapAndTheNextRunTakesTheRest() throws Exception {
        run();
        storeAsOlderReader();
        pdfText = LOAN_TEXT;
        int stale = pdfRows().size();
        assertThat(stale).isGreaterThan(1);
        handler.retryCap = 1;

        run();
        assertThat(oldOnes()).isEqualTo(stale - 1);
        run();
        assertThat(oldOnes()).isEqualTo(stale - 2);
        handler.retryCap = 200;
        run();
        assertThat(oldOnes()).isZero();
    }
}
