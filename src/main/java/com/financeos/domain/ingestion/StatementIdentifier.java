package com.financeos.domain.ingestion;

import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementParseException;
import org.springframework.stereotype.Component;

/** Reads who a statement file is from (bank id, account number) without parsing its rows. A seam so callers can be tested without real files. */
@Component
public class StatementIdentifier {

    /** Throws {@link StatementParseException} for a locked or unreadable file. */
    public StatementIdentity identify(byte[] bytes, String password) {
        return StatementParseEngine.identify(bytes, password);
    }

    /** True when the file is locked (opens only with a password). */
    public boolean isLocked(byte[] bytes) {
        try {
            identify(bytes, null);
            return false;
        } catch (StatementParseException e) {
            return e.isPasswordProblem();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
