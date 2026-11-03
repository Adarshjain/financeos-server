package com.financeos.gmail.accountdetect;

import java.time.Instant;
import java.util.List;

/**
 * One mail as the detector sees it, in memory only. {@code body} is null when only headers were fetched.
 * Nothing here is ever written to disk or logs; only {@link MailDerived} is cached.
 */
public record MailFeature(String senderDomain, Instant date, String subject, String snippet, String body,
                          List<String> attachmentNames) {
    public MailFeature {
        attachmentNames = attachmentNames == null ? List.of() : List.copyOf(attachmentNames);
    }
}
