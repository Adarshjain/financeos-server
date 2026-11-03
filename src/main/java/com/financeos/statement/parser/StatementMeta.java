package com.financeos.statement.parser;

import java.time.LocalDate;

public class StatementMeta {
    public String bank;
    public String accountNumber;
    public String ifsc;
    public String holderName;
    public LocalDate periodStart;
    public LocalDate periodEnd;
    public Double openingBalance;
    public Double closingBalance;

    // Added by DocumentFacts (what the document says about itself). Additive: the fields above keep their meaning.
    /** Bank / issuer id by the weight and position of evidence in the document; null when not unique. */
    public String issuerId;
    /** CREDIT_CARD or ACCOUNT by the document's structure; null when it carries no statement structure. */
    public String documentKind;
    /**
     * True only when the document carries card or account statement structure (a kind); false whenever none is
     * found: terms, MITC, KFS, welcome kits, loan statements and anything else without that structure.
     */
    public Boolean isStatement;
    /** The statement date / period end as the document labels it; may fill a gap where periodEnd is null. */
    public LocalDate documentDate;
    /** The product phrase of the title region, as printed. */
    public String productPhrase;
    /** Credit limit as printed in the header region. */
    public Double creditLimit;
    /** The header says the card or account is closed. */
    public boolean closureStated;
}
