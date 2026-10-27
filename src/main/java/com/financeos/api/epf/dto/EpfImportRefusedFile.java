package com.financeos.api.epf.dto;

/** A file of a batch import that was skipped; {@code code} is NOT_A_PASSBOOK, PASSWORD_PROTECTED, UNREADABLE or UAN_MISMATCH. */
public record EpfImportRefusedFile(String fileName, String code, String message) {
}
