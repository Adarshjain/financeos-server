package com.financeos.statement.parser.corpus;

import com.financeos.statement.parser.Derived;
import com.financeos.statement.parser.ParseInfo;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.StatementMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The corpus tool's layout support: the layout_id result column, the family filter and the ignored-field switch. */
class CorpusLayoutSwitchTest {

    private static ParsedStatement statement(String layoutId, String reader) {
        ParseInfo info = new ParseInfo();
        info.verdict = "AUTO-INGEST";
        info.mode = "balance-chain";
        info.cardChecks = new LinkedHashMap<>();
        return new ParsedStatement(new StatementMeta(), "bank_account", new LinkedHashMap<>(), new Derived(), info,
                List.of(), "").withReader(layoutId, reader);
    }

    private static void snapshot(Path dir, String sha, String amount, String signSource, String layoutId) throws Exception {
        Files.createDirectories(dir);
        String extra = layoutId == null ? "" : "\"layoutId\":\"" + layoutId + "\",";
        Files.writeString(dir.resolve(sha + ".json"), "{" + extra + "\"statementType\":\"bank_account\",\"parseInfo\":{\"verdict\":\"X\"},"
                + "\"transactions\":[{\"index\":0,\"date\":\"2026-01-01\",\"amount\":\"" + amount
                + "\",\"description\":\"d\",\"signSource\":\"" + signSource + "\"}]}", StandardCharsets.UTF_8);
    }

    @Test
    void resultsEndWithALayoutIdColumn() {
        assertThat(CorpusRunner.RESULT_COLUMNS.get(CorpusRunner.RESULT_COLUMNS.size() - 1)).isEqualTo("layout_id");
    }

    @Test
    void aLayoutReadRowNamesItsLayoutAndAV1RowLeavesItEmpty() {
        CorpusRunner.Entry e = new CorpusRunner.Entry("abc", "pdf", "statement", "axis");
        String[] layout = CorpusRunner.resultRow(e, 10, 5, statement("axis-card-dtda-2024", "layout"), null).split("\t", -1);
        String[] v1 = CorpusRunner.resultRow(e, 10, 5, statement(null, "v1"), null).split("\t", -1);
        assertThat(layout).hasSize(CorpusRunner.RESULT_COLUMNS.size());
        assertThat(layout[layout.length - 1]).isEqualTo("axis-card-dtda-2024");
        assertThat(v1).hasSize(CorpusRunner.RESULT_COLUMNS.size());
        assertThat(v1[v1.length - 1]).isEmpty();
    }

    @Test
    void aFailedRowStillHasOneCellPerColumn() {
        CorpusRunner.Entry e = new CorpusRunner.Entry("abc", "pdf", "statement", "axis");
        String[] cells = CorpusRunner.resultRow(e, 10, 5, null, new IllegalStateException("x")).split("\t", -1);
        assertThat(cells).hasSize(CorpusRunner.RESULT_COLUMNS.size());
    }

    @Test
    void shasWithLayoutListsOnlyTheRowsOfThatLayout(@TempDir Path tmp) throws Exception {
        Path results = tmp.resolve("results.tsv");
        Files.writeString(results, "sha256\tissuer\tlayout_id\na\taxis\tfam-1\nb\taxis\t\nc\thdfc\tfam-2\nd\taxis\tfam-1\n");
        assertThat(CorpusDiff.shasWithLayout(results, "fam-1")).containsExactlyInAnyOrder("a", "d");
        assertThat(CorpusDiff.shasWithLayout(results, "other")).isEmpty();
    }

    @Test
    void shasWithLayoutIsEmptyWhenTheFileOrColumnIsMissing(@TempDir Path tmp) throws Exception {
        assertThat(CorpusDiff.shasWithLayout(tmp.resolve("none.tsv"), "fam-1")).isEmpty();
        Path old = tmp.resolve("old.tsv");
        Files.writeString(old, "sha256\tissuer\na\taxis\n");
        assertThat(CorpusDiff.shasWithLayout(old, "fam-1")).isEmpty();
    }

    @Test
    void theFamilyFilterLimitsTheComparisonToThoseFiles(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        snapshot(base, "a", "10.00", "x", null);
        snapshot(head, "a", "99.00", "x", "fam-1");
        snapshot(base, "b", "10.00", "x", null);
        snapshot(head, "b", "99.00", "x", null);
        CorpusDiff.Result all = CorpusDiff.compareDirs(base, head, null, Set.of());
        CorpusDiff.Result only = CorpusDiff.compareDirs(base, head, Set.of("a"), Set.of());
        assertThat(all.changed()).hasSize(2);
        assertThat(only.changed()).hasSize(1);
        assertThat(only.changed().get(0).name()).isEqualTo("a.json");
        assertThat(only.onlyInBase()).isEmpty();
    }

    @Test
    void ignoredFieldNamesAreLeftOutAtAnyDepth(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        snapshot(base, "a", "10.00", "balance", null);
        snapshot(head, "a", "10.00", "column", "fam-1");
        assertThat(CorpusDiff.compareDirs(base, head, null, Set.of()).changed()).hasSize(1);
        CorpusDiff.Result ignored = CorpusDiff.compareDirs(base, head, null, CorpusDiff.parseIgnore("signSource, layoutId,reader"));
        assertThat(ignored.changed()).isEmpty();
        assertThat(ignored.identical()).isEqualTo(1);
    }

    @Test
    void ignoringAFieldStillShowsOtherDifferences(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        snapshot(base, "a", "10.00", "balance", null);
        snapshot(head, "a", "11.00", "column", "fam-1");
        assertThat(CorpusDiff.compareDirs(base, head, null, Set.of("signSource", "layoutId")).changed()).hasSize(1);
    }

    @Test
    void aFamilyFilterThatMatchesNothingFailsWithAClearMessage() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> CorpusDiff.requireFamilyFiles("fam-1", Set.of()));
        assertThat(e.getMessage()).contains("fam-1").contains("matches no file");
        CorpusDiff.requireFamilyFiles("fam-1", Set.of("a"));
        CorpusDiff.requireFamilyFiles(null, null);
    }

    @Test
    void parseIgnoreSplitsOnCommasAndSkipsBlanks() {
        assertThat(CorpusDiff.parseIgnore(null)).isEmpty();
        assertThat(CorpusDiff.parseIgnore("")).isEmpty();
        assertThat(CorpusDiff.parseIgnore("a,, b ,")).containsExactlyInAnyOrder("a", "b");
    }
}
