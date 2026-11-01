package com.financeos.domain.category;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * taxonomy.json is the source of truth for the fixed category list. The Oracle seed in V119 and the
 * H2 seed in test data.sql are generated from it; these tests fail when either drifts.
 */
@SpringBootTest
class TaxonomySeedTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V119__category_redesign.sql");
    private static final Path H2_SEED = Path.of("src/test/resources/data.sql");

    // cat('key', 'name', 'DIR', out, in, 'icon', excl);   (names may hold '' as an escaped quote)
    private static final Pattern CAT = Pattern.compile(
            "^\\s*cat\\('((?:[^']|'')*)', '((?:[^']|'')*)', '(\\w+)', (NULL|\\d+), (NULL|\\d+), '((?:[^']|'')*)', (\\d)\\);",
            Pattern.MULTILINE);
    // sub('key', 'category', 'name', 'DIR', order);
    private static final Pattern SUB = Pattern.compile(
            "^\\s*sub\\('((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '(\\w+)', (\\d+)\\);",
            Pattern.MULTILINE);

    private static final Pattern H2_CAT = Pattern.compile(
            "INSERT INTO txn_categories \\(.*?\\) VALUES \\('((?:[^']|'')*)', '((?:[^']|'')*)', '(\\w+)', (NULL|\\d+), (NULL|\\d+), '((?:[^']|'')*)', (\\d)\\);");
    private static final Pattern H2_SUB = Pattern.compile(
            "INSERT INTO txn_subcategories \\(.*?\\) VALUES \\('((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '(\\w+)', (\\d+)\\);");

    @Autowired private TxnCategoryRepository categoryRepository;
    @Autowired private TxnSubcategoryRepository subcategoryRepository;

    private static String unq(String s) {
        return s.replace("''", "'");
    }

    private static Integer num(String s) {
        return "NULL".equals(s) ? null : Integer.valueOf(s);
    }

    private static List<String> categoryRows(Pattern p, String sql) {
        List<String> rows = new ArrayList<>();
        Matcher m = p.matcher(sql);
        while (m.find()) {
            rows.add(String.join("|", unq(m.group(1)), unq(m.group(2)), m.group(3), String.valueOf(num(m.group(4))),
                    String.valueOf(num(m.group(5))), unq(m.group(6)), m.group(7)));
        }
        return rows.stream().sorted().toList();
    }

    private static List<String> subRows(Pattern p, String sql) {
        List<String> rows = new ArrayList<>();
        Matcher m = p.matcher(sql);
        while (m.find()) {
            rows.add(String.join("|", unq(m.group(1)), unq(m.group(2)), unq(m.group(3)), m.group(4), m.group(5)));
        }
        return rows.stream().sorted().toList();
    }

    private static List<String> jsonCategoryRows(TaxonomyFile file) {
        return file.categories().stream()
                .map(c -> String.join("|", c.getKey(), c.getName(), c.getDirection().name(),
                        String.valueOf(c.getOutOrder()), String.valueOf(c.getInOrder()), c.getIcon(),
                        c.isExcludedByDefault() ? "1" : "0"))
                .sorted().toList();
    }

    private static List<String> jsonSubRows(TaxonomyFile file) {
        return file.subcategories().stream()
                .map(s -> String.join("|", s.getKey(), s.getCategoryKey(), s.getName(), s.getDirection().name(),
                        String.valueOf(s.getSortOrder())))
                .sorted().toList();
    }

    @Test
    void theJsonHasTheApprovedShape() {
        TaxonomyFile file = TaxonomyFile.load();
        assertEquals(39, file.categories().size());
        assertEquals(142, file.subcategories().size());
    }

    @Test
    void theOracleSeedInV119MatchesTheJson() throws IOException {
        TaxonomyFile file = TaxonomyFile.load();
        String sql = Files.readString(MIGRATION);

        assertEquals(jsonCategoryRows(file), categoryRows(CAT, sql));
        assertEquals(jsonSubRows(file), subRows(SUB, sql));
    }

    @Test
    void theH2SeedMatchesTheJson() throws IOException {
        TaxonomyFile file = TaxonomyFile.load();
        String sql = Files.readString(H2_SEED);

        assertEquals(jsonCategoryRows(file), categoryRows(H2_CAT, sql));
        assertEquals(jsonSubRows(file), subRows(H2_SUB, sql));
    }

    @Test
    void theSeededTablesLoadIntoTheEntitiesAndTheCatalog() {
        TaxonomyFile file = TaxonomyFile.load();
        List<TxnCategory> categories = categoryRepository.findAll();
        List<TxnSubcategory> subs = subcategoryRepository.findAll();

        assertEquals(jsonCategoryRows(file), categories.stream()
                .map(c -> String.join("|", c.getKey(), c.getName(), c.getDirection().name(),
                        String.valueOf(c.getOutOrder()), String.valueOf(c.getInOrder()), c.getIcon(),
                        c.isExcludedByDefault() ? "1" : "0"))
                .sorted().toList());
        assertEquals(jsonSubRows(file), subs.stream()
                .map(s -> String.join("|", s.getKey(), s.getCategoryKey(), s.getName(), s.getDirection().name(),
                        String.valueOf(s.getSortOrder())))
                .sorted().toList());

        CategoryCatalog fromDb = new CategoryCatalog(categoryRepository, subcategoryRepository);
        CategoryCatalog fromJson = CategoryCatalog.of(file.categories(), file.subcategories());
        assertEquals(fromJson.all(), fromDb.all());
    }

    @Test
    void v119BacksUpTheRulesBeforeTheWipe() throws IOException {
        String sql = Files.readString(MIGRATION);
        int backup = sql.indexOf("category_rules_bak_20261101 AS SELECT * FROM category_rules");
        int backup2 = sql.indexOf("category_rule_categories_bak_20261101 AS SELECT * FROM category_rule_categories");
        int wipe = sql.indexOf("DELETE FROM category_rules");
        assertTrue(backup > 0 && backup2 > 0, "both rule tables are backed up");
        assertTrue(backup < wipe && backup2 < wipe, "the backups come before the delete");
    }
}
