package com.financeos.core.observability;

import com.financeos.domain.job.JobType;
import com.financeos.gmail.client.GmailCallNames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lists every op the app can emit and keeps the Grafana Cloud series bill under the agreed budget. */
class OpSeriesBudgetTest {

    /** Series per (op, outcome): 9 SLO buckets + +Inf + sum + count + max. */
    private static final int SERIES_PER_OP_OUTCOME = 13;
    /** Stage ops have no buckets (TimingConfig MeterFilter): sum + count + max per outcome. */
    private static final int SERIES_PER_STAGE_OUTCOME = 3;
    /** The long-task timer the observation handler adds per op: active count, duration sum, max. */
    private static final int SERIES_PER_OP_ACTIVE = 3;
    /** financeos.db.queries: 5 buckets + +Inf + sum + count + max, per tag value. */
    private static final int SERIES_PER_SUMMARY_TAG = 9;
    private static final int MAX_ROUTES = 60;
    /** Stage ops that can also record outcome=rejected (an expected failure): statement identify/unlock_saved, gmailsync fetch, chat llm. */
    private static final int REJECTED_STAGE_OPS = 4;
    private static final int BUDGET = 2500;

    private List<String> allOps() {
        List<String> ops = new ArrayList<>(Ops.FIXED);
        for (JobType t : JobType.values()) {
            ops.add(Ops.job(t.name()));
        }
        for (String call : GmailCallNames.ALL) {
            ops.add(Ops.gmail(call));
        }
        for (int i = 0; i < Ops.MAX_LLM_PROVIDERS; i++) {
            ops.add(Ops.llm("provider" + i));
        }
        ops.addAll(stageOps());
        return ops;
    }

    private List<String> stageOps() {
        List<String> ops = new ArrayList<>(Ops.STAGES);
        for (int i = 0; i < Ops.MAX_AUTOLINKERS; i++) {
            ops.add(Ops.autolink("Linker" + i));
        }
        return ops;
    }

    @Test
    void everyOp_isValid_andUnique() {
        List<String> ops = allOps();
        assertThat(ops).doesNotHaveDuplicates();
        assertThat(ops).allSatisfy(op -> assertThat(Timing.OP_PATTERN.matcher(op).matches()).as(op).isTrue());
    }

    @Test
    void stageOps_areAllUnderTheStagePrefix() {
        assertThat(stageOps()).allSatisfy(op -> assertThat(op).startsWith("stage."));
    }

    @Test
    void worstCaseSeries_stayUnderTheBudget() {
        int stages = stageOps().size();
        int ops = allOps().size();
        int bucketed = ops - stages;
        int jobs = JobType.values().length;
        int timers = bucketed * 2 * SERIES_PER_OP_OUTCOME + stages * 2 * SERIES_PER_STAGE_OUTCOME + ops * SERIES_PER_OP_ACTIVE;
        int jobWait = jobs * 3; // count, sum, max per type
        int dbQueries = (MAX_ROUTES + jobs) * SERIES_PER_SUMMARY_TAG;

        int rejected = REJECTED_STAGE_OPS * SERIES_PER_STAGE_OUTCOME;
        int total = timers + jobWait + dbQueries + rejected;

        System.out.println("OP_SERIES_TOTAL=" + total + " (ops=" + ops + ", stages=" + stages + ")");
        assertThat(total).as("estimated new series, ops=%d", ops).isLessThan(BUDGET);
    }
}
