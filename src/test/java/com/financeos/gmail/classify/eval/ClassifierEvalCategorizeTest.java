package com.financeos.gmail.classify.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.categorization.builtin.BuiltinCategorizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The categorize replay mode, on a tiny made-up dump. */
class ClassifierEvalCategorizeTest {

    @Test
    void writesOnlyIdCategoryStepKeyAndSurvivesBadRows(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("dump.jsonl");
        Path extract = dir.resolve("extract.jsonl");
        Path out = dir.resolve("out.jsonl");
        Files.write(dump, List.of(
                "{\"id\":\"m1\",\"from\":\"Bank <alerts@examplebank.test>\",\"subject\":\"Alert\",\"bodyText\":\"Dear Customer, Rs 100 spent at Zyxqsecret Cafe\"}",
                "{\"id\":\"m2\",\"from\":\"Bank <alerts@examplebank.test>\",\"subject\":\"Alert\",\"bodyText\":\"Rs 5 spent\"}",
                "{\"id\":\"m3\",\"from\":\"Bank <alerts@examplebank.test>\",\"subject\":\"Alert\",\"bodyText\":\"Rs 6 spent\"}"));
        Files.write(extract, List.of(
                "{\"id\":\"m1\",\"kind\":\"ALERT\",\"amount\":\"100.00\",\"direction\":\"DEBIT\",\"counterparty\":\"Zyxqsecret Cafe\"}",
                "{\"id\":\"m2\",\"kind\":\"ALERT\",\"amount\":\"not-a-number\",\"direction\":\"DEBIT\",\"counterparty\":\"Zyxq\"}",
                "{\"id\":\"m3\",\"kind\":\"ALERT\",\"amount\":\"6.00\",\"direction\":\"SIDEWAYS\",\"counterparty\":\"Zyxq\"}"));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Integer> byStep = ClassifierEvalRunner.categorizeReplay(dump, extract, out, mapper, new BuiltinCategorizer());
        assertEquals(2, byStep.get("ERROR"));
        List<String> lines = Files.readAllLines(out);
        assertEquals(1, lines.size());
        JsonNode row = mapper.readTree(lines.get(0));
        Set<String> fields = new TreeSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "category", "step", "key"), fields);
        assertEquals("Dining", row.get("category").asText());
        String all = String.join("\n", lines);
        assertFalse(all.toLowerCase().contains("zyxqsecret"), "no counterparty or mail text in the output");
        assertTrue(all.contains("m1"));
    }
}
