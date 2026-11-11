package com.financeos.sms.line;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.sms.line.SmsAccountQueries.LastDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The user's decisions on lines. Every method is scoped to the user; another user's ids are "not found". */
@Service
public class SmsLineService {

    private final SmsLineRepository lines;
    private final SmsAccountQueries queries;

    public SmsLineService(SmsLineRepository lines, SmsAccountQueries queries) {
        this.lines = lines;
        this.queries = queries;
    }

    /** ON for this account. Start date: as given, else the day after the account's last transaction, else the line's first message. */
    @Transactional
    public SmsLine turnOn(UUID userId, UUID lineId, UUID accountId, LocalDate syncFrom) {
        SmsLine line = require(userId, lineId);
        requireAccount(userId, accountId);
        line.setAccountId(accountId);
        line.setSyncFrom(syncFrom != null ? syncFrom : defaultStart(userId, accountId, line));
        line.setState(SmsLineState.ON);
        return lines.save(line);
    }

    @Transactional
    public SmsLine turnOff(UUID userId, UUID lineId) {
        SmsLine line = require(userId, lineId);
        line.setState(SmsLineState.OFF);
        return lines.save(line);
    }

    @Transactional
    public SmsLine notMine(UUID userId, UUID lineId) {
        SmsLine line = require(userId, lineId);
        line.setState(SmsLineState.NOT_MINE);
        return lines.save(line);
    }

    /** Back to OFF; the account stays. */
    @Transactional
    public SmsLine undo(UUID userId, UUID lineId) {
        SmsLine line = require(userId, lineId);
        line.setState(SmsLineState.OFF);
        return lines.save(line);
    }

    /** Assigns the account without turning anything on. A line that was ON for another account goes OFF; NOT_MINE goes back to OFF. */
    @Transactional
    public SmsLine assignAccount(UUID userId, UUID lineId, UUID accountId) {
        SmsLine line = require(userId, lineId);
        requireAccount(userId, accountId);
        if (line.getState() == SmsLineState.NOT_MINE
                || (line.getState() == SmsLineState.ON && !accountId.equals(line.getAccountId()))) {
            line.setState(SmsLineState.OFF);
            line.setSyncFrom(null);
        }
        line.setAccountId(accountId);
        return lines.save(line);
    }

    @Transactional
    public int markAllSeen(UUID userId) {
        return lines.markAllSeen(userId);
    }

    private LocalDate defaultStart(UUID userId, UUID accountId, SmsLine line) {
        List<LastDate> last = queries.lastTransactionDates(userId, List.of(accountId));
        if (!last.isEmpty() && last.get(0).date() != null) {
            return last.get(0).date().plusDays(1);
        }
        if (line.getFirstMsgAt() != null) {
            return line.getFirstMsgAt().atZone(AppTime.zone()).toLocalDate();
        }
        return AppTime.today();
    }

    private SmsLine require(UUID userId, UUID lineId) {
        return lines.findByIdAndUserId(lineId, userId).orElseThrow(() -> new ResourceNotFoundException("SMS line", lineId));
    }

    private void requireAccount(UUID userId, UUID accountId) {
        if (queries.accounts(userId, List.of(accountId)).isEmpty()) {
            throw new ResourceNotFoundException("Account", accountId);
        }
    }
}
