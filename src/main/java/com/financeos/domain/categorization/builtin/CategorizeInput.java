package com.financeos.domain.categorization.builtin;

import com.financeos.gmail.extract.Direction;

import java.math.BigDecimal;

/**
 * Everything the built-in categoriser looks at; every field may be null. {@code text} is the normalised email
 * text ({@code EmailText.of(message).text()}), {@code mcc} a 4-digit ISO 18245 code, {@code holderName} the
 * account holder's own name when the caller knows it (the greeting of the mail is used as well).
 */
public record CategorizeInput(String counterparty, Direction direction, BigDecimal amount, String fromAddress,
                              String subject, String text, String templateId, String mcc, String holderName) {

    public CategorizeInput(String counterparty, Direction direction, BigDecimal amount, String fromAddress,
                           String subject, String text, String templateId, String mcc) {
        this(counterparty, direction, amount, fromAddress, subject, text, templateId, mcc, null);
    }
}
