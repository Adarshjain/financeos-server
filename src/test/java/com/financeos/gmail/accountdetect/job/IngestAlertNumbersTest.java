package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.job.IngestAlertNumbers.Row;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestAlertNumbersTest {

    private static final Detector.Options HSBC = Detector.Options.ALL.withKnown(Set.of("hsbc"), Set.of("7106", "9724", "2484", "5754"));

    private static Row row(String id, String bank, String last4, String date, String template) {
        return new Row(id, bank, last4, Instant.parse(date), template);
    }

    @Test
    void hsbcFirstAlertBecomesPossibleCreditCard() {
        Detection d = IngestAlertNumbers.merge(new Detection(), List.of(row("1a1c", "hsbc", "7898", "2026-11-09T10:00:00Z", "hsbc.alert")),
                Map.of("1a1c", Kind.CREDIT_CARD), HSBC);
        assertEquals(1, d.entities.size());
        Detection.Entity e = d.entities.get(0);
        assertEquals("hsbc", e.bank);
        assertEquals("7898", e.last4);
        assertEquals(Kind.CREDIT_CARD, e.kind);
        assertEquals(Conf.LOW, e.existsConf);
        assertEquals(1, e.evidenceMails);
        assertEquals(java.time.LocalDate.of(2026, 11, 9), e.first);
        assertEquals(e.first, e.last);
    }

    @Test
    void ownedNumberAddsNothing() {
        Detection d = IngestAlertNumbers.merge(new Detection(), List.of(row("m", "hsbc", "7106", "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
        assertTrue(d.entities.isEmpty());
    }

    @Test
    void detectorEntityIsKept() {
        Detection det = new Detection();
        Detection.Entity mine = Detection.Entity.of("hsbc", "7898");
        mine.existsConf = Conf.MEDIUM;
        mine.kind = Kind.DEBIT_CARD;
        det.entities.add(mine);
        IngestAlertNumbers.merge(det, List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
        assertEquals(1, det.entities.size());
        assertSame(mine, det.entities.get(0));
        assertEquals(Conf.MEDIUM, mine.existsConf);
        assertEquals(Kind.DEBIT_CARD, mine.kind);
    }

    @Test
    void closedBankNeedsTwoMonths() {
        Detector.Options none = Detector.Options.ALL.withKnown(Set.of(), Set.of());
        Detection one = IngestAlertNumbers.merge(new Detection(), List.of(
                row("a", "federal", "4455", "2026-10-02T10:00:00Z", null), row("b", "federal", "4455", "2026-10-20T10:00:00Z", null)),
                Map.of("a", Kind.CREDIT_CARD, "b", Kind.CREDIT_CARD), none);
        assertTrue(one.entities.isEmpty());
        Detection two = IngestAlertNumbers.merge(new Detection(), List.of(
                row("a", "federal", "4455", "2026-10-02T10:00:00Z", null), row("b", "federal", "4455", "2026-11-03T10:00:00Z", null)),
                Map.of("a", Kind.CREDIT_CARD, "b", Kind.CREDIT_CARD), none);
        assertEquals(1, two.entities.size());
        assertEquals(Conf.LOW, two.entities.get(0).existsConf);
        assertEquals(2, two.entities.get(0).evidenceMails);
    }

    @Test
    void unknownKindWithoutCardTemplateIsSkipped() {
        Detection d = IngestAlertNumbers.merge(new Detection(), List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", "hsbc.upi.debit")), Map.of(), HSBC);
        assertTrue(d.entities.isEmpty());
    }

    @Test
    void unknownKindWithCardTemplateIsCreditCard() {
        Detection d = IngestAlertNumbers.merge(new Detection(), List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", "hsbc.cc.spend")), Map.of(), HSBC);
        assertEquals(Kind.CREDIT_CARD, d.entities.get(0).kind);
    }

    @Test
    void placeholderIsSkipped() {
        for (String p : List.of("1234", "0000", "4321")) {
            Detection d = IngestAlertNumbers.merge(new Detection(), List.of(row("m", "hsbc", p, "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
            assertTrue(d.entities.isEmpty(), p);
        }
    }

    @Test
    void majorityKindWins() {
        Detection d = IngestAlertNumbers.merge(new Detection(), List.of(
                row("a", "hsbc", "7898", "2026-11-01T10:00:00Z", null), row("b", "hsbc", "7898", "2026-11-02T10:00:00Z", null), row("c", "hsbc", "7898", "2026-11-03T10:00:00Z", null)),
                Map.of("a", Kind.DEBIT_CARD, "b", Kind.CREDIT_CARD, "c", Kind.CREDIT_CARD), HSBC);
        assertEquals(Kind.CREDIT_CARD, d.entities.get(0).kind);
        assertNull(IngestAlertNumbers.kindOf(List.of(row("z", "hsbc", "7898", "2026-11-01T10:00:00Z", "x")), Map.of()));
    }

    @Test
    void reuseKeyChangesWithTheUnresolvedSet() {
        List<Row> a = List.of(row("a", "hsbc", "7898", "2026-11-01T10:00:00Z", null));
        List<Row> b = List.of(row("a", "hsbc", "7898", "2026-11-01T10:00:00Z", null), row("b", "hsbc", "1357", "2026-11-02T10:00:00Z", null));
        assertNotEquals(IngestAlertNumbers.fingerprint(List.of()), IngestAlertNumbers.fingerprint(a));
        assertNotEquals(IngestAlertNumbers.fingerprint(a), IngestAlertNumbers.fingerprint(b));
        assertEquals(IngestAlertNumbers.fingerprint(b), IngestAlertNumbers.fingerprint(List.of(b.get(1), b.get(0))));
    }

    @Test
    void secondMonthAlertChangesTheReuseKey() {
        Row first = row("a", "hsbc", "7898", "2026-10-01T10:00:00Z", null);
        Row second = row("b", "hsbc", "7898", "2026-11-01T10:00:00Z", null);
        assertNotEquals(IngestAlertNumbers.fingerprint(List.of(first)), IngestAlertNumbers.fingerprint(List.of(first, second)));
    }

    @Test
    void solidNumberAtAnotherBankIsSkipped() {
        Detection det = new Detection();
        Detection.Entity other = Detection.Entity.of("axis", "7898");
        other.existsConf = Conf.HIGH;
        det.entities.add(other);
        IngestAlertNumbers.merge(det, List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
        assertEquals(1, det.entities.size());
        Detection det2 = new Detection();
        Detection.Entity documented = Detection.Entity.of("axis", "7898");
        documented.existsConf = Conf.LOW;
        documented.headerStatements = 1;
        det2.entities.add(documented);
        IngestAlertNumbers.merge(det2, List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
        assertEquals(1, det2.entities.size());
        Detection det3 = new Detection();
        Detection.Entity weak = Detection.Entity.of("axis", "7898");
        weak.existsConf = Conf.LOW;
        det3.entities.add(weak);
        IngestAlertNumbers.merge(det3, List.of(row("m", "hsbc", "7898", "2026-11-09T10:00:00Z", null)), Map.of("m", Kind.CREDIT_CARD), HSBC);
        assertEquals(2, det3.entities.size());
    }
}
