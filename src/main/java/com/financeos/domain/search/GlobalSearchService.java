package com.financeos.domain.search;

import com.financeos.api.epf.dto.EpfEmployerResponse;
import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.api.search.dto.GlobalSearchResponse;
import com.financeos.api.search.dto.SearchAccountHit;
import com.financeos.api.search.dto.SearchCategoryHit;
import com.financeos.api.search.dto.SearchRuleHit;
import com.financeos.api.search.dto.SearchDashboardHit;
import com.financeos.api.search.dto.SearchHoldingHit;
import com.financeos.api.search.dto.SearchLoanHit;
import com.financeos.api.search.dto.SearchOpenBill;
import com.financeos.api.search.dto.SearchPersonHit;
import com.financeos.api.search.dto.SearchReportHit;
import com.financeos.api.search.dto.SearchRetirementHit;
import com.financeos.api.search.dto.SearchTagHit;
import com.financeos.api.search.dto.SearchUpcomingHit;
import com.financeos.api.search.dto.SearchTransactionHit;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.epf.EpfEmployerStatus;
import com.financeos.domain.epf.EpfService;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.transaction.TransactionListQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Global search: twelve small capped queries (5 rows each, no counts), run in order inside one read-only
 * transaction. Every query is scoped by {@code user_id}; small groups list prefix matches first, then
 * alphabetically; transactions list newest first (served by idx_txn_user_date).
 */
