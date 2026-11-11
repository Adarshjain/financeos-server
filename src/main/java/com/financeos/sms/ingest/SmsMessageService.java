package com.financeos.sms.ingest;

import com.financeos.api.sms.dto.SmsDtos.ConfirmRequest;
import com.financeos.api.sms.dto.SmsDtos.MessagePage;
import com.financeos.api.sms.dto.SmsDtos.MessageView;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsAccountQueries;
import com.financeos.sms.read.SmsKind;
import com.financeos.sms.read.SmsReadStatus;
import com.financeos.sms.read.SmsReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** What the owner sees and decides about stored messages. Every method is scoped to the user. */
@Service
public class SmsMessageService {

    static final int MAX_PAGE_SIZE = 100;
    static final String CONFIRMED = "CONFIRMED";
    static final String REJECTED = "REJECTED";

    private final SmsMessageRepository messages;
    private final SmsAccountQueries accountQueries;
    private final SenderCatalogue catalogue;
    private final SmsPipelineService pipeline;

    public SmsMessageService(SmsMessageRepository messages, SmsAccountQueries accountQueries, SenderCatalogue catalogue,
                             SmsPipelineService pipeline) {
        this.messages = messages;
        this.accountQueries = accountQueries;
        this.catalogue = catalogue;
        this.pipeline = pipeline;
    }

    /** Newest first. {@code status} and {@code accountId} are optional filters. Bodies are decrypted for the owner. */
    @Transactional(readOnly = true)
    public MessagePage list(UUID userId, SmsReadStatus status, UUID accountId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id")));
        Page<SmsMessage> result;
        if (status != null && accountId != null) {
            result = messages.findByUserIdAndReadStatusAndAccountId(userId, status, accountId, pageable);
        } else if (status != null) {
            result = messages.findByUserIdAndReadStatus(userId, status, pageable);
        } else if (accountId != null) {
            result = messages.findByUserIdAndAccountId(userId, accountId, pageable);
        } else {
            result = messages.findByUserId(userId, pageable);
        }
        return new MessagePage(views(userId, result.getContent()), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** Applies the owner's overrides, then runs the pipeline for it as a certain read. */
    @Transactional
    public MessageView confirm(UUID userId, UUID id, ConfirmRequest request) {
        pipeline.lockUser(userId);
        SmsMessage m = require(userId, id);
        if (m.getReadStatus() != SmsReadStatus.NEEDS_CONFIRM && m.getReadStatus() != SmsReadStatus.COULD_NOT_READ) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a message that needs confirming can be confirmed");
        }
        BigDecimal amount = request != null && request.amount() != null ? request.amount() : m.getAmount();
        TransactionType direction = request != null && request.direction() != null
                ? direction(request.direction()) : m.getDirection();
        if (amount == null || amount.signum() <= 0 || direction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An amount and a direction are needed to confirm");
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        if (rounded.precision() - rounded.scale() > SmsReader.MAX_INTEGER_DIGITS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount is too large");
        }
        m.setAmount(rounded);
        m.setDirection(direction);
        if (request != null && request.txnDate() != null) {
            m.setTxnDate(request.txnDate());
        } else if (m.getTxnDate() == null) {
            m.setTxnDate(m.getReceivedAt().atZone(AppTime.zone()).toLocalDate());
        }
        if (request != null && request.counterparty() != null && !request.counterparty().isBlank()) {
            String c = request.counterparty().trim();
            m.setCounterparty(c.length() > 200 ? c.substring(0, 200) : c);
        }
        m.setKind(SmsKind.TRANSACTION);
        m.setReadStatus(SmsReadStatus.READ);
        m.setUserDecision(CONFIRMED);
        pipeline.decide(m);
        messages.saveAndFlush(m);
        return views(userId, List.of(m)).get(0);
    }

    /** The owner says it is not a transaction: IGNORED, no outcome. */
    @Transactional
    public MessageView reject(UUID userId, UUID id) {
        pipeline.lockUser(userId);
        SmsMessage m = require(userId, id);
        if (m.getReadStatus() != SmsReadStatus.NEEDS_CONFIRM && m.getReadStatus() != SmsReadStatus.COULD_NOT_READ) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a message that needs confirming can be rejected");
        }
        m.setReadStatus(SmsReadStatus.IGNORED);
        m.setUserDecision(REJECTED);
        m.setOutcome(null);
        m.setDuplicateOf(null);
        m.setTransactionId(null);
        messages.saveAndFlush(m);
        return views(userId, List.of(m)).get(0);
    }

    private SmsMessage require(UUID userId, UUID id) {
        return messages.findByIdAndUserId(id, userId).orElseThrow(() -> new ResourceNotFoundException("SMS message", id));
    }

    private static TransactionType direction(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "debit" -> TransactionType.DEBIT;
            case "credit" -> TransactionType.CREDIT;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be debit or credit");
        };
    }

    /** One account lookup for the whole page. */
    private List<MessageView> views(UUID userId, List<SmsMessage> rows) {
        Set<UUID> accountIds = new HashSet<>();
        rows.forEach(m -> {
            if (m.getAccountId() != null) accountIds.add(m.getAccountId());
        });
        Map<UUID, String> names = accountQueries.accounts(userId, accountIds).stream()
                .collect(Collectors.toMap(SmsAccountQueries.AccountRow::id, SmsAccountQueries.AccountRow::name));
        return rows.stream().map(m -> new MessageView(m.getId(), m.getSender(),
                catalogue.find(m.getSender()).map(SenderCatalogue.Sender::provider).orElse(m.getSender()), m.getBody(),
                m.getReceivedAt(), m.getAccountId(), m.getAccountId() == null ? null : names.get(m.getAccountId()),
                m.getReadStatus().name(), m.getKind().name(), m.getAmount(),
                m.getDirection() == null ? null : m.getDirection().name().toLowerCase(Locale.ROOT), m.getTxnDate(),
                m.getCounterparty(), m.getBalanceAfter(), m.getRefNo(), m.getOutcome() == null ? null : m.getOutcome().name(),
                m.getTransactionId())).toList();
    }
}
