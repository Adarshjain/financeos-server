package com.financeos.statement.parser;

/** Who a statement file is from, read without parsing its rows: issuer id (null when not unique), the bank text printed on it, the raw account number. */
public record StatementIdentity(String bankId, String bankText, String accountNumber) {
}
