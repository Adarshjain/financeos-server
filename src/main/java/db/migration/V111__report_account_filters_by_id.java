package db.migration;

import com.financeos.domain.report.AccountFilterIdRewrite;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V111: saved report filters on the transactions {@code account} field hold account ids, not names, so
 * renaming an account no longer silently empties a report. Each transactions report's top-level
 * {@code account} filter (is / is_not / in / not_in) has its names replaced by the report owner's account
 * ids ({@link AccountFilterIdRewrite} has the rules: a name shared by several accounts or unknown stays a name, shape kept).
 * Only changed rows are written, and {@code updated_at} is left alone.
 *
 * <p>Idempotent: ids are never rewritten, so a re-run finds nothing to change. TZ-safe: no date/time is
 * read or written.
 */
public class V111__report_account_filters_by_id extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V111__report_account_filters_by_id.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        Map<String, Map<String, List<String>>> accountsByUser = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT user_id, name, id FROM accounts")) {
            while (rs.next()) {
                accountsByUser.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
            }
        }

        // Read everything first, then write: no update while the result set is open.
        Map<String, String> rewritten = new HashMap<>();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger namesLeft = new AtomicInteger();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, user_id, definition FROM reports WHERE datasource = 'transactions' "
                     + "AND definition LIKE '%\"account\"%'")) {
            while (rs.next()) {
                scanned.incrementAndGet();
                String id = rs.getString(1);
                Map<String, List<String>> idsByName = accountsByUser.getOrDefault(rs.getString(2), Map.of());
                String updated = AccountFilterIdRewrite.rewrite(clob(rs, 3), idsByName, namesLeft);
                if (updated != null) {
                    rewritten.put(id, updated);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE reports SET definition = ? WHERE id = ?")) {
            for (Map.Entry<String, String> e : rewritten.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                ps.executeUpdate();
            }
        }
        log.info("V111 report account filters by id: {} report(s) scanned, {} rewritten, {} account name(s) left as names",
                scanned.get(), rewritten.size(), namesLeft.get());
    }

    private static String clob(ResultSet rs, int column) throws Exception {
        try (Reader r = rs.getCharacterStream(column)) {
            if (r == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }
}
