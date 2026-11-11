package com.financeos.sms.line;

import com.financeos.sms.device.SmsDeviceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Upserts the per-line summary a phone reports. New lines start OFF and unseen; a repeat changes only counts and dates. */
@Service
public class SmsLineSummaryService {

    public record LineSummary(String sender, String ending, String family, int msgCount, int txnCount,
                              Instant firstMsgAt, Instant lastMsgAt) {}

    public record Skipped(int personal, int otp, int promo, int unknownSender) {}

    public record Result(int lines, int newLines, int skipped) {}

    private final SmsLineRepository lines;
    private final SenderCatalogue catalogue;
    private final SmsSuggestionService suggestions;
    private final SmsDeviceService devices;

    public SmsLineSummaryService(SmsLineRepository lines, SenderCatalogue catalogue, SmsSuggestionService suggestions,
                                 SmsDeviceService devices) {
        this.lines = lines;
        this.catalogue = catalogue;
        this.suggestions = suggestions;
        this.devices = devices;
    }

    @Transactional
    public Result apply(UUID userId, UUID deviceId, List<LineSummary> incoming, Skipped phoneSkipped) {
        lines.lockUser(userId);
        lines.healOrphans(userId);
        Map<String, SmsLine> existing = new HashMap<>();
        for (SmsLine l : lines.findByUserId(userId)) {
            existing.put(key(l.getSender(), l.getEnding(), l.getFamily()), l);
        }
        int created = 0;
        int skipped = 0;
        List<SmsLine> touched = new ArrayList<>();
        for (LineSummary s : incoming) {
            String sender = SenderCatalogue.normaliseSender(s.sender());
            String ending = blankToNull(s.ending());
            String family = blankToNull(s.family());
            if (!SmsLineKey.isValidSender(sender) || (ending != null && !ending.matches("\\d{2,4}"))
                    || (family != null && family.length() > 40)) {
                skipped++;
                continue;
            }
            String k = key(sender, ending, family);
            SmsLine line = existing.get(k);
            if (line == null) {
                line = new SmsLine();
                line.setUserId(userId);
                line.setSender(sender);
                line.setEnding(ending);
                line.setFamily(family);
                line.setProvider(catalogue.find(sender).map(SenderCatalogue.Sender::provider).orElse(sender));
                line.setState(SmsLineState.OFF);
                line.setSeenByUser(false);
                existing.put(k, line);
                created++;
            }
            line.setMsgCount(Math.max(0, s.msgCount()));
            line.setTxnCount(Math.max(0, s.txnCount()));
            line.setFirstMsgAt(s.firstMsgAt());
            line.setLastMsgAt(s.lastMsgAt());
            touched.add(lines.save(line));
        }
        suggestions.suggest(userId, touched);
        if (phoneSkipped != null) {
            devices.markSummary(deviceId, phoneSkipped.personal(), phoneSkipped.otp(), phoneSkipped.promo(), phoneSkipped.unknownSender());
        } else {
            devices.markSummary(deviceId);
        }
        return new Result(touched.size(), created, skipped);
    }

    private static String key(String sender, String ending, String family) {
        return sender + "|" + (ending == null ? "" : ending) + "|" + (family == null ? "" : family);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
