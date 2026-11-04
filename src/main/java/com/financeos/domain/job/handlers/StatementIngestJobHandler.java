package com.financeos.domain.job.handlers;

import com.financeos.core.crypto.EncryptionService;
import com.financeos.domain.ingestion.AutoIngestService;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.ingestion.UploadedFile;
import com.financeos.domain.job.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class StatementIngestJobHandler implements JobHandler {

    private final FileIngestionService fileIngestionService;
    // Optional collaborators (setters) so a handler built by hand for a plain account ingest needs none of them.
    private AutoIngestService autoIngestService;
    private EncryptionService encryptionService;
    private JobService jobService;

    public StatementIngestJobHandler(FileIngestionService fileIngestionService) {
        this.fileIngestionService = fileIngestionService;
    }

    @Autowired(required = false)
    public void setAutoIngestService(AutoIngestService autoIngestService) {
        this.autoIngestService = autoIngestService;
    }

    @Autowired(required = false)
    public void setEncryptionService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Autowired(required = false)
    public void setJobService(@Lazy JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public JobType type() {
        return JobType.STATEMENT_INGEST;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        StatementIngestPayload payload = ctx.payload(StatementIngestPayload.class);
        List<JobArtifact> artifacts = ctx.artifacts();
        String password = payload.encryptedPassword() != null && encryptionService != null
                ? encryptionService.decrypt(payload.encryptedPassword()) : null;

        FileIngestionResult result;
        if (payload.accountId() == null) {
            result = autoIngestService.run(ctx, artifacts, password);
        } else {
            List<UploadedFile> files = artifacts.stream()
                    .map(a -> new UploadedFile(a.getFilename(), a.getContentType(), a.getData()))
                    .toList();
            result = password == null
                    ? fileIngestionService.ingest(payload.accountId(), files, ctx)
                    : fileIngestionService.ingest(payload.accountId(), files, ctx, password);
        }
        retainFilesNeedingAPassword(ctx.getJobId(), artifacts, result);
        return result;
    }

    /** A locked file the user can still fix with a password keeps its stored copy after the job succeeds. */
    private void retainFilesNeedingAPassword(UUID jobId, List<JobArtifact> artifacts, FileIngestionResult result) {
        if (jobService == null || result == null || result.fileDetails() == null) {
            return;
        }
        Set<UUID> keep = new HashSet<>();
        List<FileIngestionResult.FileSummary> details = result.fileDetails();
        for (int i = 0; i < details.size() && i < artifacts.size(); i++) {
            FileIngestionResult.ErrorCode code = details.get(i).errorCode();
            if (code == FileIngestionResult.ErrorCode.PASSWORD_REQUIRED || code == FileIngestionResult.ErrorCode.WRONG_PASSWORD) {
                keep.add(artifacts.get(i).getId());
            }
        }
        jobService.retainArtifacts(jobId, keep);
    }
}
