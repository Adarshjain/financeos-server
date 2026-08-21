package com.financeos.domain.job.handlers;

import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

@Component
public class InvestmentImportCommitJobHandler implements JobHandler {

    private final ImportService importService;

    public InvestmentImportCommitJobHandler(ImportService importService) {
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
        return importService.commit(payload.request().source(), payload.request().brokerAccountId(), payload.request().rows());
    }
}
