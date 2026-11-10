package com.financeos.statement.parser.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusDiffTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode snap(String verdict, String type, String closing, String... rows) {
        ObjectNode o = M.createObjectNode();
        o.putObject("meta").put("closingBalance", closing);
        o.put("statementType", type);
        o.putObject("parseInfo").put("verdict", verdict);
        o.putObject("summaryFields");
        ArrayNode t = o.putArray("transactions");
        for (int i = 0; i < rows.length; i++) {
            String[] p = rows[i].split("\\|");
            t.addObject().put("index", i).put("date", p[0]).put("description", p[2]).put("amount", p[1]).put("balance", "0.00");
        }
        return o;
    }

    private static void write(Path dir, String sha, JsonNode n) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(sha + ".json"), n.toString());
    }

    @Test
    void identicalSnapshotsAreNotAChange() {
        ObjectNode a = snap("OK", "bank_account", "10.00", "2026-01-01|5.00|a");
        assertThat(CorpusDiff.compare("x.json", a, a.deepCopy())).isNull();
    }

    @Test
    void verdictChangeIsReported() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "bank_account", "1.00"), snap("WARN", "bank_account", "1.00"));
        assertThat(c.verdict()).isEqualTo("OK → WARN");
        assertThat(c.statementType()).isNull();
        assertThat(c.fieldChanges()).isEmpty();
    }

    @Test
    void statementTypeChangeIsReported() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "bank_account", "1.00"), snap("OK", "credit_card", "1.00"));
        assertThat(c.statementType()).isEqualTo("bank_account → credit_card");
        assertThat(c.verdict()).isNull();
    }

    @Test
    void fieldChangeShowsOldAndNewValues() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1.00"), snap("OK", "t", "2.00"));
        assertThat(c.fieldChanges()).containsExactly("meta.closingBalance: 1.00 → 2.00");
    }

    @Test
    void fieldGoingFromNullToValueAndMissingIsReported() {
        ObjectNode a = snap("OK", "t", "1.00");
        ObjectNode b = snap("OK", "t", "1.00");
        ((ObjectNode) a.get("meta")).putNull("ifsc");
        ((ObjectNode) b.get("meta")).put("ifsc", "HDFC0000001");
        ((ObjectNode) b.get("meta")).put("bank", "HDFC");
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", a, b);
        assertThat(c.fieldChanges()).containsExactly("meta.bank: null → HDFC", "meta.ifsc: null → HDFC0000001");
    }

    @Test
    void transactionCountChangeIsReported() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", "2026-01-01|5.00|a"),
                snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-02|6.00|b"));
        assertThat(c.txnCount()).isEqualTo("1 → 2");
    }

    @Test
    void rowAddedWhenCountsDifferMatchesByDateAndAmount() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", "2026-01-01|5.00|a"),
                snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-02|6.00|b"));
        assertThat(c.rowsAdded()).isEqualTo(1);
        assertThat(c.rowsRemoved()).isZero();
        assertThat(c.rowsChanged()).isZero();
        assertThat(c.addedExamples()).hasSize(1);
        assertThat(c.addedExamples().get(0)).contains("2026-01-02").contains("6.00");
    }

    @Test
    void rowRemovedWhenCountsDifferMatchesByDateAndAmount() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-02|6.00|b"),
                snap("OK", "t", "1", "2026-01-02|6.00|b"));
        assertThat(c.rowsRemoved()).isEqualTo(1);
        assertThat(c.rowsAdded()).isZero();
        assertThat(c.removedExamples().get(0)).contains("2026-01-01");
    }

    @Test
    void duplicateDateAmountRowsCountAsAMultiset() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-01|5.00|a"),
                snap("OK", "t", "1", "2026-01-01|5.00|a"));
        assertThat(c.rowsRemoved()).isEqualTo(1);
        assertThat(c.rowsAdded()).isZero();
    }

    @Test
    void rowChangedWhenCountsAreEqualMatchesByIndex() {
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-02|6.00|b"),
                snap("OK", "t", "1", "2026-01-01|5.00|a", "2026-01-02|6.00|b renamed"));
        assertThat(c.rowsChanged()).isEqualTo(1);
        assertThat(c.rowsAdded()).isZero();
        assertThat(c.rowsRemoved()).isZero();
        assertThat(c.changedExamples().get(0)).startsWith("#1:").contains("b renamed");
    }

    @Test
    void examplesAreCappedAtFive() {
        String[] base = new String[8];
        String[] head = new String[8];
        for (int i = 0; i < 8; i++) {
            base[i] = "2026-01-0" + (i + 1) + "|1.00|a";
            head[i] = "2026-01-0" + (i + 1) + "|1.00|b";
        }
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", snap("OK", "t", "1", base), snap("OK", "t", "1", head));
        assertThat(c.rowsChanged()).isEqualTo(8);
        assertThat(c.changedExamples()).hasSize(5);
    }

    @Test
    void failureSnapshotsCompareByTheirFields() {
        ObjectNode a = CorpusSnapshot.ofFailure(new IllegalStateException("a"));
        ObjectNode b = CorpusSnapshot.ofFailure(new IllegalStateException("b"));
        CorpusDiff.FileChange c = CorpusDiff.compare("x.json", a, b);
        assertThat(c.fieldChanges()).containsExactly("failure.message: a → b");
        assertThat(c.verdict()).isNull();
    }

    @Test
    void directoriesAreCountedAsIdenticalChangedAndOnlyInOneSide(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        write(base, "aaa", snap("OK", "t", "1"));
        write(head, "aaa", snap("OK", "t", "1"));
        write(base, "bbb", snap("OK", "t", "1"));
        write(head, "bbb", snap("WARN", "t", "1"));
        write(base, "ccc", snap("OK", "t", "1"));
        write(head, "ddd", snap("OK", "t", "1"));
        Files.writeString(head.resolve("results.tsv"), "sha256\tissuer\naaa\tHDFC\n");
        CorpusDiff.Result r = CorpusDiff.compareDirs(base, head);
        assertThat(r.identical()).isEqualTo(1);
        assertThat(r.changed()).extracting(CorpusDiff.FileChange::name).containsExactly("bbb.json");
        assertThat(r.onlyInBase()).containsExactly("ccc.json");
        assertThat(r.onlyInHead()).containsExactly("ddd.json");
    }

    @Test
    void reportHasCountsAndGroupsChangedFilesByIssuer(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        write(base, "s1", snap("OK", "t", "1"));
        write(head, "s1", snap("WARN", "t", "1"));
        write(base, "s2", snap("OK", "t", "1"));
        write(head, "s2", snap("OK", "t", "9"));
        write(base, "s3", snap("OK", "t", "1"));
        write(head, "s3", snap("FAIL", "t", "1"));
        Files.writeString(head.resolve("results.tsv"), "sha256\tissuer\ns1\tICICI\ns2\tAXIS\n");
        String md = CorpusDiff.render(CorpusDiff.compareDirs(base, head), CorpusDiff.readIssuers(head.resolve("results.tsv")));
        assertThat(md).contains("- identical: 0").contains("- changed: 3").contains("- only in base: 0").contains("- only in head: 0");
        assertThat(md.indexOf("## AXIS")).isLessThan(md.indexOf("## ICICI"));
        assertThat(md).contains("## (unknown issuer) (1)");
        assertThat(md).contains("- verdict: OK → WARN").contains("- meta.closingBalance: 1 → 9");
        assertThat(md.indexOf("### s2")).isGreaterThan(md.indexOf("## AXIS")).isLessThan(md.indexOf("## ICICI"));
    }

    @Test
    void missingResultsFileGivesNoIssuers(@TempDir Path tmp) throws Exception {
        assertThat(CorpusDiff.readIssuers(tmp.resolve("results.tsv"))).isEmpty();
    }

    @Test
    void failOnChangeListsTheChangedShas() {
        CorpusDiff.Result r = new CorpusDiff.Result(2,
                List.of(CorpusDiff.compare("abc.json", snap("OK", "t", "1"), snap("WARN", "t", "1"))), List.of("old.json"), List.of("new.json"));
        assertThatThrownBy(() -> CorpusDiff.assertNoChangeIfRequired(r, true))
                .isInstanceOf(AssertionError.class).hasMessageContaining("abc").hasMessageContaining("old").hasMessageContaining("new");
    }

    @Test
    void failOnChangePassesWhenNothingChanged() {
        assertThatCode(() -> CorpusDiff.assertNoChangeIfRequired(new CorpusDiff.Result(3, List.of(), List.of(), List.of()), true))
                .doesNotThrowAnyException();
    }

    @Test
    void changesDoNotFailWithoutTheFlag() {
        CorpusDiff.Result r = new CorpusDiff.Result(0,
                List.of(CorpusDiff.compare("abc.json", snap("OK", "t", "1"), snap("WARN", "t", "1"))), List.of(), List.of());
        assertThatCode(() -> CorpusDiff.assertNoChangeIfRequired(r, false)).doesNotThrowAnyException();
    }
}
