package com.financeos.domain.ingestion;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.gmail.accountdetect.Vocab;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import com.financeos.statement.parser.FileType;
import com.financeos.statement.parser.PdfPasswords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the password of a stored statement file from what the statement has (its user, its account, its bytes): the
 * same search the Gmail path does at ingest ({@link StatementReconciliationService}: saved passwords of every account
 * with case variants, then passwords built from the bank's rules and the user's name / birth date).
 * <p>
 * Order: (1) the statement's own account's saved password, as saved then lowercase then uppercase; (2) the user's other
 * accounts' saved passwords, every account as saved before any case variant (as {@code matchByPassword}); (3) the
 * vault's other saved passwords (anything not yet tried); (4) for a PDF, the bank-rule passwords of the statement's
 * account's bank ({@link StatementUnlocker}). Steps 1-3 try at most {@link #MAX_SAVED_TRIES} candidates, and the whole search stops at a per-file
 * deadline (then {@link Found#capHit()}); step 4 has the unlocker's own bound.
 * <p>
 * Rule inputs a stored statement has: the user's name and birth date, the account's bank (a stored file has no sender,
 * so the bank is the account's, which is what the Gmail path ends with for partner brands), the account kind and its
 * card / account last4. Not available (so those rules are skipped): rules the mail text names (hints) and numbers in
 * the mail's subject or body.
 * <p>
 * Never saves anything, never logs a password; the account data is read in short transactions and every password
 * trial runs with none open.
 */
@Service
public class StatementPasswordFinder {

    private static final Logger log = LoggerFactory.getLogger(StatementPasswordFinder.class);

    /** Saved-password candidates tried per file (steps 1-3). */
    public static final int MAX_SAVED_TRIES = 20;

    /** Where the working password came from. */
    public enum Source { OWN_ACCOUNT, OTHER_ACCOUNT, VAULT, BANK_RULES }

    /** The result: source and password when one opened the file, whether the candidate cap cut the search short. */
    public record Found(Source source, String password, boolean capHit) {
        public boolean opened() {
            return source != null;
        }

        @Override
        public String toString() {
            return "Found[" + source + ", capHit=" + capHit + "]";
        }
    }

    private record Saved(String accountId, String password) {}

    private record Own(String bank, AccountType type, List<String> numbers, String password) {}

    private record Loaded(Own own, List<Saved> others) {}

    private final AccountRepository accountRepository;
    private final StatementPasswordVault vault;
    private final UnlockProfileLoader profileLoader;
    private final StatementUnlocker unlocker;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration budget;

    @Autowired
    public StatementPasswordFinder(AccountRepository accountRepository, StatementPasswordVault vault,
                                   UnlockProfileLoader profileLoader, PlatformTransactionManager txManager,
                                   ObjectProvider<Clock> clock,
                                   @Value("${app.statement-reparse.unlock-budget-seconds:10}") long budgetSeconds) {
        this(accountRepository, vault, profileLoader, StatementUnlocker.bundled(), txManager,
                clock.getIfAvailable(Clock::systemUTC), budgetSeconds);
    }

    StatementPasswordFinder(AccountRepository accountRepository, StatementPasswordVault vault,
                            UnlockProfileLoader profileLoader, StatementUnlocker unlocker,
                            PlatformTransactionManager txManager, Clock clock, long budgetSeconds) {
        this.clock = clock;
        this.budget = Duration.ofSeconds(budgetSeconds);
        this.accountRepository = accountRepository;
        this.vault = vault;
        this.profileLoader = profileLoader;
        this.unlocker = unlocker;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    /**
     * @param pdf true for a PDF (the bank rules apply to PDFs only), false for an encrypted Excel file
     * @param alreadyTried passwords the caller already tried as given (e.g. the account's saved one): not tried again.
     *                     Never logged.
     * @return which source opened the file (with the password); nothing opened it otherwise
     */
    public Found find(UUID userId, UUID accountId, byte[] bytes, boolean pdf, Set<String> alreadyTried) {
        Instant deadline = clock.instant().plus(budget);
        if (pdf) {
            // a PDF may carry bytes before its header (the parser skips them); the trials see the document itself
            int start = FileType.pdfHeaderOffset(bytes);
            if (start > 0) bytes = java.util.Arrays.copyOfRange(bytes, start, bytes.length);
        }
        Loaded loaded = tx.execute(status -> load(userId, accountId));
        Trials trials = new Trials(bytes, pdf, clock, deadline, alreadyTried);
        if (loaded != null && loaded.own() != null) {
            for (String v : PdfPasswords.variants(loaded.own().password())) {
                if (trials.opens(v)) return new Found(Source.OWN_ACCOUNT, v, false);
            }
        }
        if (loaded != null) {
            // every account's password as saved before any case variant, so a variant never beats another account's exact one
            for (boolean variantsPass : new boolean[]{false, true}) {
                for (Saved s : loaded.others()) {
                    List<String> vs = PdfPasswords.variants(s.password());
                    for (String v : variantsPass ? vs.subList(1, vs.size()) : vs.subList(0, 1)) {
                        if (trials.opens(v)) return new Found(Source.OTHER_ACCOUNT, v, false);
                    }
                }
            }
        }
        for (String v : vault.savedPasswords(userId)) {
            if (v != null && !v.isBlank() && trials.opens(v)) return new Found(Source.VAULT, v, false);
        }
        if (pdf && loaded != null && loaded.own() != null && trials.expired()) {
            trials.capHit = true;
        } else if (pdf && loaded != null && loaded.own() != null) {
            String rule = byRules(userId, loaded.own(), bytes);
            if (rule != null) return new Found(Source.BANK_RULES, rule, false);
        }
        return new Found(null, null, trials.capHit);
    }

    /** The saved-password trials of one file: each distinct candidate once, at most {@link #MAX_SAVED_TRIES}. */
    private static final class Trials {
        private final byte[] bytes;
        private final boolean pdf;
        private final Clock clock;
        private final Instant deadline;
        private final Set<String> tried = new HashSet<>();
        private int count;
        boolean capHit;

        Trials(byte[] bytes, boolean pdf, Clock clock, Instant deadline, Set<String> alreadyTried) {
            this.bytes = bytes;
            this.pdf = pdf;
            this.clock = clock;
            this.deadline = deadline;
            if (alreadyTried != null) tried.addAll(alreadyTried);
        }

        boolean expired() {
            return !clock.instant().isBefore(deadline);
        }

        boolean opens(String candidate) {
            if (tried.contains(candidate)) return false;
            if (count >= MAX_SAVED_TRIES || expired()) {
                capHit = true;
                return false;
            }
            tried.add(candidate);
            count++;
            try {
                return StatementReconciliationService.opens(bytes, candidate, pdf);
            } catch (RuntimeException e) {
                return false; // a malformed file on one trial is "did not open", the next source still runs
            }
        }
    }

    private Loaded load(UUID userId, UUID accountId) {
        Account own = accountRepository.findByIdAndUser_Id(accountId, userId).orElse(null);
        List<Saved> others = new ArrayList<>();
        for (Account a : accountRepository.findByUserIdAndHasStatementPassword(userId)) {
            if (a.getId().equals(accountId)) continue;
            String p = passwordOf(a);
            if (p != null && !p.isBlank()) others.add(new Saved(a.getId().toString(), p));
        }
        if (own == null) return new Loaded(null, others);
        List<String> numbers = new ArrayList<>();
        addDigits(numbers, own.primaryLast4());
        if (own.getBankDetails() != null) addDigits(numbers, own.getBankDetails().getLast4());
        return new Loaded(new Own(own.getBank(), own.getType(), numbers, passwordOf(own)), others);
    }

    private static void addDigits(List<String> out, String last4) {
        if (last4 != null && last4.length() == 4 && !out.contains(last4)) out.add(last4);
    }

    private static String passwordOf(Account a) {
        return StatementPasswordVault.passwordOf(a);
    }

    private String byRules(UUID userId, Own own, byte[] pdf) {
        if (own.bank() == null) return null;
        try {
            UnlockProfile loaded = profileLoader.load(userId);
            if (loaded.displayName() == null || loaded.displayName().isBlank()
                    || loaded.birthDate() == null || loaded.birthDate().isBlank()) {
                return null;
            }
            UnlockProfile profile = UnlockProfile.of(loaded.displayName(), loaded.birthDate());
            Vocab.Kind kind = own.type() == AccountType.credit_card ? Vocab.Kind.CREDIT_CARD : Vocab.Kind.ACCOUNT;
            return unlocker.openingPassword(pdf,
                    new StatementUnlocker.Target(own.bank(), kind, List.of(), own.numbers(), null), profile);
        } catch (RuntimeException e) {
            log.warn("Bank password rules failed for account {}: {}", own.bank(), e.getClass().getSimpleName());
            return null;
        }
    }
}
