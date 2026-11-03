package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.Detection;

import java.util.UUID;

/**
 * Receives each ACCOUNT_DETECT run's detection for one user (over all of the user's stored facts). The default sink
 * only logs counts; a later phase supplies the real one (matching to accounts, suggestions).
 */
public interface DetectionSink {

    void accept(UUID userId, Detection detection, DetectionContext ctx);
}
