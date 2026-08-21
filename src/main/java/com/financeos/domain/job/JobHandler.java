package com.financeos.domain.job;

public interface JobHandler {
    JobType type();
    Object execute(JobExecutionContext ctx) throws Exception;
}
