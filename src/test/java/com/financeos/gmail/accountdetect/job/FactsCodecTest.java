package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.FeatureExtractor;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.accountdetect.Stability;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FactsCodecTest {

    @Test
    void roundTripKeepsEveryCachedField() {
        MailDerived m = new MailDerived("hdfc", "a1b2c3d4e5f6", Role.STATEMENT, LocalDate.of(2025, 3, 4), Kind.CREDIT_CARD,
                List.of(new MailDerived.Slot("k1", "1234", LabelKind.CREDIT_CARD), new MailDerived.Slot("k2", "9876", LabelKind.REJECT_PREPAID)),
                List.of(new MailDerived.VariantHit("Regalia Gold", "1234"), new MailDerived.VariantHit("Millennia", null)),
                List.of(new MailDerived.EventHit(EventType.values()[0], List.of("1234", "5678"))),
                List.of(new MailDerived.AppHit("icici", "4321", LabelKind.CARD, "Amazon Pay"), new MailDerived.AppHit("axis", "1111", LabelKind.NONE)),
                MailDerived.TRANSFER | MailDerived.BALANCE,
                List.of(new MailDerived.LimitHit("1234", "3-5L")));
        StatementContent s = new StatementContent("PDF_MAIL", "hdfc", "1234", Kind.CREDIT_CARD, YearMonth.of(2025, 2), "Regalia Gold",
                "1-3L", List.of("2222", "3333"), true, LocalDate.of(2025, 2, 28), "a1b2c3d4e5f6", LocalDate.of(2025, 3, 4));
        StatementContent bare = new StatementContent("PDF_MAIL", "sbi", null, null, null, null, null, List.of(), false);

        FactsCodec.Facts back = FactsCodec.decode(FactsCodec.encode(m, List.of(s, bare), true));

        assertThat(back.derived()).isEqualTo(m);
        assertThat(back.statements()).containsExactly(s, bare);
        assertThat(back.bodyFetched()).isTrue();
        assertThat(FactsCodec.decode(FactsCodec.encode(m, List.of(), false)).bodyFetched()).isFalse();
    }

    @Test
    void rejectedNumberShapesOtherThanPrepaidAreNotStoredLikeTheCache() {
        LabelKind otherReject = null;
        for (LabelKind k : LabelKind.values()) if (k.rejected() && k != LabelKind.REJECT_PREPAID) otherReject = k;
        assertThat(otherReject).isNotNull();
        MailDerived m = new MailDerived("hdfc", "c", Role.TXN_ALERT, null, null,
                List.of(new MailDerived.Slot("k1", "1234", LabelKind.ACCOUNT), new MailDerived.Slot("k2", "0000", otherReject),
                        new MailDerived.Slot("k3", "5555", LabelKind.REJECT_PREPAID)),
                List.of(), List.of(), List.of());

        MailDerived back = FactsCodec.decode(FactsCodec.encode(m, List.of(), false)).derived();

        assertThat(back.slots()).extracting(MailDerived.Slot::last4).containsExactly("1234", "5555");
        assertThat(back.date()).isNull();
        assertThat(back.clusterKind()).isNull();
    }

    /**
     * Every held-out dev mailbox (made-up mail): the detector over facts that went through storage equals the detector
     * over the same mails in memory, when the in-memory features hold what the cache holds.
     */
    @Test
    void detectorOverStoredFactsEqualsTheInMemoryPathOnTheFixtures() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(FixtureMailSource.DEV)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        String salt = FeatureExtractor.newSalt();
        for (Path f : files) {
            List<MailDerived> derived = new ArrayList<>();
            List<List<StatementContent>> perMail = new ArrayList<>();
            for (FixtureMailSource.Mail m : FixtureMailSource.load(f.getFileName().toString())) {
                String snippet = m.body().replaceAll("\\s+", " ").trim();
                if (snippet.length() > 150) snippet = snippet.substring(0, 150);
                List<String> names = m.attachments().stream().map(FixtureMailSource.Att::filename).toList();
                MailDerived md = FeatureExtractor.derive(new MailFeature(FixtureMailSource.domainOf(m.from()), m.date(), m.subject(), snippet, m.body(), names), salt);
                List<StatementContent> sts = new ArrayList<>();
                for (FixtureMailSource.Att a : m.attachments()) {
                    if (!a.filename().toLowerCase(Locale.ROOT).endsWith(".pdf") || a.isProtected() || a.pdfText() == null) continue;
                    StatementPdfReader.Result r = StatementPdfReader.read(FixtureMailSource.pdf(a.pdfText(), false), "PDF_MAIL", md.bank(), YearMonth.from(md.date()));
                    if (r.content() != null) sts.add(r.content().withOrigin(md.clusterId(), md.date()));
                }
                derived.add(md);
                perMail.add(sts);
            }

            List<MailDerived> memMails = new ArrayList<>();
            List<StatementContent> memStatements = new ArrayList<>();
            List<AccountDetectStore.StoredFact> stored = new ArrayList<>();
            for (int i = 0; i < derived.size(); i++) {
                String json = FactsCodec.encode(derived.get(i), perMail.get(i), true);
                stored.add(new AccountDetectStore.StoredFact("m" + i, null, FactsCodec.decode(json)));
                memMails.add(cacheView(derived.get(i)));
                memStatements.addAll(perMail.get(i).stream().map(FactsCodecTest::cacheView).toList());
            }
            Detection fromFacts = AccountDetectJobHandler.detect(stored, List.of()).detection();
            Detection inMemory = Stability.detectStable(memMails, memStatements, AccountDetectJobHandler.STABILITY_SEED).detection();
            assertThat(AccountDetectJobHandlerTest.summary(fromFacts)).as(f.getFileName().toString())
                    .isEqualTo(AccountDetectJobHandlerTest.summary(inMemory));
        }
    }

    /** What the derived-feature cache (and so the facts) keep of a mail: rejected shapes other than prepaid go. */
    private static MailDerived cacheView(MailDerived m) {
        return new MailDerived(m.bank(), m.clusterId(), m.roleVote(), m.date(), m.clusterKind(),
                m.slots().stream().filter(s -> !s.label().rejected() || s.label() == LabelKind.REJECT_PREPAID).toList(),
                m.variants(), m.events(), m.appHits(), m.layout(), m.limits());
    }

    /** ... and of a statement: no printed product phrase. */
    private static StatementContent cacheView(StatementContent s) {
        return new StatementContent(s.source(), s.bank(), s.last4(), s.kind(), s.period(), s.variant(), s.limitBucket(), s.addOnLast4(),
                s.mentionsClosure(), s.periodEnd(), s.originCluster(), s.originDate());
    }
}
