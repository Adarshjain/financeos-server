ALTER TABLE corporate_actions ADD fractional_cash_in_lieu NUMBER(19,4);
ALTER TABLE corporate_actions ADD CONSTRAINT chk_corp_actions_frac_cash
    CHECK (fractional_cash_in_lieu IS NULL OR fractional_cash_in_lieu >= 0);
