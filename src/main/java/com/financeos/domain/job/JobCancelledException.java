package com.financeos.domain.job;

public class JobCancelledException extends RuntimeException {
    public JobCancelledException(String message) {
        super(message);
    }
}
