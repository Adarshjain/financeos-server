package com.financeos.domain.job.handlers;

import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.ingestion.UploadedFile;
import com.financeos.domain.job.*;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatementIngestJobHandler implements JobHandler {

    private final FileIngestionService fileIngestionService;

    public StatementIngestJobHandler(FileIngestionService fileIngestionService) {
        this.fileIngestionService = fileIngestionService;
    }

    @Override
    public JobType type() {
        return JobType.STATEMENT_INGEST;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        StatementIngestPayload payload = ctx.payload(StatementIngestPayload.class);
        List<JobArtifact> artifacts = ctx.artifacts();
        List<UploadedFile> files = artifacts.stream()
                .map(a -> new UploadedFile(a.getFilename(), a.getContentType(), a.getData()))
                .toList();

        return fileIngestionService.ingest(payload.accountId(), files, ctx);
    }
}
