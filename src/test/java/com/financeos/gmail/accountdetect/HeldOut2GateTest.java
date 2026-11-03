package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.HeldOutGateTest.Tally;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Held-out gate v2 (src/test/resources/accountdetect/heldout2): 40 mailboxes over 40 issuers, same path and rules as
 * {@link HeldOutGateTest}, plus the limit and add-on fields. dev/: per-field and per-issuer detail; 0 wrong at HIGH and
 * MEDIUM, 0 mustNotReport, exists recall >= 80%. sealed/: the same assertions, reported as counts only.
 */
class HeldOut2GateTest {

    static final Path ROOT = Path.of("src/test/resources/accountdetect/heldout2");

    private static int mustNot(Tally t) {
        return t.counts.getOrDefault("mustNotReport reported at HIGH", 0) + t.counts.getOrDefault("mustNotReport reported at MEDIUM", 0);
    }

    @Test
    void devGate_noWrongHighOrMedium_recallFloor() throws IOException {
        if (!Files.isDirectory(ROOT.resolve("dev"))) return;
        Tally t = HeldOutGateTest.run(ROOT, "dev");
        System.out.println("HELD-OUT v2 dev\n" + HeldOutGateTest.table(t) + HeldOutGateTest.classes(t));
        StringBuilder ir = new StringBuilder("per-issuer exists recall >= MEDIUM:");
        t.issuerRecall.forEach((b, a) -> ir.append(' ').append(b).append('=').append(a[1]).append('/').append(a[0]));
        System.out.println(ir);
        t.wrong.forEach(w -> System.out.println("DEV2 WRONG " + w));
        t.missed.forEach(w -> System.out.println("DEV2 MISSED " + w));
        t.lowWrong.forEach(w -> System.out.println("DEV2 LOW-WRONG " + w));
        for (String f : HeldOutGateTest.FIELDS) {
            for (Conf c : List.of(Conf.HIGH, Conf.MEDIUM)) {
                int[] a = t.prec.getOrDefault(f + "|" + c, new int[2]);
                assertThat(a[1]).as("dev2 " + f + " " + c + " precision").isEqualTo(a[0]);
            }
        }
        assertThat(mustNot(t)).as("dev2 mustNotReport at HIGH/MEDIUM").isZero();
        int[] r = t.recall.getOrDefault("exists", new int[3]);
        assertThat(r[1]).as("dev2 exists recall >= MEDIUM").isGreaterThanOrEqualTo((int) Math.ceil(0.8 * r[0]));
    }

    @Test
    void sealedAggregatesOnly() throws IOException {
        if (!Files.isDirectory(ROOT.resolve("sealed"))) return;
        Tally t = HeldOutGateTest.run(ROOT, "sealed");
        System.out.println("HELD-OUT v2 sealed (aggregates only)\n" + HeldOutGateTest.table(t) + "wrong at HIGH/MEDIUM: " + t.wrong.size() + "\n" + HeldOutGateTest.classes(t));
        // failure messages carry counts only, never which mailbox or number
        assertThat(t.wrong.size()).as("sealed2 wrong facts at HIGH/MEDIUM (count only)").isZero();
        assertThat(mustNot(t)).as("sealed2 mustNotReport at HIGH/MEDIUM (count only)").isZero();
    }
}
