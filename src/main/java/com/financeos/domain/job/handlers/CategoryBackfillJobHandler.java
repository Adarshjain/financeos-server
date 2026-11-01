package com.financeos.domain.job.handlers;

import com.financeos.domain.categorization.CategoryBackfillService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

/** Runs the one-off category backfill (a system job, no user). */
@Component
public class CategoryBackfillJobHandler implements JobHandler {

    private final CategoryBackfillService backfillService;

    public CategoryBackfillJobHandler(CategoryBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public JobType type() {
        return JobType.CATEGORY_BACKFILL;
    }

    @Override
    public Object execute(JobExecutionContext ctx) {
        return backfillService.run(ctx);
    }
}
