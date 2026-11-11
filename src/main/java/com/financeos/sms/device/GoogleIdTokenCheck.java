package com.financeos.sms.device;

/** Verifies a Google ID token for the phone's sign-in. A seam so tests can run without the network. */
public interface GoogleIdTokenCheck {

    /**
     * What the token said. {@code valid} means signature, issuer, expiry and audience all passed; {@code subject} is
     * filled whenever the token could be read at all (even if invalid), {@code null} when it could not.
     */
    record Result(boolean valid, String subject, String email, boolean emailVerified) {
        public static Result unreadable() {
            return new Result(false, null, null, false);
        }
    }

    /** Thrown when Google's keys could not be fetched: not the caller's fault. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Result check(String idToken, String audience);
}
