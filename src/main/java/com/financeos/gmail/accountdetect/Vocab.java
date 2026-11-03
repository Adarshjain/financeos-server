package com.financeos.gmail.accountdetect;

/** Closed vocabularies of the account detector. */
public final class Vocab {

    private Vocab() {}

    /** Role of a layout cluster. MARKETING, OTP and OTHER never yield facts. */
    public enum Role { STATEMENT, DUE_REMINDER, TXN_ALERT, CARD_SERVICE, ACCOUNT_SERVICE, OTP, MARKETING, OTHER;
        public boolean factual() {
            return this != OTP && this != MARKETING && this != OTHER;
        }
    }

    /** Independent evidence channels; HIGH needs two of them. */
    public enum Channel { BILLING, ALERTS, SERVICE, STATEMENT_CONTENT, PAYMENT_APP;
        static Channel of(Role r) {
            return switch (r) {
                case STATEMENT, DUE_REMINDER -> BILLING;
                case TXN_ALERT -> ALERTS;
                case CARD_SERVICE, ACCOUNT_SERVICE -> SERVICE;
                default -> null;
            };
        }
    }

    public enum Conf { HIGH, MEDIUM, LOW;
        public Conf min(Conf o) {
            return o == null ? this : (this.ordinal() >= o.ordinal() ? this : o);
        }
        public boolean atLeast(Conf o) {
            return this.ordinal() <= o.ordinal();
        }
    }

    /** What the words right before a number call it. REJECT_* never become numbers. */
    public enum LabelKind { CREDIT_CARD, DEBIT_CARD, CARD, ACCOUNT, LOAN, NONE, REJECT_ID, REJECT_REF, REJECT_OTP, REJECT_AADHAAR, REJECT_VPA, REJECT_PREPAID;
        public boolean rejected() {
            return name().startsWith("REJECT_");
        }
    }

    public enum Kind { ACCOUNT, CREDIT_CARD, DEBIT_CARD }

    public enum EventType { REPLACED, UPGRADED, ADD_ON, CLOSED, BLOCKED }

    public enum Lifecycle { REPLACED, UPGRADED, REPLACEMENT, ADD_ON, CLOSED, STOPPED }

    public enum Status { ACTIVE, QUIET, DORMANT }
}
