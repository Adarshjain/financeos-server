package com.financeos.domain.epf.passbook;

import java.util.List;

/** Parsed EPFO passbook. Deliberately carries no personal name, father's name or date of birth. */
public record ParsedPassbook(
        String uan,
        String memberId,
        String establishmentId,
        String establishmentName,
        List<ParsedPassbookRow> rows,
        int unrecognisedLines,
        ClosingBalance closing) {
}
