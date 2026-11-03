package com.financeos.gmail.accountdetect.scan;

import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Statements the app already parsed and stored, as detector statement content (source DB): bank, last4, kind,
 * period and a credit-limit bucket. Filters by user id explicitly (no Hibernate user filter needed).
 */
public final class DbStatements {

    private DbStatements() {}

    /** @param rows rows read, used and unmapped (no bank/last4/period) */
    public record Loaded(List<StatementContent> statements, int rows, int used, int unmapped) {}

    public static Loaded load(JdbcTemplate jdbc, UUID userId) {
        List<StatementContent> statements = new ArrayList<>();
        int[] db = new int[3]; // rows, used, unmapped
        jdbc.query("SELECT s.statement_type, s.bank_name, s.account_number_masked, s.period_end, a.bank, d.credit_limit "
                        + "FROM statements s JOIN accounts a ON a.id = s.account_id "
                        + "LEFT JOIN statement_credit_card_details d ON d.statement_id = s.id "
                        + "WHERE s.user_id = ? AND (s.verdict IS NULL OR s.verdict <> 'REJECTED')",
                rs -> {
                    db[0]++;
                    String masked = rs.getString(3);
                    String digits = masked == null ? "" : masked.replaceAll("\\D", "");
                    String bank = dbBank(rs.getString(5), rs.getString(2));
                    java.sql.Date pe = rs.getDate(4);
                    if (digits.length() < 4 || bank == null || pe == null) {
                        db[2]++;
                        return;
                    }
                    String type = rs.getString(1);
                    Kind kind = "credit_card".equals(type) ? Kind.CREDIT_CARD : "bank_account".equals(type) ? Kind.ACCOUNT : null;
                    java.math.BigDecimal limit = rs.getBigDecimal(6);
                    statements.add(new StatementContent("DB", bank, digits.substring(digits.length() - 4), kind,
                            YearMonth.from(pe.toLocalDate()), null, limit == null ? null : StatementContent.bucket(limit.doubleValue()), List.of(), false));
                    db[1]++;
                }, userId.toString());
        return new Loaded(statements, db[0], db[1], db[2]);
    }

    static String dbBank(String accountBank, String bankName) {
        if (accountBank != null) {
            String b = accountBank.trim().toLowerCase(Locale.ROOT);
            for (String d : BankDirectory.allDomains()) {
                String id = BankDirectory.bankForDomain(d);
                if (b.equals(id)) return id;
            }
        }
        if (bankName != null) {
            List<String> found = BankDirectory.issuersIn(bankName.toLowerCase(Locale.ROOT));
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }
}
