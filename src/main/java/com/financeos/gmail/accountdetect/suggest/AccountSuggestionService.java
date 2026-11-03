package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.account.dto.CreateCardRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderService;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.Vocab;
import com.financeos.gmail.accountdetect.unlock.PasswordRender;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Band;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes a detection into the user's suggestions and answers them. Every query is filtered by user explicitly.
 *
 * <p>Re-runs upsert by (user, key): ADDED, NOT_MINE and BELONGS rows are never touched; NEW rows get fresh facts and
 * become MATCHED once the user has the number; MATCHED rows keep that state; rows no longer reported stay as they are.
 */
@Service
public class AccountSuggestionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AccountSuggestionRepository repository;
    private final ExistingAccountsLoader existingLoader;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final CardholderService cardholderService;
    private final ApplicationEventPublisher events;

    public AccountSuggestionService(AccountSuggestionRepository repository, ExistingAccountsLoader existingLoader,
                                    AccountRepository accountRepository, CardRepository cardRepository,
                                    CardholderService cardholderService, ApplicationEventPublisher events) {
        this.repository = repository;
        this.existingLoader = existingLoader;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.cardholderService = cardholderService;
        this.events = events;
    }

    /**
     * Applies one run's detection and marks the rows worth telling the user about (NEW, never notified, FOUND or OLDER)
     * as notified.
     *
     * @return how many rows were marked: the count the notification announces
     */
    @Transactional
    public int apply(UUID userId, Detection detection) {
        LocalDate today = AppTime.today();
        Map<String, AccountSuggestion> byKey = new HashMap<>();
        Set<String> declinedParents = new HashSet<>();
        for (AccountSuggestion s : repository.findByUserId(userId)) {
            byKey.put(s.getKey(), s);
            if (s.isParentDeclined()) declinedParents.add(s.getKey());
        }
        Map<String, String> passwordRules = passwordRules(detection);
        List<SuggestionPlanner.Plan> plans = SuggestionPlanner.plan(detection, existingLoader.load(userId), today, declinedParents);

        List<SuggestionPlanner.Plan> live = new ArrayList<>();
        Set<String> liveKeys = new HashSet<>();
        for (SuggestionPlanner.Plan p : plans) {
            AccountSuggestion row = byKey.get(p.key());
            if (row != null && row.answered()) continue;
            boolean created = row == null;
            if (created) {
                row = new AccountSuggestion(userId, p.key());
                byKey.put(p.key(), row);
            }
            facts(row, p, passwordRules.get(p.entity().key()));
            if (p.match() != null && (created || row.getState() == State.NEW)) {
                row.setState(State.MATCHED);
                row.setResolvedAccountId(p.match().accountId());
                row.setResolvedCardId(p.match().cardId());
                row.setCandidateAccountId(null);
            } else if (row.getState() == State.NEW) {
                // recomputed every run: exactly one candidate sets it, zero or several clear it; a "no" is final
                row.setCandidateAccountId(!row.isThisDeclined() && p.candidates().size() == 1 ? p.candidates().get(0) : null);
            }
            live.add(p);
            liveKeys.add(p.key());
        }

        // rows first (a parent row must exist before a child points at it), then parents and the notified marker
        List<AccountSuggestion> liveRows = live.stream().map(p -> byKey.get(p.key())).toList();
        for (AccountSuggestion saved : repository.saveAllAndFlush(liveRows)) {
            byKey.put(saved.getKey(), saved);
        }
        Instant now = AppTime.nowInstant();
        int marked = 0;
        for (SuggestionPlanner.Plan p : live) {
            AccountSuggestion row = byKey.get(p.key());
            UUID parent = null;
            if (p.parentKey() != null) {
                AccountSuggestion ps = byKey.get(p.parentKey());
                parent = ps != null && ps.getState() == State.NEW ? ps.getId() : null;
                if (parent == null && row.getKind() == AccountSuggestion.Kind.ADDON_CARD && row.getParentAccountId() == null) {
                    row.setKind(p.baseKind());   // the primary is not a suggestion any more: a plain card again
                }
            } else if (p.debitNeedsBank()) {
                List<AccountSuggestion> banks = byKey.values().stream()
                        .filter(s -> s.getState() == State.NEW && s.getKind() == AccountSuggestion.Kind.BANK_ACCOUNT
                                && s.getBank().equals(p.entity().bank))
                        .toList();
                if (banks.size() == 1) parent = banks.get(0).getId();
            }
            row.setParentSuggestionId(parent);
            if (row.getState() == State.NEW && row.getNotifiedAt() == null && row.getBand() != Band.MAYBE) {
                row.setNotifiedAt(now);
                marked++;
            }
        }
        // rows this run did not report keep their facts, but a link to a parent that left NEW is dropped
        Map<UUID, AccountSuggestion> byId = new HashMap<>();
        for (AccountSuggestion s : byKey.values()) byId.put(s.getId(), s);
        List<AccountSuggestion> toSave = new ArrayList<>(liveRows);
        for (AccountSuggestion s : byKey.values()) {
            if (liveKeys.contains(s.getKey()) || s.getParentSuggestionId() == null) continue;
            AccountSuggestion ps = byId.get(s.getParentSuggestionId());
            if (ps != null && ps.getState() == State.NEW) continue;
            s.setParentSuggestionId(null);
            if (s.getKind() == AccountSuggestion.Kind.ADDON_CARD && s.getParentAccountId() == null) {
                AccountSuggestion.Kind base = baseKindOfKey(s.getKey());
                if (base != null) s.setKind(base);
            }
            toSave.add(s);
        }
        repository.saveAll(toSave);
        return marked;
    }

    /** The detected kind inside a suggestion key (bank:KIND:last4), or null. */
    static AccountSuggestion.Kind baseKindOfKey(String key) {
        int last = key.lastIndexOf(':');
        int prev = last <= 0 ? -1 : key.lastIndexOf(':', last - 1);
        if (prev < 0) return null;
        try {
            return AccountSuggestion.Kind.valueOf(key.substring(prev + 1, last));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Per entity key (bank:last4): the rule that opened its protected statements, from the statements the detector linked
     * to it by number or by their mail. Only rules built from the profile (name, birth date, card number) count; a saved
     * password ("SAVED") is never turned into a rule.
     */
    static Map<String, String> passwordRules(Detection detection) {
        Map<String, String> out = new HashMap<>();
        for (Detection.StatementLink l : detection.statementLinks) {
            if (l.doc() == null || l.doc().unlockRule() == null || l.last4() == null || l.bank() == null) continue;
            if (!Detector.LINK_LAST4.equals(l.outcome()) && !Detector.LINK_ORIGIN.equals(l.outcome())) continue;
            String rule = l.doc().unlockRule();
            if (rule.length() > AccountSuggestion.PASSWORD_RULE_MAX || !PasswordRender.renderable(rule)) continue;
            out.putIfAbsent(l.bank() + ":" + l.last4(), rule);
        }
        return out;
    }

    private static void facts(AccountSuggestion row, SuggestionPlanner.Plan p, String passwordRule) {
        Detection.Entity e = p.entity();
        row.setKind(p.kind());
        row.setBank(e.bank);
        row.setLast4(e.last4);
        row.setKindConf(name(e.kindConf));
        row.setVariant(p.variant());
        row.setVariantConf(name(e.variantConf));
        row.setVariantCandidates(p.variantCandidatesJson());
        row.setLimitBucket(e.limitBucket == null || e.limitBucket.length() <= AccountSuggestion.LIMIT_BUCKET_MAX
                ? e.limitBucket : e.limitBucket.substring(0, AccountSuggestion.LIMIT_BUCKET_MAX));
        row.setLimitConf(name(e.limitConf));
        row.setLimitAmount(e.limitAmount != null && e.limitConf != null && e.limitConf.atLeast(Vocab.Conf.MEDIUM) ? e.limitAmount : null);
        row.setPasswordRule(passwordRule);
        row.setNotInYourName(e.notInYourName);
        row.setLifecycle(e.lifecycleConf == null ? null : name(e.lifecycle));
        row.setLifecycleConf(e.lifecycle == null ? null : name(e.lifecycleConf));
        row.setExistsConf(name(e.existsConf));
        row.setBand(p.band());
        row.setFirstSeenOn(e.first);
        row.setLastSeenOn(e.last);
        row.setEvidenceMails(e.evidenceMails);
        row.setParentAccountId(p.parentAccountId());
    }

    // ---------------------------------------------------------------- answers

    @Transactional
    public void notMine(UUID userId, UUID id) {
        AccountSuggestion s = owned(userId, id);
        if (s.getState() != State.NEW) throw new ValidationException("Only a new suggestion can be marked not mine.");
        s.setState(State.NOT_MINE);
        repository.save(s);
    }

    /**
     * "Not part of that account": the user refuses the "Older number on X?" link. The parent account is cleared and the
     * row is never linked to an account as an older number again; it stays NEW and standalone.
     */
    @Transactional
    public void notPart(UUID userId, UUID id) {
        AccountSuggestion s = owned(userId, id);
        if (s.getState() != State.NEW) throw new ValidationException("Only a new suggestion can be marked not part of an account.");
        if (s.getParentAccountId() == null || s.getKind() != AccountSuggestion.Kind.CREDIT_CARD) {
            throw new ValidationException("This suggestion is not linked to an account as an older number.");
        }
        s.setParentDeclined(true);
        s.setParentAccountId(null);
        repository.save(s);
    }

    @Transactional
    public void undo(UUID userId, UUID id) {
        AccountSuggestion s = owned(userId, id);
        if (s.getState() != State.NOT_MINE) throw new ValidationException("Only a suggestion marked not mine can be undone.");
        s.setState(State.NEW);
        repository.save(s);
    }

    /**
     * "Is this your …": yes writes the number onto the candidate account (bank-details last4, or a PRIMARY / SELF card
     * for a credit card) and marks the suggestion ADDED; no drops the candidate and keeps the suggestion NEW.
     */
    @Transactional
    public void isThis(UUID userId, UUID id, UUID accountId, boolean yes) {
        AccountSuggestion s = owned(userId, id);
        if (s.getState() != State.NEW || s.getCandidateAccountId() == null || !s.getCandidateAccountId().equals(accountId)) {
            throw new ValidationException("That account is not this suggestion's candidate.");
        }
        if (!yes) {
            s.setCandidateAccountId(null);
            s.setThisDeclined(true);
            repository.save(s);
            return;
        }
        Account account = accountRepository.findById(accountId)
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        UUID cardId = null;
        if (account.getType() == AccountType.bank_account) {
            AccountBankDetails details = account.getBankDetails();
            if (details == null) {
                details = new AccountBankDetails(account, null, s.getLast4());
                details.setUser(account.getUser());
                account.setBankDetails(details);
            } else if (details.getLast4() != null && !details.getLast4().isBlank()) {
                // an account with no bank but this very number is a candidate too: nothing to write
                if (!details.getLast4().equals(s.getLast4())) throw new ValidationException("That account already has a number.");
            } else {
                details.setLast4(s.getLast4());
            }
            accountRepository.save(account);
            events.publishEvent(new AccountIngestChangedEvent(userId, s.getLast4(), account.getIngestFromDate()));
        } else if (account.getType() == AccountType.credit_card) {
            cardId = cardRepository.findOpenByAccountIdAndLast4(accountId, s.getLast4()).map(Card::getId).orElse(null);
            if (cardId == null) {
                if (!cardRepository.findByAccountId(accountId).isEmpty()) throw new ValidationException("That card account already has a card.");
                CreateCardRequest req = new CreateCardRequest(s.getLast4(), null);
                Cardholder primary = account.getCardholders().stream().filter(Cardholder::isPrimary).findFirst().orElse(null);
                if (primary == null) cardholderService.addPrimaryWithCard(accountId, req);
                else cardholderService.addCard(accountId, primary.getId(), req);
                cardId = cardRepository.findOpenByAccountIdAndLast4(accountId, s.getLast4()).map(Card::getId).orElse(null);
            }
        } else {
            throw new ValidationException("Only a bank account or a credit card can take this number.");
        }
        s.setState(State.ADDED);
        s.setResolvedAccountId(accountId);
        s.setResolvedCardId(cardId);
        s.setCandidateAccountId(null);
        repository.save(s);
    }

    private AccountSuggestion owned(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> new ResourceNotFoundException("Account suggestion", id));
    }

    // ---------------------------------------------------------------- helpers

    static List<String> readCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
