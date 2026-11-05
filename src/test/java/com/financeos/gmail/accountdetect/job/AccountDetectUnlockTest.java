package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState;
import com.financeos.gmail.accountdetect.scan.MailSource;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A scan where the statement PDFs are protected with a made-up password (RIYA1408: first four letters of the name in
 * capitals + DDMM of the birth date), the mail names that rule, and the unlock retry after the profile changes.
 */
class AccountDetectUnlockTest {

    private static final String BOX = "hdfc-regalia-solo.json";
    private static final String PASSWORD = "RIYA1408";
    private static final String STATEMENT_TEXT = String.join("\n",
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card Member: RIYA SHARMA",
            "Card No: XXXX XXXX XXXX 4721",
            "Statement Date: 12/06/2025",
            "Credit Limit: Rs. 4,50,000.00",
            "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00");

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private GmailConnection connection;
    private UnlockTestStore store;
    private ProtectedSource source;
    private UnlockProfile profile;
    private final List<Detection> detections = new ArrayList<>();
    private final List<DetectionContext> contexts = new ArrayList<>();
    private AccountDetectJobHandler handler;
    private JobExecutionContext ctx;
    private int opened;
    private GmailConnectionRepository connections;
    private GmailSenderRepository senders;
    private UnlockProfileLoader profiles;

    /** The box's statement mails carry the password sentence and a PDF protected with {@link #PASSWORD}. */
    static class ProtectedSource extends FixtureMailSource {
        ProtectedSource(List<Mail> mails) {
            super(mails);
        }

        /** Each statement carries its own month (the mail's date), so statements are distinct periods. */
        private String textFor(String messageId) {
            return mails.stream().filter(m -> m.id().equals(messageId)).findFirst()
                    .map(m -> STATEMENT_TEXT.replace("12/06/2025", java.time.LocalDate.ofInstant(m.date(), java.time.ZoneId.of("Asia/Kolkata"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                    .orElse(STATEMENT_TEXT);
        }

        @Override
        public Attachments attachments() {
            Attachments base = super.attachments();
            return (messageId, attachmentId) -> {
                attachmentRequested.add(attachmentId);
                byte[] plain = base.get(messageId, attachmentId);
                if (plain == null) return null;
                try (PDDocument doc = Loader.loadPDF(FixtureMailSource.pdf(textFor(messageId), false)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", PASSWORD, new AccessPermission());
                    p.setEncryptionKeyLength(128);
                    doc.protect(p);
                    doc.save(out);
                    return out.toByteArray();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
    }

    /** The box's statement mails already say "Password: first 4 letters of your name in CAPITALS followed by DDMM ..." */
    private static ProtectedSource source() throws Exception {
        return new ProtectedSource(FixtureMailSource.load(BOX));
    }

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        connections = mock(GmailConnectionRepository.class);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        store = new UnlockTestStore();
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        profiles = mock(UnlockProfileLoader.class);
        when(profiles.load(userId)).thenAnswer(i -> profile);
        MailSourceFactory factory = c -> {
            opened++;
            return source;
        };
        DetectionSink sink = (u, d, c) -> {
            detections.add(d);
            contexts.add(c);
        };
        handler = new AccountDetectJobHandler(connections, senders, store, factory, new FirstScanGuard(), mock(AccountDetectTriggers.class), sink,
                mock(JdbcTemplate.class), profiles, 12, 50, 120);
        ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(boolean retry) throws Exception {
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId, retry));
        return (Map<String, Object>) handler.execute(ctx);
    }

    private List<AccountDetectFact> statementRows() {
        return store.rows(userId).stream().filter(f -> Boolean.TRUE.equals(f.getPdfLocked())).toList();
    }

    private Detection.Entity regalia() {
        Detection d = detections.get(detections.size() - 1);
        return d.find("hdfc", "4721");
    }

    @Test
    void aLockedStatementOpensWithTheMailsRuleAndItsExactLimitAndVariantReachTheDetection() throws Exception {
        source = source();

        assertThat(run(false).get("status")).isEqualTo("DONE");

        List<AccountDetectFact> rows = statementRows();
        assertThat(rows).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
            assertThat(f.getUnlockRule()).isEqualTo("NAME4U+DDMM");
            assertThat(f.getUnlockHint()).isEqualTo("NAME4U+DDMM");
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            assertThat(facts.statements()).hasSize(1);
            assertThat(facts.statements().get(0).limitAmount()).isEqualByComparingTo(new BigDecimal("450000.00"));
            assertThat(facts.statements().get(0).unlockRule()).isEqualTo("NAME4U+DDMM");
        });
        Detection.Entity e = regalia();
        assertThat(e).isNotNull();
        assertThat(e.variant).isEqualTo("Regalia Gold");
        assertThat(e.limitBucket).isEqualTo("3-5L");
        assertThat(e.limitConf).isNotNull();
        assertThat(e.limitConf.atLeast(Conf.MEDIUM)).isTrue();
        assertThat(e.limitAmount).isEqualByComparingTo(new BigDecimal("450000.00"));
        assertThat(store.facts(userId).stream().flatMap(f -> f.facts().statements().stream()).toList())
                .allSatisfy(s -> assertThat(s.unlockRule()).isEqualTo("NAME4U+DDMM"));
    }

    @Test
    void withoutAProfileTheStatementsStayLockedAndOnlyTheHintIsStored() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);

        run(false);

        assertThat(statementRows()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getUnlockState()).isEqualTo(UnlockState.LOCKED);
            assertThat(f.getUnlockRule()).isNull();
            assertThat(f.getUnlockHint()).isEqualTo("NAME4U+DDMM");   // tokens only, never the sentence
            assertThat(FactsCodec.decode(f.getFacts()).statements()).isEmpty();
        });
        assertThat(regalia() == null || regalia().limitAmount == null).isTrue();
    }

