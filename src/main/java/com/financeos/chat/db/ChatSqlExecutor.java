package com.financeos.chat.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChatSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatSqlExecutor.class);
    private static final Pattern ORA_PATTERN = Pattern.compile("ORA-\\d{5}");

    private final java.util.function.Supplier<DataSource> dataSourceSupplier;
    private final ChatSqlValidator validator;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatSqlExecutor(ChatConnectionProvider connectionProvider,
                           ChatSqlValidator validator,
                           ChatProperties chatProperties,
                           ObjectMapper objectMapper) {
        this(connectionProvider::dataSource, validator, chatProperties, objectMapper);
    }

    // Direct-DataSource constructor for tests.
    public ChatSqlExecutor(DataSource chatDataSource,
                           ChatSqlValidator validator,
                           ChatProperties chatProperties,
                           ObjectMapper objectMapper) {
        this(() -> chatDataSource, validator, chatProperties, objectMapper);
    }

    private ChatSqlExecutor(java.util.function.Supplier<DataSource> dataSourceSupplier,
                            ChatSqlValidator validator,
                            ChatProperties chatProperties,
                            ObjectMapper objectMapper) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.validator = validator;
        this.chatProperties = chatProperties;
        this.objectMapper = objectMapper;
    }

    public String execute(String rawSql) {
        String validatedSql = validator.validate(rawSql);

        int rowCap = chatProperties.getLoop().getSqlRowCap();
        int timeoutSeconds = chatProperties.getLoop().getSqlTimeoutSeconds();
        int charCap = chatProperties.getLoop().getResultCharCap();
        String appSchema = chatProperties.getAppSchema();

        String wrappedSql = "SELECT * FROM ( " + validatedSql + " ) FETCH FIRST " + (rowCap + 1) + " ROWS ONLY";

        List<String> columnNames = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncatedAtRowCap = false;

        try (ChatDbSession session = new ChatDbSession(dataSourceSupplier.get(), appSchema)) {
            Connection conn = session.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeoutSeconds);

                try (ResultSet rs = stmt.executeQuery(wrappedSql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int columnCount = md.getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        columnNames.add(md.getColumnLabel(i).toLowerCase());
                    }

                    int count = 0;
                    while (rs.next()) {
                        count++;
                        if (count > rowCap) {
                            truncatedAtRowCap = true;
                            break;
                        }

                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Object val = rs.getObject(i);
                            row.add(convertCellValue(val));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Chat SQL execution failed for query: [{}] - Error: {}", rawSql, e.getMessage(), e);
            throw genericizeException(e);
        } catch (Exception e) {
            log.warn("Chat SQL execution unexpected error for query: [{}] - Error: {}", rawSql, e.getMessage(), e);
            throw new ChatSqlFailedException("Query execution failed: internal error", e);
        }

        return serializeResult(columnNames, rows, truncatedAtRowCap, charCap);
    }

    private Object convertCellValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (val instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (val instanceof LocalDate ld) {
            return ld.toString();
        }
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Number n) {
            return n;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return val.toString();
    }

    private String serializeResult(List<String> columns, List<List<Object>> rows, boolean truncatedAtRowCap, int charCap) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode colsNode = root.putArray("columns");
        columns.forEach(colsNode::add);

        ArrayNode rowsNode = root.putArray("rows");
        for (List<Object> row : rows) {
            ArrayNode rowNode = rowsNode.addArray();
            for (Object cell : row) {
                if (cell == null) {
                    rowNode.addNull();
                } else if (cell instanceof BigDecimal bd) {
                    rowNode.add(bd);
                } else if (cell instanceof Integer i) {
                    rowNode.add(i);
                } else if (cell instanceof Long l) {
                    rowNode.add(l);
                } else if (cell instanceof Double d) {
                    rowNode.add(d);
                } else if (cell instanceof Boolean b) {
                    rowNode.add(b);
                } else {
                    rowNode.add(cell.toString());
                }
            }
        }

        root.put("rowCount", rows.size());
        root.put("truncatedAtRowCap", truncatedAtRowCap);
        root.put("truncated", false);

        String json = root.toString();
        if (json.length() <= charCap) {
            return json;
        }

        // Truncate rows if JSON exceeds charCap
        while (rowsNode.size() > 0 && root.toString().length() > charCap) {
            rowsNode.remove(rowsNode.size() - 1);
        }

        root.put("rowCount", rowsNode.size());
        root.put("truncated", true);
        root.put("truncationNote", "Result truncated — aggregate in SQL instead of selecting raw rows.");

        return root.toString();
    }

    private ChatSqlFailedException genericizeException(SQLException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        int errorCode = e.getErrorCode();

        // Query Timeout
        if (errorCode == 1013 || msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("query cancelled")) {
            return new ChatSqlFailedException("Query timed out (5s). Simplify or aggregate.");
        }

        // ORA-00942: table or view does not exist
        if (errorCode == 942 || msg.contains("ORA-00942") || msg.toLowerCase().contains("not found")) {
            return new ChatSqlFailedException("Unknown table/view — use only the views listed in the schema reference.");
        }

        // Extract ORA code if present
        Matcher matcher = ORA_PATTERN.matcher(msg);
        if (matcher.find()) {
            return new ChatSqlFailedException("Query failed: " + matcher.group());
        }

        return new ChatSqlFailedException("Query failed: database error");
    }
}
