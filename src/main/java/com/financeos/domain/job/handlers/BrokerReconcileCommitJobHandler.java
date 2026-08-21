package com.financeos.domain.job.handlers;

import com.financeos.domain.investment.reconcile.BrokerReconciliationService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

@Component
public class BrokerReconcileCommitJobHandler implements JobHandler {

    private final BrokerReconciliationService reconciliationService;

    public BrokerReconcileCommitJobHandler(BrokerReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public JobType type() {
        return JobType.BROKER_RECONCILE_COMMIT;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        BrokerReconcileCommitPayload payload = ctx.payload(BrokerReconcileCommitPayload.class);
        ctx.checkCancelled();
        return reconciliationService.commit(payload.request());
    }
}
