package com.financeos.sms.ingest;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.sms.device.SmsTokens;
import com.financeos.sms.line.SmsLine;
import com.financeos.sms.line.SmsLineKey;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.line.SmsLineState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Accepts the messages a phone uploads for lines that are ON: checks, stores, reads and decides each one. */
@Service
public class SmsIntakeService {

    public static final int MAX_MESSAGES = 200;
    public static final int MAX_BODY = 2000;

    public enum Status { ACCEPTED, DUPLICATE, REJECTED }

    public enum Reason { LINE_OFF, BEFORE_START, BAD_MESSAGE }

    public record Incoming(String sender, String body, Instant receivedAt, Integer simSlot, String fingerprint) {}

    public record Result(String fingerprint, Status status, Reason reason) {}

    private static final Logger log = LoggerFactory.getLogger(SmsIntakeService.class);

    private final SmsLineRepository lines;
    private final SmsLineKey lineKey;
    private final SmsMessageRepository messages;
    private final SmsPipelineService pipeline;
    private final AccountRepository accounts;
    private final TransactionTemplate tx;

    public SmsIntakeService(SmsLineRepository lines, SmsLineKey lineKey, SmsMessageRepository messages, SmsPipelineService pipeline,
                            AccountRepository accounts, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.tx = new TransactionTemplate(txManager);
        this.lines = lines;
        this.lineKey = lineKey;
        this.messages = messages;
        this.pipeline = pipeline;
    }

    /**
     * One result per message, in request order. Not one big transaction: the user's lines and accounts are read once,
     * then each message is stored, read and decided in its own short transaction (under the user lock), so one message
     * that cannot be saved is answered REJECTED / BAD_MESSAGE and the rest still go through.
     */
    public List<Result> accept(UUID userId, UUID deviceId, List<Incoming> incoming) {
        Map<String, SmsLine> byKey = new HashMap<>();
        for (SmsLine l : lines.findByUserId(userId)) {
            byKey.put(key(l.getSender(), l.getEnding(), l.getFamily()), l);
        }
        Map<UUID, Account> accountById = new HashMap<>();
        for (Account a : accounts.findByUserId(userId)) {
            accountById.put(a.getId(), a);
        }

        Result[] results = new Result[incoming.size()];
        String[] fingerprints = new String[incoming.size()];
        SmsLine[] lineOf = new SmsLine[incoming.size()];
        String[] senders = new String[incoming.size()];
        Set<String> candidates = new HashSet<>();

        for (int i = 0; i < incoming.size(); i++) {
            Incoming in = incoming.get(i);
            String echo = in.fingerprint();
            if (in.body() == null || in.body().isBlank() || in.body().length() > MAX_BODY || in.receivedAt() == null) {
                results[i] = new Result(echo, Status.REJECTED, Reason.BAD_MESSAGE);
                continue;
            }
            SmsLineKey.Result k = lineKey.resolve(in.sender(), in.body());
            if (!k.hasLine()) {
                results[i] = new Result(echo, Status.REJECTED, Reason.BAD_MESSAGE);
                continue;
            }
            String fp = SmsTokens.sha256(k.sender() + "\n" + in.receivedAt().toEpochMilli() + "\n" + in.body());
            String shown = echo != null && !echo.isBlank() ? echo : fp;
            SmsLine line = byKey.get(key(k.sender(), k.ending(), k.family()));
            if (line == null || line.getState() != SmsLineState.ON || line.getAccountId() == null
                    || !accountById.containsKey(line.getAccountId())) {
                results[i] = new Result(shown, Status.REJECTED, Reason.LINE_OFF);
                continue;
            }
            if (line.getSyncFrom() != null
                    && in.receivedAt().isBefore(line.getSyncFrom().atStartOfDay(AppTime.zone()).toInstant())) {
                results[i] = new Result(shown, Status.REJECTED, Reason.BEFORE_START);
                continue;
            }
            fingerprints[i] = fp;
            lineOf[i] = line;
            senders[i] = k.sender();
            candidates.add(fp);
            // Placeholder until the duplicate check below; keeps the echoed value.
            results[i] = new Result(shown, Status.ACCEPTED, null);
        }

        Set<String> known = candidates.isEmpty() ? Set.of() : messages.findKnownFingerprints(userId, candidates);
        Set<String> inRequest = new HashSet<>();
        List<Integer> toStore = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            if (fingerprints[i] == null) {
                continue;
            }
            if (known.contains(fingerprints[i]) || !inRequest.add(fingerprints[i])) {
                results[i] = new Result(results[i].fingerprint(), Status.DUPLICATE, null);
            } else {
                toStore.add(i);
            }
        }

        // Decide in received order, so a second alert in the same call sees the first one.
        toStore.sort(Comparator.comparing((Integer i) -> incoming.get(i).receivedAt()));
        for (int i : toStore) {
            Incoming in = incoming.get(i);
            SmsLine line = lineOf[i];
            Account account = accountById.get(line.getAccountId());
            SmsMessage m = new SmsMessage();
            m.setUserId(userId);
            m.setDeviceId(deviceId);
            m.setLineId(line.getId());
            m.setAccountId(line.getAccountId());
            m.setSender(senders[i]);
            m.setBody(in.body());
            m.setReceivedAt(in.receivedAt());
            m.setSimSlot(in.simSlot() != null && in.simSlot() >= 0 && in.simSlot() <= 99 ? in.simSlot() : null);
            m.setFingerprint(fingerprints[i]);
            try {
                tx.executeWithoutResult(status -> {
                    pipeline.lockUser(userId);
                    messages.save(m);
                    pipeline.process(m, account);
                });
            } catch (RuntimeException e) {
                // Stored by a concurrent call meanwhile: a duplicate. Anything else: this message only is refused.
                if (!messages.findKnownFingerprints(userId, Set.of(fingerprints[i])).isEmpty()) {
                    results[i] = new Result(results[i].fingerprint(), Status.DUPLICATE, null);
                } else {
                    log.warn("sms.intake.store.failed error={}", e.toString());
                    results[i] = new Result(results[i].fingerprint(), Status.REJECTED, Reason.BAD_MESSAGE);
                }
            }
        }
        return List.of(results);
    }

    private static String key(String sender, String ending, String family) {
        return sender + "|" + (ending == null ? "" : ending) + "|" + (family == null ? "" : family);
    }
}
