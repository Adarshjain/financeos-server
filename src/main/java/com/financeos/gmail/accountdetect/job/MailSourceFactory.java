package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.scan.MailSource;
import com.financeos.gmail.domain.GmailConnection;

/** Opens the mail reader for a Gmail connection (Gmail in the app, a fake in tests). */
public interface MailSourceFactory {

    MailSource open(GmailConnection connection) throws Exception;
}
