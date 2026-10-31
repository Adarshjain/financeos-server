package com.financeos.gmail.classify;

import java.util.List;

/**
 * Classifier answer. {@code signals} are short codes explaining the decision (never message text).
 */
public record ClassificationResult(
        EmailKind kind,
        EmailSubtype subtype,
        ClassifierConfidence confidence,
        String bankId,
        List<String> signals,
        int version) {

    public String signalsJoined(int maxLength) {
        String joined = String.join(",", signals);
        return joined.length() <= maxLength ? joined : joined.substring(0, maxLength);
    }
}
