package com.financeos.gmail.accountdetect.unlock;

/** The scan's hook for a protected statement PDF: tries to open it for one user (see {@link StatementUnlocker}). */
@FunctionalInterface
public interface PdfUnlocker {

    StatementUnlocker.Outcome unlock(byte[] pdf, StatementUnlocker.Target target);
}