@Service
@Transactional(readOnly = true)
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    static final int MIN_LENGTH = 2;
    /** Transactions and holdings are only searched from this length (too noisy at 2 characters). */
    static final int HEAVY_MIN_LENGTH = 3;
    static final int MAX_LENGTH = 100;

    private static final String LIKE = " ESCAPE '\\'";
    private static final String PREFIX_ORDER =
            " ORDER BY CASE WHEN LOWER(%1$s) LIKE :prefix" + LIKE + " THEN 0 ELSE 1 END, LOWER(%1$s), %2$s"
                    + " FETCH FIRST 5 ROWS ONLY";

    private static final String ACCOUNTS_SQL = """
            SELECT a.id, a.name, a.bank, a.type,
                   COALESCE(
                       (SELECT MIN(c.last4) FROM cards c
                         WHERE c.account_id = a.id AND c.user_id = :userId AND LOWER(c.last4) LIKE :q""" + LIKE + """
            ),
                       (SELECT c.last4 FROM cards c WHERE c.account_id = a.id AND c.user_id = :userId
                         ORDER BY c.created_at, c.id FETCH FIRST 1 ROW ONLY)) AS last4
              FROM accounts a
             WHERE a.user_id = :userId
               AND (LOWER(a.name) LIKE :q""" + LIKE + " OR LOWER(a.bank) LIKE :q" + LIKE + """

                    OR EXISTS (SELECT 1 FROM cards c
                                WHERE c.account_id = a.id AND c.user_id = :userId AND LOWER(c.last4) LIKE :q""" + LIKE + "))"
            + PREFIX_ORDER.formatted("a.name", "a.id");

    private static final String HOLDINGS_SQL = """
            SELECT i.id, COALESCE(o.name, i.name) AS name, COALESCE(o.symbol, i.symbol) AS symbol
              FROM instruments i
              LEFT JOIN user_instrument_overrides o ON o.instrument_id = i.id AND o.user_id = :userId
             WHERE EXISTS (SELECT 1 FROM holdings h WHERE h.user_id = :userId AND h.instrument_id = i.id)
               AND (LOWER(COALESCE(o.name, i.name)) LIKE :q""" + LIKE
            + " OR LOWER(COALESCE(o.symbol, i.symbol)) LIKE :q" + LIKE + ")"
            + PREFIX_ORDER.formatted("COALESCE(o.name, i.name)", "i.id");

    private static final String PEOPLE_SQL = "SELECT id, name FROM counterparties WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    private static final String LOANS_SQL = "SELECT id, name, lender FROM loans WHERE user_id = :userId"
            + " AND (LOWER(name) LIKE :q" + LIKE + " OR LOWER(lender) LIKE :q" + LIKE
            + " OR LOWER(loan_account_number) LIKE :q" + LIKE + ")" + PREFIX_ORDER.formatted("name", "id");

    private static final String REPORTS_SQL = "SELECT id, name, type FROM reports WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    private static final String DASHBOARDS_SQL = "SELECT id, name, is_default FROM dashboards WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    /** Fixed taxonomy: categories and sub-categories in one query, prefix matches first. */
    private static final String CATEGORIES_SQL = """
            SELECT item_key, name, parent_name FROM (
                SELECT c.category_key AS item_key, c.name AS name, CAST(NULL AS VARCHAR2(100)) AS parent_name
                  FROM txn_categories c
                UNION ALL
                SELECT s.subcategory_key, s.name, p.name
                  FROM txn_subcategories s JOIN txn_categories p ON p.category_key = s.category_key
            ) WHERE LOWER(name) LIKE :q""" + LIKE
            + PREFIX_ORDER.formatted("name", "item_key");

    private static final String RULES_SQL = """
            SELECT r.id, r.merchant_key, c.name AS category_name
              FROM category_rules r JOIN txn_categories c ON c.category_key = r.category_key
             WHERE r.user_id = :userId
               AND (LOWER(r.merchant_key) LIKE :q""" + LIKE + " OR LOWER(r.display_name) LIKE :q" + LIKE + ")"
            + PREFIX_ORDER.formatted("r.merchant_key", "r.id");

    /**
     * EPF (one row when the user has EPF data) and NPS (one row per account) in one query. Keyword matches
     * ("epf", "provident fund", "nps", "vatsalya") test the typed text against the constant. Only last 4 of the
     * UAN and the stored pran_last4 are matched; the encrypted PRAN is never selected. "Current employer" is the
     * newest-joined employer that has not left, been settled or transferred out.
     */
    private static final String RETIREMENT_SQL = """
            SELECT kind, account_id, title, subtitle, pran_last4, holder_name, matched_scheme FROM (
                SELECT 'EPF' AS kind, CAST(NULL AS VARCHAR2(36)) AS account_id, 'EPF' AS title,
                       CAST(NULL AS VARCHAR2(200)) AS subtitle,
                       CAST(NULL AS VARCHAR2(4)) AS pran_last4, CAST(NULL AS VARCHAR2(200)) AS holder_name,
                       CAST(NULL AS VARCHAR2(200)) AS matched_scheme
                  FROM users u
                 WHERE u.id = :userId
                   AND (EXISTS (SELECT 1 FROM epf_employer e WHERE e.user_id = :userId)
                        OR EXISTS (SELECT 1 FROM epf_entry x WHERE x.user_id = :userId))
                   AND ('epf' LIKE :q""" + LIKE + " OR 'provident fund' LIKE :q" + LIKE + """

                        OR EXISTS (SELECT 1 FROM epf_employer e
                                    WHERE e.user_id = :userId
                                      AND (LOWER(e.name) LIKE :q""" + LIKE + """

                                           OR LOWER(SUBSTR(e.uan, -4)) LIKE :q""" + LIKE + """
)))
                UNION ALL
                SELECT 'NPS', a.id,
                       CASE WHEN a.relation = 'CHILD' AND a.sector = 'VATSALYA' THEN 'NPS Vatsalya' ELSE 'NPS' END,
                       CAST(NULL AS VARCHAR2(200 CHAR)), a.pran_last4,
                       CASE WHEN a.relation = 'CHILD' THEN a.holder_name END,
                       (SELECT MIN(i.name) FROM nps_scheme s JOIN instruments i ON i.id = s.instrument_id
                         WHERE LOWER(i.name) LIKE :q""" + LIKE + """

                           AND (EXISTS (SELECT 1 FROM nps_allocation al
                                         WHERE al.account_id = a.id AND al.scheme_code = s.scheme_code)
                                OR EXISTS (SELECT 1 FROM nps_entry_line l JOIN nps_entry en ON en.id = l.entry_id
                                            WHERE en.account_id = a.id AND en.user_id = :userId
                                              AND l.scheme_code = s.scheme_code)))
                  FROM nps_account a
                 WHERE a.user_id = :userId
                   AND ('nps' LIKE :q""" + LIKE + """

                        OR ('vatsalya' LIKE :q""" + LIKE + """
 AND a.relation = 'CHILD' AND a.sector = 'VATSALYA')
                        OR LOWER(a.holder_name) LIKE :q""" + LIKE + """

                        OR LOWER(a.pran_last4) LIKE :q""" + LIKE + """

                        OR EXISTS (SELECT 1 FROM nps_scheme s JOIN instruments i ON i.id = s.instrument_id
                                    WHERE LOWER(i.name) LIKE :q""" + LIKE + """

                                      AND (EXISTS (SELECT 1 FROM nps_allocation al
                                                    WHERE al.account_id = a.id AND al.scheme_code = s.scheme_code)
                                           OR EXISTS (SELECT 1 FROM nps_entry_line l JOIN nps_entry en ON en.id = l.entry_id
                                                       WHERE en.account_id = a.id AND en.user_id = :userId
                                                         AND l.scheme_code = s.scheme_code))))
            )""" + PREFIX_ORDER.formatted("title", "account_id");

    /** What Upcoming lists as "recurring": the tracked series (loan-backed ones are already searchable as loans). */
    private static final String UPCOMING_SQL = "SELECT id, display_name, next_due_on, amount, kind FROM recurring_series"
            + " WHERE user_id = :userId AND status = 'TRACKED' AND loan_id IS NULL"
            + " AND (LOWER(display_name) LIKE :q" + LIKE + " OR LOWER(merchant_key) LIKE :q" + LIKE + ")"
            + PREFIX_ORDER.formatted("display_name", "id");

    private static final String TAGS_SQL = """
            SELECT g.id, g.name,
                   (SELECT COUNT(*) FROM transaction_tags tt
                      JOIN transactions t ON t.id = tt.transaction_id AND t.user_id = :userId
                     WHERE tt.tag_id = g.id) AS txn_count
              FROM tags g
             WHERE g.user_id = :userId AND LOWER(g.name) LIKE :q""" + LIKE
            + PREFIX_ORDER.formatted("g.name", "g.id");

    private final NamedParameterJdbcTemplate jdbc;

    private final CardBillService cardBillService;
    private final EpfService epfService;

    public GlobalSearchService(NamedParameterJdbcTemplate jdbc, CardBillService cardBillService, EpfService epfService) {
        this.epfService = epfService;
        this.jdbc = jdbc;
        this.cardBillService = cardBillService;
    }

    public GlobalSearchResponse search(String rawQuery) {
        String text = rawQuery == null ? "" : rawQuery.trim();
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "Search text must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.");
        }
        return Timing.call(Ops.SEARCH_QUERY, () -> runSearch(text));
    }

    private GlobalSearchResponse runSearch(String text) {
        long started = System.nanoTime();
        String lowered = text.toLowerCase(Locale.ROOT);
        String escaped = SqlPredicates.escapeLike(lowered);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", UserContext.getCurrentUserId().toString())
                .addValue("q", "%" + escaped + "%")
                .addValue("prefix", escaped + "%");

        List<SearchAccountHit> accounts = withOpenBills(jdbc.query(ACCOUNTS_SQL, params, (rs, n) -> new SearchAccountHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("bank"),
                AccountType.valueOf(rs.getString("type")), rs.getString("last4"), null)));

        boolean heavy = text.length() >= HEAVY_MIN_LENGTH;
        List<SearchTransactionHit> transactions = !heavy ? List.<SearchTransactionHit>of()
                : jdbc.query(transactionsSql(text, params), params,
                (rs, n) -> new SearchTransactionHit(UUID.fromString(rs.getString("id")),
                        rs.getDate("transaction_date").toLocalDate(), rs.getString("description"),
                        UUID.fromString(rs.getString("account_id")), rs.getString("account_name"),
                        rs.getBigDecimal("signed_amount")));

        List<SearchHoldingHit> holdings = !heavy ? List.<SearchHoldingHit>of()
                : jdbc.query(HOLDINGS_SQL, params, (rs, n) -> new SearchHoldingHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("symbol")));

        List<SearchPersonHit> people = jdbc.query(PEOPLE_SQL, params,
                (rs, n) -> new SearchPersonHit(UUID.fromString(rs.getString("id")), rs.getString("name")));

        List<SearchLoanHit> loans = jdbc.query(LOANS_SQL, params, (rs, n) -> new SearchLoanHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("lender")));

        List<SearchReportHit> reports = jdbc.query(REPORTS_SQL, params, (rs, n) -> new SearchReportHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), ReportType.valueOf(rs.getString("type"))));

        List<SearchDashboardHit> dashboards = jdbc.query(DASHBOARDS_SQL, params, (rs, n) -> new SearchDashboardHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getInt("is_default") != 0));

        List<SearchCategoryHit> categories = jdbc.query(CATEGORIES_SQL, params, (rs, n) -> new SearchCategoryHit(
                rs.getString("item_key"), rs.getString("name"), rs.getString("parent_name")));

        List<SearchRuleHit> rules = jdbc.query(RULES_SQL, params, (rs, n) -> new SearchRuleHit(
                UUID.fromString(rs.getString("id")), rs.getString("merchant_key"), rs.getString("category_name")));

        List<SearchRetirementHit> retirement = withEpfEmployer(jdbc.query(RETIREMENT_SQL, params, (rs, n) -> new SearchRetirementHit(
                rs.getString("kind"), rs.getString("account_id") == null ? null : UUID.fromString(rs.getString("account_id")),
                rs.getString("title"), rs.getString("subtitle"), rs.getString("pran_last4"),
                rs.getString("holder_name"), rs.getString("matched_scheme"))));

        List<SearchUpcomingHit> upcoming = jdbc.query(UPCOMING_SQL, params, (rs, n) -> new SearchUpcomingHit(
                UUID.fromString(rs.getString("id")), rs.getString("display_name"),
                rs.getDate("next_due_on").toLocalDate(), rs.getBigDecimal("amount"), rs.getString("kind")));

        List<SearchTagHit> tags = jdbc.query(TAGS_SQL, params, (rs, n) -> new SearchTagHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getLong("txn_count")));

        log.debug("Global search: accounts={} transactions={} holdings={} people={} loans={} reports={} dashboards={}"
                        + " categories={} rules={} retirement={} upcoming={} tags={} in {} ms",
                accounts.size(), transactions.size(), holdings.size(), people.size(), loans.size(), reports.size(),
                dashboards.size(), categories.size(), rules.size(), retirement.size(), upcoming.size(), tags.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new GlobalSearchResponse(accounts, transactions, holdings, people, loans, reports, dashboards,
                categories, rules, retirement, upcoming, tags);
    }

    /**
     * Attaches each credit card's unpaid bill. The bills come from the same batched read the Inbox and
     * Upcoming use ({@link CardBillService#listBills}: one live bill per open card), and "unpaid" is
     * the status the app offers Mark paid for (OPEN, PARTIAL, OVERDUE), so the figures cannot drift.
     * Skipped entirely when no hit is a credit card.
     */
    private List<SearchAccountHit> withOpenBills(List<SearchAccountHit> hits) {
        if (hits.stream().noneMatch(h -> h.type() == AccountType.credit_card)) {
            return hits;
        }
        Map<UUID, SearchOpenBill> byCard = new HashMap<>();
        for (CardBill bill : cardBillService.listBills(UserContext.getCurrentUserId())) {
            boolean unpaid = bill.status() == BillStatus.OPEN || bill.status() == BillStatus.PARTIAL
                    || bill.status() == BillStatus.OVERDUE;
            if (unpaid && bill.statementId() != null && bill.remainingAmount() != null) {
                byCard.put(bill.accountId(),
                        new SearchOpenBill(bill.statementId(), bill.remainingAmount(), bill.paymentDueDate()));
            }
        }
        return hits.stream().map(h -> h.type() == AccountType.credit_card
                ? new SearchAccountHit(h.id(), h.name(), h.bank(), h.type(), h.last4(), byCard.get(h.id()))
                : h).toList();
    }

    /** Newest first; a numeric query also matches the amount's digits, as the transactions list search does. */
    private static String transactionsSql(String text, MapSqlParameterSource params) {
        String amountClause = "";
        String amountDigits = TransactionListQueryBuilder.numericSearchText(text);
        if (amountDigits != null) {
            params.addValue("qAmt", "%" + amountDigits + "%");
            amountClause = " OR TO_CHAR(ABS(t.amount), '" + TransactionListQueryBuilder.AMOUNT_TEXT_FORMAT
                    + "') LIKE :qAmt";
        }
        return "SELECT t.id, t.transaction_date, COALESCE(t.description, t.sourced_description) AS description,"
                + " t.account_id, a.name AS account_name,"
                + " (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount"
                + " FROM transactions t JOIN accounts a ON a.id = t.account_id AND a.user_id = :userId"
                + " WHERE t.user_id = :userId AND (LOWER(t.description) LIKE :q" + LIKE
                + " OR LOWER(t.sourced_description) LIKE :q" + LIKE + amountClause + ")"
                + " ORDER BY t.transaction_date DESC, t.id DESC FETCH FIRST 5 ROWS ONLY";
    }

    /** Sets the EPF hit's subtitle to the employer the EPF screen calls current; read only when EPF matched. */
    private List<SearchRetirementHit> withEpfEmployer(List<SearchRetirementHit> hits) {
        if (hits.stream().noneMatch(h -> "EPF".equals(h.kind()))) {
            return hits;
        }
        String current = epfService.getEmployers().stream()
                .filter(e -> e.status() == EpfEmployerStatus.CURRENT)
                .map(EpfEmployerResponse::name)
                .findFirst().orElse(null);
        return hits.stream()
                .map(h -> "EPF".equals(h.kind()) ? new SearchRetirementHit(h.kind(), h.accountId(), h.title(), current,
                        h.pranLast4(), h.holderName(), h.matchedScheme()) : h)
                .toList();
    }
}
