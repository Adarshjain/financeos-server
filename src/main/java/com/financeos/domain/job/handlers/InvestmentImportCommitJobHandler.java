package com.financeos.domain.job.handlers;

import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionDetectTriggers;
import org.springframework.stereotype.Component;

@Component
public class InvestmentImportCommitJobHandler implements JobHandler {

    private final ImportService importService;
    private final CorporateActionDetectTriggers detectTriggers;

    public InvestmentImportCommitJobHandler(ImportService importService, CorporateActionDetectTriggers detectTriggers) {
        this.detectTriggers = detectTriggers;
        this.importService = importService;
    }

    @Override
    public JobType type() {
        return JobType.INVESTMENT_IMPORT_COMMIT;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        InvestmentImportCommitPayload payload = ctx.payload(InvestmentImportCommitPayload.class);
        ctx.checkCancelled();
        var result = importService.commit(payload.request().source(), payload.request().brokerAccountId(), payload.request().rows());
        // New trades may span a split or bonus: check this user's holdings (never fails the import).
        detectTriggers.enqueueForUser(ctx.getUserId());
        return result;
    }
}
