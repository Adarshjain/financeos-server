package com.financeos.core.observability;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EventsTest {

    @Test
    void testNoDuplicateEventConstants() throws IllegalAccessException {
        Field[] fields = Events.class.getDeclaredFields();
        Set<String> values = new HashSet<>();

        for (Field field : fields) {
            if (Modifier.isPublic(field.getModifiers()) &&
                Modifier.isStatic(field.getModifiers()) &&
                Modifier.isFinal(field.getModifiers()) &&
                field.getType().equals(String.class)) {

                String value = (String) field.get(null);
                assertNotNull(value, "Event constant value must not be null: " + field.getName());
                assertFalse(value.isBlank(), "Event constant value must not be blank: " + field.getName());
                assertTrue(values.add(value), "Duplicate event constant value found: " + value + " in field " + field.getName());
            }
        }
        assertTrue(values.size() > 10, "Events class must define event constants");
    }

    @Test
    void testAllEventConstantsAreReferencedInMainSource() throws Exception {
        Field[] fields = Events.class.getDeclaredFields();
        Path mainSrc = Paths.get("src/main/java");
        assertTrue(Files.exists(mainSrc), "src/main/java directory must exist");

        List<String> javaFilesContent;
        try (Stream<Path> stream = Files.walk(mainSrc)) {
            javaFilesContent = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith("Events.java"))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .collect(Collectors.toList());
        }

        for (Field field : fields) {
            if (Modifier.isPublic(field.getModifiers()) &&
                Modifier.isStatic(field.getModifiers()) &&
                Modifier.isFinal(field.getModifiers()) &&
                field.getType().equals(String.class)) {

                String fieldName = field.getName();
                boolean isReferenced = javaFilesContent.stream()
                        .anyMatch(content -> content.contains("Events." + fieldName));

                assertTrue(isReferenced, "Event constant Events." + fieldName + " is declared but never referenced in src/main/java");
            }
        }
    }
}
