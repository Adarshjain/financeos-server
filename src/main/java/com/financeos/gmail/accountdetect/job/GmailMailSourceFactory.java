package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.scan.GmailMailSource;
import com.financeos.gmail.accountdetect.scan.MailSource;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.engine.GmailEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Gmail reads for the ACCOUNT_DETECT job, paced like the v3 runner (gets per second, metadata batch size). */
@Component
public class GmailMailSourceFactory implements MailSourceFactory {

    private final GmailEngine engine;
    private final int batchSize;
    private final double getsPerSecond;

    public GmailMailSourceFactory(GmailEngine engine,
                                  @Value("${financeos.account-detect.batch-size:20}") int batchSize,
                                  @Value("${financeos.account-detect.gets-per-second:45}") double getsPerSecond) {
        this.engine = engine;
        this.batchSize = batchSize;
        this.getsPerSecond = getsPerSecond;
    }

    @Override
    public MailSource open(GmailConnection connection) throws Exception {
        return new GmailMailSource(engine, engine.createService(connection), batchSize, getsPerSecond);
    }
}
