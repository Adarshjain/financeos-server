package com.financeos.gmail.ingest;

public record EmailClassificationResult(
    EmailType emailType,
    double confidence,
    String reasoning,
    boolean isSuccess,
    String failureReason
) {
    public static EmailClassificationResult success(EmailType emailType, double confidence, String reasoning) {
        return new EmailClassificationResult(emailType, confidence, reasoning, true, null);
    }

    public static EmailClassificationResult failure(String reason) {
        return new EmailClassificationResult(EmailType.OTHER, 0.0, null, false, reason);
    }
}
// 
