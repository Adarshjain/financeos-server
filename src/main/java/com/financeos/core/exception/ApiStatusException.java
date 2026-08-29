package com.financeos.core.exception;

import org.springframework.http.HttpStatus;

/**
 * A failure that carries both the HTTP status and the machine-readable {@code code}
 * the client switches on.
 *
 * The other exceptions here each map to one fixed status, which is right when the
 * concept and the status are the same thing ("not found" is always 404). Account
 * deletion needs two different statuses (403, 409) that clients must tell apart by
 * code rather than by status alone, so the pair travels with the exception.
 *
 * Prefer {@link ResourceNotFoundException} and friends where one already fits;
 * reach for this only when a handler would otherwise exist solely to attach a code.
 */
public class ApiStatusException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiStatusException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
