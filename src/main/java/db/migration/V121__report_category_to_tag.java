package db.migration;

import com.financeos.domain.report.ReportCategoryToTagRewrite;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * V121: the per-user categories became tags (V119) and {@code category} now names the fixed category list,
 * so every saved transactions report that used {@code category} as a dimension, split, filter, column or sort
 * is rewritten to {@code tag} (same data, same numbers). {@link ReportCategoryToTagRewrite} holds the rules.
 * Java rather than SQL because the definition is JSON in a CLOB and only field references may change.
 *
 * <p>Dashboards need no rewrite: a report widget points at its report by id and a built-in widget's params
 * never name a category field. Only changed rows are written and {@code updated_at} is left alone.
 *
 * <p>Idempotent: a definition with no {@code category} reference left is untouched. Flyway runs it once; a
 * manual re-run after reports legitimately use the new {@code category} would rewrite those too, so don't.
 * TZ-safe: no date/time is read or written.
 */
public class V121__report_category_to_tag extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V121__report_category_to_tag.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        // Read everything first, then write: no update while the result set is open.
        Map<String, String> rewritten = new HashMap<>();
        int scanned = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, definition FROM reports WHERE datasource = 'transactions' "
                     + "AND definition LIKE '%\"category\"%'")) {
            while (rs.next()) {
                scanned++;
                String updated = ReportCategoryToTagRewrite.rewrite(clob(rs, 2));
                if (updated != null) {
                    rewritten.put(rs.getString(1), updated);
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
        log.info("V121 report category to tag: {} report(s) scanned, {} rewritten", scanned, rewritten.size());
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
