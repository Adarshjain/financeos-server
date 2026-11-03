package com.financeos.gmail.accountdetect.unlock;

import com.financeos.domain.account.BankCatalog;
import com.financeos.gmail.accountdetect.BankDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementPasswordRulesTest {

    @Test
    void everyBankKeyIsABankDirectoryKeyAndEveryKindIsKnown() {
        StatementPasswordRules rules = StatementPasswordRules.bundled();
        assertThat(rules.entries()).isNotEmpty();
        List<String> directoryKeys = BankDirectory.allDomains().stream().map(BankDirectory::bankForDomain).toList();
        for (StatementPasswordRules.Entry e : rules.entries()) {
            assertThat(BankCatalog.isKnown(e.bank()) || directoryKeys.contains(e.bank())).as("bank key " + e.bank()).isTrue();
            assertThat(e.kind()).isIn("account", "credit_card");
            assertThat(e.confidence()).isIn("official", "secondary", "unknown");
            assertThat(e.patterns()).isNotEmpty();
        }
    }

    @Test
    void patternsComeInTableOrderPerBankAndKind() {
        StatementPasswordRules rules = StatementPasswordRules.bundled();
        assertThat(rules.patterns("icici", "credit_card")).containsExactly("NAME4L+DDMM", "CARD4");
        assertThat(rules.patterns("icici", "account")).first().isEqualTo("NAME4L+DDMM");
        assertThat(rules.patterns("hdfc", "credit_card")).startsWith("NAME4U+CARD4", "NAME4L+DDMM");
        // no kind: both kinds' patterns, deduplicated
        assertThat(rules.patterns("icici", null)).containsExactly("NAME4L+DDMM", "ACCNO", "CARD4");
        assertThat(rules.patterns("nobank", null)).isEmpty();
    }
}
