package com.financeos.domain.ingestion;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.parser.StatementParseException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
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

    public StatementOpener(StatementParser statementParser, StatementPasswordVault passwordVault) {
        this.statementParser = statementParser;
        this.passwordVault = passwordVault;
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
