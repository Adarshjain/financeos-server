package com.financeos.domain.ingestion;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.parser.StatementParseException;
import com.financeos.statement.parser.FileType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Opens a statement file with passwords: the explicit one (if any), else the account's saved one; when the file is
 * locked and that fails, the user's other saved statement passwords. Shared by the upload path
 * ({@link FileIngestionService}) and the re-read job. Passwords are never logged.
 */
@Service
public class StatementOpener {

    /** What opening a file took: the parse result and whether any password was tried. */
    public record Opened(StatementExtractionResult result, boolean passwordTried) {}

    private final StatementParser statementParser;
    /** Other saved passwords and saving a working one; null for hand-built instances that need none. */
    private final StatementPasswordVault passwordVault;

    /** The wider search (other accounts, bank rules) for the upload path; null leaves {@link #openWithSearch} as {@link #open}. */
    private final StatementPasswordFinder passwordFinder;

    public StatementOpener(StatementParser statementParser, StatementPasswordVault passwordVault) {
        this(statementParser, passwordVault, null);
    }

    @Autowired
    public StatementOpener(StatementParser statementParser, StatementPasswordVault passwordVault,
                           StatementPasswordFinder passwordFinder) {
        this.statementParser = statementParser;
        this.passwordVault = passwordVault;
        this.passwordFinder = passwordFinder;
    }

    /**
     * The upload path: {@link #open} (saving a working vault password), then, when the file is still locked, the finder's
     * search (other accounts, bank rules from the user's name and birth date). A password found that way is saved to an
     * account that has none; an existing account password is never replaced.
     */
    public Opened openWithSearch(UUID userId, UUID accountId, byte[] bytes, String explicit, String accountPassword) {
        Opened opened = open(userId, accountId, bytes, explicit, accountPassword, true);
        StatementExtractionResult result = opened.result();
        if (result.success() || passwordFinder == null
                || !StatementParseException.isPasswordMessage(result.failureReason())) {
            return opened;
        }
        boolean hasAccount = accountPassword != null && !accountPassword.isBlank();
        Set<String> tried = new HashSet<>();
        if (explicit != null && !explicit.isBlank()) tried.add(explicit);
        if (hasAccount) tried.add(accountPassword);
        if (passwordVault != null) tried.addAll(passwordVault.savedPasswords(userId));
        StatementPasswordFinder.Found found = passwordFinder.find(userId, accountId, bytes,
                FileType.pdfHeaderOffset(bytes) >= 0, tried);
        if (!found.opened()) {
            return opened;
        }
        StatementExtractionResult attempt = statementParser.parse(bytes, found.password());
        if (!attempt.success()) {
            return opened;
        }
        if (!hasAccount && found.source() != StatementPasswordFinder.Source.OWN_ACCOUNT && passwordVault != null) {
            passwordVault.saveIfMissing(userId, accountId, found.password());
        }
        return new Opened(attempt, true);
    }

    /**
     * @param saveWorkingPassword when true, a vault password that opens the file is saved to an account that has none
     *                            (the upload path); false leaves every account untouched (the read-only job)
     */
    public Opened open(UUID userId, UUID accountId, byte[] bytes, String explicit, String accountPassword,
                       boolean saveWorkingPassword) {
        boolean hasExplicit = explicit != null && !explicit.isBlank();
        boolean hasAccount = accountPassword != null && !accountPassword.isBlank();
        String first = hasExplicit ? explicit : hasAccount ? accountPassword : null;
        StatementExtractionResult result = statementParser.parse(bytes, first);
        boolean tried = first != null;
        if (result.success() || !StatementParseException.isPasswordMessage(result.failureReason())) {
            return new Opened(result, tried);
        }
        LinkedHashSet<String> others = new LinkedHashSet<>();
        if (hasExplicit && hasAccount) others.add(accountPassword);
        if (passwordVault != null) others.addAll(passwordVault.savedPasswords(userId));
        others.remove(first);
        for (String candidate : others) {
            StatementExtractionResult attempt = statementParser.parse(bytes, candidate);
            tried = true;
            if (attempt.success()) {
                if (saveWorkingPassword && !hasAccount && passwordVault != null) {
                    passwordVault.saveIfMissing(userId, accountId, candidate);
                }
                return new Opened(attempt, true);
            }
        }
        return new Opened(result, tried);
    }
}
