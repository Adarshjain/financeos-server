package com.financeos.gmail.attention;

import com.financeos.api.gmail.dto.AssignNumberGroupResponse;
import com.financeos.api.gmail.dto.AttentionCountResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.IgnoredNumberResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.ImportOffGroup;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.NumberSuggestion;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.SuggestionExample;
import com.financeos.api.gmail.dto.AttentionGroupsResponse.UnknownNumberGroup;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepository.BankTransactionBrief;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailIgnoredNumber;
import com.financeos.gmail.domain.GmailIgnoredNumberRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository.NumberedMail;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Gmail "Needs attention" grouped: unresolved mail naming the same number at the same bank is one group the user can
 * assign to an account or ignore; accounts with import off are one entry each.
 */
@Service
public class UnresolvedNumberService {

    private static final int MAX_BANK_LENGTH = 40;
    private static final List<GmailProcessedStatus> UNRESOLVED = List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT);

    private final GmailProcessedMessageRepository messageRepository;
    private final GmailIgnoredNumberRepository ignoredRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final AccountIdentifierService accountIdentifierService;
    private final IgnoredNumberRules ignoredRules;

    public UnresolvedNumberService(GmailProcessedMessageRepository messageRepository,
                                   GmailIgnoredNumberRepository ignoredRepository,
                                   TransactionRepository transactionRepository,
                                   AccountRepository accountRepository,
                                   AccountIdentifierService accountIdentifierService,
                                   IgnoredNumberRules ignoredRules) {
        this.messageRepository = messageRepository;
        this.ignoredRepository = ignoredRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.accountIdentifierService = accountIdentifierService;
        this.ignoredRules = ignoredRules;
    }

    record Key(String bank, String last4) {}

    private static final List<GmailProcessedStatus> UNGROUPED_STATUSES = List.of(
            GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.FAILED_PERMANENT);

    private Map<Key, List<NumberedMail>> numberedGroups(UUID userId) {
        Map<Key, List<NumberedMail>> grouped = new LinkedHashMap<>();
        for (NumberedMail m : messageRepository.findActiveUnresolvedNumbered(userId)) {
            grouped.computeIfAbsent(keyOf(m), k -> new ArrayList<>()).add(m);
        }
        return grouped;
    }

    /**
     * What the Needs-attention card header counts: unknown-number groups + import-off accounts + ungrouped emails.
     * The Settings badge and the Inbox row use it; three queries, no suggestions computed.
     */
    @Transactional(readOnly = true)
    public int attentionTotal(UUID userId) {
        long total = numberedGroups(userId).size()
                + messageRepository.findImportOffAccounts(userId, AppTime.today()).size()
                + messageRepository.countUngroupedAttentionItems(userId, UNGROUPED_STATUSES);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    @Transactional(readOnly = true)
    public AttentionGroupsResponse groups(UUID userId) {
        Map<Key, List<NumberedMail>> grouped = numberedGroups(userId);
        List<UnknownNumberGroup> unknown = new ArrayList<>();
        Map<String, List<BankTransactionBrief>> briefsByBank = bankBriefs(userId, grouped);
        for (Map.Entry<Key, List<NumberedMail>> e : grouped.entrySet()) {
            List<NumberedMail> mails = e.getValue();
            Set<String> kinds = new TreeSet<>();
            for (NumberedMail m : mails) {
                kinds.add(NumberGroupKey.kindOf(m.getPipelineKind(), m.getClassifiedKind(), m.getError()));
            }
            unknown.add(new UnknownNumberGroup(e.getKey().bank(), e.getKey().last4(), mails.size(),
                    mails.stream().map(UnresolvedNumberService::mailAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                    mails.stream().map(UnresolvedNumberService::mailAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
                    List.copyOf(kinds), suggest(e.getKey().bank() == null ? List.of() : briefsByBank.getOrDefault(e.getKey().bank(), List.of()), mails)));
        }
        unknown.sort(Comparator.comparing(UnknownNumberGroup::lastMail, Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed());

        List<ImportOffGroup> importOff = messageRepository.findImportOffAccounts(userId, AppTime.today()).stream()
                .map(a -> new ImportOffGroup(a.getAccountId(), a.getAccountName(), (int) a.getTotal(), a.getFirstMail(), a.getLastMail()))
                .toList();

        List<GmailIgnoredNumber> rules = ignoredRepository.findByUser(userId);
        Map<Key, Integer> archivedCounts = new HashMap<>();
        if (!rules.isEmpty()) {
            Map<Key, Instant> ruleSince = new HashMap<>();
            for (GmailIgnoredNumber r : rules) {
                ruleSince.put(new Key(GmailIgnoredNumber.UNKNOWN_BANK.equals(r.getBankId()) ? null : r.getBankId(), r.getLast4()), r.getCreatedAt());
            }
            for (NumberedMail m : messageRepository.findArchivedUnresolvedNumbered(userId)) {
                Key key = keyOf(m);
                Instant since = ruleSince.get(key);
                if (since != null && m.getArchivedAt() != null && !m.getArchivedAt().isBefore(since)) {
                    archivedCounts.merge(key, 1, Integer::sum);
                }
            }
        }
        List<IgnoredNumberResponse> ignored = rules.stream()
                .map(r -> new IgnoredNumberResponse(r.getId(),
                        GmailIgnoredNumber.UNKNOWN_BANK.equals(r.getBankId()) ? null : r.getBankId(), r.getLast4(), r.getCreatedAt(),
                        archivedCounts.getOrDefault(new Key(GmailIgnoredNumber.UNKNOWN_BANK.equals(r.getBankId()) ? null : r.getBankId(), r.getLast4()), 0)))
                .toList();
        long total = unknown.size() + importOff.size() + messageRepository.countUngroupedAttentionItems(userId, UNGROUPED_STATUSES);
        return new AttentionGroupsResponse(unknown, importOff, ignored, (int) Math.min(total, Integer.MAX_VALUE));
    }

    private static Key keyOf(NumberedMail m) {
        return new Key(NumberGroupKey.bankOf(m.getClassifiedBank(), m.getSenderAddress()), m.getExtractedLast4());
    }

    private static Instant mailAt(NumberedMail m) {
        return m.getInternalDate() != null ? m.getInternalDate() : m.getDiscoveredAt();
    }

    private static LocalDate dateOf(NumberedMail m) {
        if (m.getExtractDate() != null) {
            return m.getExtractDate();
        }
        return m.getInternalDate() == null ? null : m.getInternalDate().atZone(AppTime.zone()).toLocalDate();
    }

    /** One transaction query for every group with a bank: its span and amounts cover all groups, rows are split by bank. */
    private Map<String, List<BankTransactionBrief>> bankBriefs(UUID userId, Map<Key, List<NumberedMail>> grouped) {
        Set<String> banks = new HashSet<>();
        Set<BigDecimal> amounts = new HashSet<>();
        LocalDate from = null;
        LocalDate to = null;
        for (Map.Entry<Key, List<NumberedMail>> e : grouped.entrySet()) {
            if (e.getKey().bank() == null) {
                continue;
            }
            for (NumberedMail m : usable(e.getValue())) {
                banks.add(e.getKey().bank());
                amounts.add(m.getExtractAmount().abs());
                LocalDate d = dateOf(m);
                from = from == null || d.isBefore(from) ? d : from;
                to = to == null || d.isAfter(to) ? d : to;
            }
        }
        if (banks.isEmpty()) {
            return Map.of();
        }
        Map<String, List<BankTransactionBrief>> byBank = new HashMap<>();
        for (BankTransactionBrief t : transactionRepository.findBankBriefs(userId, banks, from.minusDays(1), to.plusDays(1), amounts)) {
            byBank.computeIfAbsent(t.getBank(), k -> new ArrayList<>()).add(t);
        }
        return byBank;
    }

    private static List<NumberedMail> usable(List<NumberedMail> mails) {
        return mails.stream()
                .filter(m -> m.getExtractAmount() != null && m.getExtractDirection() != null && dateOf(m) != null)
                .toList();
    }

    /**
     * Each alert mail that matches exactly one existing transaction (same amount, direction, date within a day) among the
     * user's accounts at the group's bank counts; a suggestion stands only when every counted mail points at one account.
     */
    private NumberSuggestion suggest(List<BankTransactionBrief> candidates, List<NumberedMail> mails) {
        List<NumberedMail> usable = usable(mails);
        if (usable.isEmpty() || candidates.isEmpty()) {
            return null;
        }
        int matched = 0;
        Map<UUID, String> accounts = new LinkedHashMap<>();
        BankTransactionBrief example = null;
        for (NumberedMail m : usable) {
            TransactionType type = TransactionType.fromLlmDirection(m.getExtractDirection());
            LocalDate date = dateOf(m);
            List<BankTransactionBrief> hits = candidates.stream()
                    .filter(t -> t.getType() == type && t.getAmount() != null
                            && t.getAmount().abs().compareTo(m.getExtractAmount().abs()) == 0
                            && Math.abs(ChronoUnit.DAYS.between(t.getDate(), date)) <= 1)
                    .toList();
            if (hits.size() != 1) {
                continue; // none, or ambiguous: this mail says nothing
            }
            matched++;
            accounts.putIfAbsent(hits.get(0).getAccountId(), hits.get(0).getAccountName());
            if (example == null) {
                example = hits.get(0);
            }
        }
        if (matched == 0 || accounts.size() != 1) {
            return null;
        }
        Map.Entry<UUID, String> account = accounts.entrySet().iterator().next();
        return new NumberSuggestion(account.getKey(), account.getValue(), matched, usable.size(),
                new SuggestionExample(example.getDate(), example.getAmount(), example.getDescription()));
    }

    /** Whole number across banks (no bank given); see the bank overload. */
    @Transactional
    public AssignNumberGroupResponse assign(User user, String last4, UUID accountId, AccountIdentifierKind kind) {
        return assign(user, null, last4, accountId, kind);
    }

    /**
     * Adds the number to the account as an identifier. The identifier service reactivates parked mail inside the account's
     * window and settles the rest in place: mail before the window becomes SKIPPED_BEFORE_WATERMARK with the account set.
     * With a bank, only that (bank, last4) group's mail is touched and counted; another bank's mail with the same digits stays parked.
     */
    @Transactional
    public AssignNumberGroupResponse assign(User user, String bank, String last4, UUID accountId, AccountIdentifierKind kind) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (!account.getUser().getId().equals(user.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        String groupBank = blankToNull(bank);
        int before = parkedInGroup(user.getId(), last4, groupBank);
        AccountIdentifierService.CreationResult result = accountIdentifierService.createOrUpdateIdentifier(user, account, last4, kind, groupBank);
        int remaining = parkedInGroup(user.getId(), last4, groupBank);
        int skipped = Math.max(0, before - remaining - result.reactivatedCount());
        return new AssignNumberGroupResponse(result.identifier().getId(), result.reactivatedCount(), skipped, remaining, result.jobIds());
    }

    private int parkedInGroup(UUID userId, String last4, String groupBank) {
        return (int) messageRepository.findParked(userId, last4, UNRESOLVED).stream()
                .filter(m -> groupBank == null
                        || NumberGroupKey.sameBank(groupBank, NumberGroupKey.bankOf(m.getClassifiedBank(), m.getSenderAddress())))
                .count();
    }

    /** Remembers the (bank, last4) as ignored and archives the group's current mail; later mail with it is archived on arrival. */
    @Transactional
    public AttentionCountResponse ignore(UUID userId, String bank, String last4) {
        String normalized = blankToNull(bank);
        if (normalized != null && normalized.length() > MAX_BANK_LENGTH) {
            throw new ValidationException("bank must be at most " + MAX_BANK_LENGTH + " characters");
        }
        String bankKey = IgnoredNumberRules.bankKey(normalized);
        GmailIgnoredNumber rule = ignoredRepository.findRule(userId, bankKey, last4).orElse(null);
        if (rule == null) {
            try {
                rule = ignoredRules.create(userId, bankKey, last4);
            } catch (DataIntegrityViolationException e) { // a double submit won the race: the rule exists
                rule = ignoredRepository.findRule(userId, bankKey, last4).orElseThrow(() -> e);
            }
        }
        Instant now = Instant.now();
        int archived = 0;
        for (GmailProcessedMessage row : messageRepository.findParked(userId, last4, UNRESOLVED)) {
            if (row.getArchivedAt() == null && NumberGroupKey.sameBank(normalized,
                    NumberGroupKey.bankOf(row.getClassifiedBank(), row.getSenderAddress()))) {
                row.setArchivedAt(now);
                messageRepository.save(row);
                archived++;
            }
        }
        return new AttentionCountResponse(rule.getId(), archived);
    }

    /** Drops the ignore rule and puts back the group's mail the rule archived (not mail the user archived by hand before it). */
    @Transactional
    public AttentionCountResponse unignore(UUID userId, UUID ruleId) {
        GmailIgnoredNumber rule = ignoredRepository.findOwned(ruleId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("GmailIgnoredNumber", ruleId));
        String bank = GmailIgnoredNumber.UNKNOWN_BANK.equals(rule.getBankId()) ? null : rule.getBankId();
        int restored = 0;
        for (GmailProcessedMessage row : messageRepository.findParked(userId, rule.getLast4(), UNRESOLVED)) {
            if (row.getArchivedAt() != null && !row.getArchivedAt().isBefore(rule.getCreatedAt())
                    && NumberGroupKey.sameBank(bank, NumberGroupKey.bankOf(row.getClassifiedBank(), row.getSenderAddress()))) {
                row.setArchivedAt(null);
                messageRepository.save(row);
                restored++;
            }
        }
        ignoredRepository.delete(rule);
        return new AttentionCountResponse(null, restored);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
