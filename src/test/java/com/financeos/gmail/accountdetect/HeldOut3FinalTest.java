package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.HeldOutGateTest.Tally;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final honest number on a fresh sealed set (src/test/resources/accountdetect/heldout3/sealed). Never iterated on:
 * runs only with -Dheldout3=true, and prints aggregates only (no names, numbers or mailboxes).
 */
@EnabledIfSystemProperty(named = "heldout3", matches = "true")
class HeldOut3FinalTest {

    static final Path ROOT = Path.of("src/test/resources/accountdetect/heldout3");

    @Test
    void sealedFinalAggregatesOnly() throws IOException {
        if (!Files.isDirectory(ROOT.resolve("sealed"))) return;
        Tally t = HeldOutGateTest.run(ROOT, "sealed");
        int mustNot = t.counts.getOrDefault("mustNotReport reported at HIGH", 0) + t.counts.getOrDefault("mustNotReport reported at MEDIUM", 0);
        System.out.println("HELD-OUT v3 FINAL sealed (aggregates only)\n" + HeldOutGateTest.table(t) + "wrong at HIGH/MEDIUM: " + t.wrong.size()
                + "\n" + HeldOutGateTest.classes(t) + "mustNotReport at HIGH/MEDIUM: " + mustNot);
        assertThat(t.wrong.size()).as("sealed3 wrong facts at HIGH/MEDIUM (count only)").isZero();
        assertThat(mustNot).as("sealed3 mustNotReport at HIGH/MEDIUM (count only)").isZero();
    }
}
