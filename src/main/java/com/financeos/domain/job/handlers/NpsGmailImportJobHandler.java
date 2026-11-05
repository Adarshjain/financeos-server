package com.financeos.domain.job.handlers;

import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import com.financeos.domain.nps.gmail.NpsGmailHeldImporter;
import com.financeos.domain.nps.gmail.NpsGmailHeldQueue;
import org.springframework.stereotype.Component;

/** Imports the Gmail statements held for a newly added NPS account (outside the request that added it). */
@Component
public class NpsGmailImportJobHandler implements JobHandler {

    private final NpsGmailHeldImporter importer;

    public NpsGmailImportJobHandler(NpsGmailHeldImporter importer) {
        this.importer = importer;
    }

    @Override
    public JobType type() {
        return JobType.NPS_GMAIL_IMPORT;
    }

    @Override
    public Object execute(JobExecutionContext ctx) {
        NpsGmailHeldQueue.Payload payload = ctx.payload(NpsGmailHeldQueue.Payload.class);
        if (ctx.getUserId() == null || payload == null) {
            return new NpsGmailHeldImporter.Result(0, 0);
        }
        NpsGmailHeldImporter.Result result = importer.run(ctx.getUserId(), payload.accountId());
        if (result.failed() > 0) {
            // the job shows FAILED; the failed rows keep their reason and are queued again by the next Gmail sync
            throw new IllegalStateException(result.failed() + " of " + (result.imported() + result.failed())
                    + " held NPS statements could not be imported");
        }
        return result;
    }
}
