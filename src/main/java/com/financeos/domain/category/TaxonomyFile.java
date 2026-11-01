package com.financeos.domain.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code categorize/taxonomy.json}, the source of truth for the fixed category list. The
 * database seed (V119) is generated from it and a test keeps the two in step.
 */
public final class TaxonomyFile {

    public static final String RESOURCE = "categorize/taxonomy.json";

    private final List<TxnCategory> categories = new ArrayList<>();
    private final List<TxnSubcategory> subcategories = new ArrayList<>();

    private TaxonomyFile() {
    }

    public static TaxonomyFile load() {
        try (InputStream in = TaxonomyFile.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            TaxonomyFile file = new TaxonomyFile();
            for (JsonNode c : root.get("categories")) {
                String key = c.get("key").asText();
                file.categories.add(new TxnCategory(key, c.get("name").asText(),
                        CategoryDirection.valueOf(c.get("direction").asText()),
                        c.get("outOrder").isNull() ? null : c.get("outOrder").asInt(),
                        c.get("inOrder").isNull() ? null : c.get("inOrder").asInt(),
                        c.get("icon").asText(), c.get("excludedByDefault").asBoolean()));
                for (JsonNode s : c.get("subs")) {
                    file.subcategories.add(new TxnSubcategory(s.get("key").asText(), key, s.get("name").asText(),
                            CategoryDirection.valueOf(s.get("direction").asText()), s.get("order").asInt()));
                }
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<TxnCategory> categories() {
        return categories;
    }

    public List<TxnSubcategory> subcategories() {
        return subcategories;
    }
}
