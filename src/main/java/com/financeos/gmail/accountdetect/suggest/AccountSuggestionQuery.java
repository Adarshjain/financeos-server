package com.financeos.gmail.accountdetect.suggest;

import com.financeos.api.accountdetect.dto.AccountSuggestionsResponse;
import com.financeos.api.accountdetect.dto.AccountSuggestionsResponse.Item;
import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.job.AccountDetectState;
import com.financeos.gmail.accountdetect.job.AccountDetectStore;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds GET /account-suggestions for one user: scan progress and NEW suggestions, children nested. */
@Service
public class AccountSuggestionQuery {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final Comparator<AccountSuggestion> ORDER = Comparator
            .comparing(AccountSuggestion::getBand)
            .thenComparing(AccountSuggestion::getLastSeenOn, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AccountSuggestion::getBank)
            .thenComparing(AccountSuggestion::getLast4);

    private final AccountSuggestionRepository repository;
    private final ExistingAccountsLoader existingLoader;
    private final AccountDetectStore store;
    private final GmailConnectionRepository connectionRepository;

    public AccountSuggestionQuery(AccountSuggestionRepository repository, ExistingAccountsLoader existingLoader,
                                  AccountDetectStore store, GmailConnectionRepository connectionRepository) {
        this.repository = repository;
        this.existingLoader = existingLoader;
        this.store = store;
        this.connectionRepository = connectionRepository;
    }

    @Transactional(readOnly = true)
    public AccountSuggestionsResponse list(UUID userId) {
        AccountDetectState state = store.state(userId).orElse(null);
        boolean connected = false;
        for (GmailConnection c : connectionRepository.findByUserId(userId)) {
            if (Boolean.TRUE.equals(c.getIsConnected()) && c.getAuthFailedAt() == null) connected = true;
        }
        AccountSuggestionsResponse.Scan scan = new AccountSuggestionsResponse.Scan(
                state == null ? "NOT_STARTED" : state.getStatus().name(),
                state != null && state.getFirstScanDoneAt() != null,
                state == null ? 0 : state.getMailsSeen(),
                state == null ? 0 : state.getMailsTotal(),
                connected);

        // one batched load of the user's accounts: hides NEW rows whose number the user has since added (the next scan
        // flips them to MATCHED) and names the parent / candidate accounts without a query per row
        ExistingAccounts existing = existingLoader.load(userId);
        Map<UUID, ExistingAccounts.Acc> accounts = new HashMap<>();
        for (ExistingAccounts.Acc a : existing.accounts()) accounts.put(a.id(), a);

        List<AccountSuggestion> fresh = new ArrayList<>();
        for (AccountSuggestion s : repository.findByUserIdAndState(userId, State.NEW)) {
            if (existing.match(s.getBank(), s.getLast4(), s.getKind()) == null) fresh.add(s);
        }
        fresh.sort(ORDER);
        Set<UUID> freshIds = new HashSet<>();
        for (AccountSuggestion s : fresh) freshIds.add(s.getId());
        Map<UUID, List<AccountSuggestion>> children = new HashMap<>();
        List<AccountSuggestion> top = new ArrayList<>();
        for (AccountSuggestion s : fresh) {
            if (s.getParentSuggestionId() != null && freshIds.contains(s.getParentSuggestionId())) {
                children.computeIfAbsent(s.getParentSuggestionId(), k -> new ArrayList<>()).add(s);
            } else {
                top.add(s);
            }
        }

        List<Item> items = new ArrayList<>();
        for (AccountSuggestion s : top) items.add(item(s, children, accounts, freshIds));
        return new AccountSuggestionsResponse(scan, items);
    }

    private Item item(AccountSuggestion s, Map<UUID, List<AccountSuggestion>> children, Map<UUID, ExistingAccounts.Acc> accounts,
                      Set<UUID> freshIds) {
        List<Item> kids = new ArrayList<>();
        for (AccountSuggestion c : children.getOrDefault(s.getId(), List.of())) kids.add(item(c, children, accounts, freshIds));
        List<String> candidates = AccountSuggestionService.readCandidates(s.getVariantCandidates());
        ExistingAccounts.Acc parent = s.getParentAccountId() == null ? null : accounts.get(s.getParentAccountId());
        ExistingAccounts.Acc candidate = s.getCandidateAccountId() == null ? null : accounts.get(s.getCandidateAccountId());
        // a child whose parent suggestion is no longer shown stands alone with its own base kind
        boolean orphan = s.getParentSuggestionId() != null && !freshIds.contains(s.getParentSuggestionId());
        AccountSuggestion.Kind base = orphan && s.getKind() == AccountSuggestion.Kind.ADDON_CARD && s.getParentAccountId() == null
                ? AccountSuggestionService.baseKindOfKey(s.getKey()) : null;
        AccountSuggestion.Kind kind = base != null ? base : s.getKind();
        return new Item(s.getId(), kind.name(), s.getKindConf(), s.getBand().name(),
                new AccountSuggestionsResponse.Bank(s.getBank(), BankDirectory.displayName(s.getBank())), s.getLast4(),
                s.getVariant(), s.getVariantConf(), candidates, s.getLimitBucket(), s.getLimitConf(), s.getLifecycle(), s.getLifecycleConf(),
                s.getFirstSeenOn(), s.getLastSeenOn(), s.getEvidenceMails(),
                parent == null ? null : new AccountSuggestionsResponse.ParentAccount(parent.id(), parent.name(), parent.displayLast4()),
                orphan ? null : s.getParentSuggestionId(),
                candidate == null ? null : new AccountSuggestionsResponse.CandidateAccount(candidate.id(), candidate.name()),
                kids, caption(s, candidates, children.getOrDefault(s.getId(), List.of())));
    }

    /**
     * "Regalia Gold or Regalia · 1 add-on · 64 emails since Mar 2022": the product (candidates joined with " or " when
     * the name is LOW or several), attached add-ons / debit cards, and the mail count since the first sighting.
     */
    static String caption(AccountSuggestion s, List<String> candidates, List<AccountSuggestion> kids) {
        List<String> parts = new ArrayList<>();
        boolean unsettled = "LOW".equals(s.getVariantConf()) || candidates.size() > 1;
        if (unsettled && !candidates.isEmpty()) parts.add(String.join(" or ", candidates));
        else if (s.getVariant() != null) parts.add(s.getVariant());
        long addons = kids.stream().filter(k -> k.getKind() == AccountSuggestion.Kind.ADDON_CARD).count();
        long debits = kids.stream().filter(k -> k.getKind() == AccountSuggestion.Kind.DEBIT_CARD).count();
        if (addons > 0) parts.add(addons + (addons == 1 ? " add-on" : " add-ons"));
        if (debits > 0) parts.add(debits + (debits == 1 ? " debit card" : " debit cards"));
        if (s.getEvidenceMails() > 0) {
            String mails = s.getEvidenceMails() + (s.getEvidenceMails() == 1 ? " email" : " emails");
            if (s.getFirstSeenOn() != null) mails += " since " + MONTH.format(s.getFirstSeenOn());
            parts.add(mails);
        }
        return String.join(" · ", parts);
    }
}