    @Test
    void aWrongProfileMarksTheStatementsFailed() throws Exception {
        source = source();
        profile = UnlockProfile.of("Zoya Khan", "1990-01-02");

        run(false);

        assertThat(statementRows()).isNotEmpty().allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.FAILED));
    }

    @Test
    void theRetryOpensWhatWasLockedAndRerunsDetectionWithoutRescanning() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        int listedBefore = source.listedAfter.size();
        int detectionsBefore = detections.size();
        Set<String> lockedAttachments = statementRows().stream().map(AccountDetectFact::getPdfAttachmentId).collect(Collectors.toSet());
        source.attachmentRequested.clear();
        source.fullRequested.clear();
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");

        store.requestUnlockRetry(userId, false);
        Map<String, Object> result = run(true);

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).hasSize(listedBefore);   // no listing, no new mail
        assertThat(source.fullRequested).isEmpty();
        assertThat(Set.copyOf(source.attachmentRequested)).isEqualTo(lockedAttachments);   // only the locked PDFs were fetched
        assertThat(statementRows()).allSatisfy(f -> {
            assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
            assertThat(f.getUnlockRule()).isEqualTo("NAME4U+DDMM");
        });
        assertThat(detections).hasSize(detectionsBefore + 1);
        DetectionContext c = contexts.get(contexts.size() - 1);
        assertThat(c.firstScan()).isFalse();
        assertThat(c.firstScanCompleted()).isFalse();
        assertThat(c.newMails()).isZero();
        assertThat(regalia().limitAmount).isEqualByComparingTo(new BigDecimal("450000.00"));
        assertThat(regalia().variant).isEqualTo("Regalia Gold");

        // nothing is locked now: a second retry does nothing and does not call the sink again
        int after = detections.size();
        store.requestUnlockRetry(userId, false);
        assertThat(run(true).get("status")).isEqualTo("DONE");
        assertThat(detections).hasSize(after);
    }

    @Test
    void aRetryThatChangedFactsClearsTheCachedDetectionSoTheNextScanDetectsInFull() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        assertThat(run(false).get("detection")).isEqualTo("SKIPPED_NOTHING_NEW");   // cached, nothing new
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);
        run(true);                                                                   // opens the PDFs: facts changed
        int before = detections.size();

        Map<String, Object> next = run(false);

        assertThat(next.get("detection")).isNull();                                  // full detection, not the stale cache
        assertThat(detections).hasSize(before + 1);
        assertThat(detections.get(before)).isNotSameAs(detections.get(before - 1));
    }

    @Test
    void aRetryAskedForIsHonouredByTheEndOfANormalScanEvenWhenItsOwnJobWasDropped() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.LOCKED));
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);   // what the trigger does before enqueueing
        int detectionsBefore = detections.size();

        run(false);   // a plain scan payload

        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED));
        assertThat(store.retryPending(userId)).isFalse();
        assertThat(detections.size()).isGreaterThan(detectionsBefore);
        assertThat(regalia().limitAmount).isEqualByComparingTo(new BigDecimal("450000.00"));
    }

    @Test
    void aRequestArrivingDuringTheRetryPassIsHonouredByTheSecondCheck() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);
        UnlockTestStore racing = store;
        int[] sinkCalls = {0};
        // the sink runs inside the retry pass, after the flag was cleared: a new request arrives then
        handler = new AccountDetectJobHandler(connections, senders, store, c -> source, new FirstScanGuard(), mock(AccountDetectTriggers.class),
                (u, d, c) -> {
                    sinkCalls[0]++;
                    racing.requestUnlockRetry(u, false);
                }, mock(JdbcTemplate.class), profiles, 12, 50, 120);

        run(false);

        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED));
        // the request set meanwhile was taken by the second pass (nothing left to do there), not lost and not looping
        assertThat(store.retryPending(userId)).isFalse();
    }

    @Test
    void aCappedPassLeavesTheRestPendingForTheNextRun() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        long locked = statementRows().size();
        assertThat(locked).isGreaterThan(2);
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        handler.retryCap = 2;
        store.requestUnlockRetry(userId, false);

        run(false);

        assertThat(statementRows().stream().filter(f -> f.getUnlockState() == UnlockState.UNLOCKED).count()).isEqualTo(2);
        assertThat(store.retryPending(userId)).isTrue();
        // the newest two were taken first
        List<AccountDetectFact> byDate = statementRows().stream().sorted(java.util.Comparator.comparing(AccountDetectFact::getInternalDate).reversed()).toList();
        assertThat(byDate.get(0).getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
        assertThat(byDate.get(1).getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
        assertThat(byDate.get(2).getUnlockState()).isEqualTo(UnlockState.LOCKED);

        handler.retryCap = 200;
        run(false);   // the next run finishes the rest
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED));
        assertThat(store.retryPending(userId)).isFalse();
    }

    @Test
    void aFailingRetryAfterAFinishedScanLeavesItPendingAndNeverFailsTheJob() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);
        int[] opens = {0};
        handler = new AccountDetectJobHandler(connections, senders, store, c -> {
            if (++opens[0] > 1) throw new IllegalStateException("mail source down");   // the scan opens once; the retry fails
            return source;
        }, new FirstScanGuard(), mock(AccountDetectTriggers.class), (u, d, c) -> { }, mock(JdbcTemplate.class), profiles, 12, 50, 120);

        assertThat(run(false).get("status")).isEqualTo("DONE");

        assertThat(store.retryPending(userId)).isTrue();
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.LOCKED));
    }

    @Test
    void noRetryRunsWhileGmailAccessIsExpired() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);
        connection.setAuthFailedAt(java.time.Instant.now());
        int openedBefore = opened;

        assertThat(run(true).get("status")).isEqualTo("SKIPPED_PAUSED_AUTH");

        assertThat(opened).isEqualTo(openedBefore);
        assertThat(store.retryPending(userId)).isTrue();   // kept for when access returns
    }

    // ---- holder-name check

    private List<String> holders() {
        return store.facts(userId).stream().flatMap(f -> f.facts().statements().stream()).map(c -> c.holder() == null ? "null" : c.holder()).distinct().toList();
    }

    @Test
    void theHolderFlagIsYWhenTheNameIsOnTheFirstPageNWhenItIsNotAndNullWithoutAName() throws Exception {
        source = source();
        run(false);
        assertThat(holders()).containsExactly("Y");   // Riya Sharma is on the statement ("Card Member: RIYA SHARMA")

        store.rows.clear();
        store.states.clear();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));   // opens by a saved password
        run(false);
        assertThat(holders()).containsExactly("N");

        store.rows.clear();
        store.states.clear();
        profile = new UnlockProfile(null, null, Map.of("hdfc", List.of(PASSWORD)));
        run(false);
        assertThat(holders()).containsExactly("null");
        assertThat(store.rows(userId).toString()).doesNotContain("Zoya").doesNotContain("Riya");
    }

    @Test
    void statementsNotInTheUsersNameMakeTheEntityNotInYourName() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));

        run(false);

        assertThat(regalia()).isNotNull();
        assertThat(regalia().notInYourName).isTrue();
    }

    @Test
    void aNameChangeRecomputesTheHolderFlagsButABirthDateChangeDoesNot() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));
        run(false);
        assertThat(holders()).containsExactly("N");
        assertThat(regalia().notInYourName).isTrue();

        // birth date only: the trigger sets just the retry flag; the holder flags are left alone
        profile = new UnlockProfile("Zoya Khan", "1991-03-04", Map.of("hdfc", List.of(PASSWORD)));
        store.requestUnlockRetry(userId, false);
        source.attachmentRequested.clear();
        run(false);
        assertThat(holders()).containsExactly("N");
        assertThat(source.attachmentRequested).isEmpty();

        // the user's name is corrected: PDFs are fetched again and the flags recomputed, then detection reruns
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        store.requestUnlockRetry(userId, true);
        int detectionsBefore = detections.size();
        run(false);
        assertThat(holders()).containsExactly("Y");
        assertThat(source.attachmentRequested).isNotEmpty();
        assertThat(detections.size()).isGreaterThan(detectionsBefore);
        assertThat(regalia().notInYourName).isFalse();
        assertThat(store.retryPending(userId)).isFalse();
    }

    @Test
    void theRetryTriesFailedStatementsAgainAndAFailureStaysFailed() throws Exception {
        source = source();
        profile = UnlockProfile.of("Zoya Khan", "1990-01-02");
        run(false);
        int detectionsBefore = detections.size();

        store.requestUnlockRetry(userId, false);
        run(true);   // still wrong: nothing opens, no new detection
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.FAILED));
        assertThat(detections).hasSize(detectionsBefore);

        profile = UnlockProfile.of("Riya Sharma", "1994-08-14");
        store.requestUnlockRetry(userId, false);
        run(true);
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED));
        assertThat(detections).hasSize(detectionsBefore + 1);
    }

    @Test
    void aSavedStatementPasswordOpensItWhenTheHintCannotRenderAndIsRecordedAsSaved() throws Exception {
        source = source();
        profile = new UnlockProfile(null, null, Map.of("hdfc", List.of(PASSWORD)));

        run(false);

        assertThat(statementRows()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
            assertThat(f.getUnlockRule()).isEqualTo("SAVED");
            assertThat(f.getUnlockHint()).isEqualTo("NAME4U+DDMM");   // the hint cannot render without a profile
        });
        assertThat(regalia().limitAmount).isEqualByComparingTo(new BigDecimal("450000.00"));
    }

    @Test
    void theRetryDoesNothingWhenGmailIsNotConnectedOrNothingWasScanned() throws Exception {
        source = source();
        assertThat(run(true).get("status")).isEqualTo("SKIPPED_NO_SCAN");   // never scanned
        assertThat(opened).isZero();

        run(false);
        int openedAfterScan = opened;
        connection.setIsConnected(false);
        assertThat(run(true).get("status")).isEqualTo("SKIPPED_DISCONNECTED");
        assertThat(opened).isEqualTo(openedAfterScan);
    }

    @Test
    void anOldPayloadWithoutTheFlagStillDeserialisesAsAScan() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AccountDetectPayload old = mapper.readValue("{\"connectionId\":\"" + connectionId + "\"}", AccountDetectPayload.class);
        assertThat(old.unlockRetry()).isFalse();
        assertThat(old.connectionId()).isEqualTo(connectionId);
        assertThat(mapper.readValue("{\"connectionId\":\"" + connectionId + "\",\"unlockRetry\":true}", AccountDetectPayload.class).unlockRetry()).isTrue();
    }

    // ---- the retry cursor

    @Test
    void theCursorMovesTheRetryPastTheCapAcrossRunsWithoutRevisitingFacts() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of());   // tries a few passwords, none opens: FAILED, stays eligible
        run(false);
        Set<String> all = statementRows().stream().map(AccountDetectFact::getPdfAttachmentId).collect(Collectors.toSet());
        assertThat(all.size()).isGreaterThan(4);
        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.FAILED));
        handler.retryCap = 2;
        store.requestUnlockRetry(userId, false);

        Set<String> seen = new java.util.HashSet<>();
        int runs = 0;
        while (store.retryPending(userId) && runs++ < 50) {
            source.attachmentRequested.clear();
            run(false);
            Set<String> thisRun = Set.copyOf(source.attachmentRequested);
            assertThat(thisRun.size()).isLessThanOrEqualTo(2);   // one capped pass per run
            assertThat(seen).doesNotContainAnyElementsOf(thisRun);   // never back to a fact already tried at this generation
            seen.addAll(thisRun);
        }

        assertThat(seen).isEqualTo(all);
        assertThat(store.retryPending(userId)).isFalse();
        // a new request starts the sweep again
        store.requestUnlockRetry(userId, false);
        assertThat(store.retryPending(userId)).isTrue();
    }

    @Test
    void theHolderRecheckHasItsOwnBudgetNextToTheUnlockOne() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));
        run(false);   // all open by the saved password, holder N
        List<AccountDetectFact> byDate = statementRows().stream().sorted(java.util.Comparator.comparing(AccountDetectFact::getInternalDate)).toList();
        assertThat(byDate.size()).isGreaterThan(5);
        byDate.get(0).setUnlockState(UnlockState.LOCKED);   // two old ones are still closed
        byDate.get(1).setUnlockState(UnlockState.LOCKED);
        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        handler.retryCap = 2;
        store.requestUnlockRetry(userId, true);
        source.attachmentRequested.clear();

        run(false);

        // 2 unlock tries + 2 holder rechecks in one pass: 4 PDFs, not 2
        assertThat(Set.copyOf(source.attachmentRequested)).hasSize(4);
        assertThat(byDate.get(0).getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
        assertThat(byDate.get(1).getUnlockState()).isEqualTo(UnlockState.UNLOCKED);
        assertThat(store.retryPending(userId)).isTrue();   // holder rechecks remain
    }

    // ---- stale holder flags and per-PDF failures

    @Test
    void aNameWithNoUsableWordsClearsTheStoredHolderFlagsWithoutFetchingAnything() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));
        run(false);
        assertThat(holders()).containsExactly("N");
        assertThat(regalia().notInYourName).isTrue();

        profile = new UnlockProfile("Al", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));   // no word of 3 letters
        store.requestUnlockRetry(userId, true);
        source.attachmentRequested.clear();
        run(false);

        assertThat(holders()).containsExactly("null");
        assertThat(source.attachmentRequested).isEmpty();
        assertThat(regalia().notInYourName).isFalse();
        assertThat(store.retryPending(userId)).isFalse();
    }

    @Test
    void aPdfThatCannotBeReadAnyMoreLeavesItsHolderUnknownNotStale() throws Exception {
        source = source();
        profile = new UnlockProfile("Zoya Khan", "1990-01-02", Map.of("hdfc", List.of(PASSWORD)));
        run(false);
        assertThat(holders()).containsExactly("N");
        source.failAttachment.addAll(statementRows().stream().map(AccountDetectFact::getMessageId).toList());   // the mails' PDFs are gone

        profile = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of(PASSWORD)));
        store.requestUnlockRetry(userId, true);
        run(false);

        assertThat(holders()).containsExactly("null");
        assertThat(store.retryPending(userId)).isFalse();
    }

    @Test
    void anUnlockerThatThrowsOnOnePdfNeverStopsThePassAndTheFactIsStampedTried() throws Exception {
        source = source();
        profile = UnlockProfile.of(null, null);
        run(false);
        handler.unlocker = new com.financeos.gmail.accountdetect.unlock.StatementUnlocker(com.financeos.gmail.accountdetect.unlock.StatementPasswordRules.bundled()) {
            @Override
            public Outcome unlock(byte[] pdf, Target target, UnlockProfile p) {
                throw new IllegalStateException("made-up failure");
            }
        };
        store.requestUnlockRetry(userId, false);

        assertThat(run(false).get("status")).isEqualTo("DONE");

        assertThat(statementRows()).allSatisfy(f -> assertThat(f.getUnlockState()).isEqualTo(UnlockState.LOCKED));
        assertThat(store.retryPending(userId)).isFalse();   // every fact was stamped, none loops
    }
}
