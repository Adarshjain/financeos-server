package com.financeos.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiSpecCoverageTest {

    private static final Pattern PATH_VAR_REGEX_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+):[^}]+}");

    @Test
    void allControllerEndpointsMustBeDocumentedInApiSpec() throws Exception {
        File specFile = new File("api-spec.yaml");
        if (!specFile.exists()) {
            specFile = new File("financeos-server/api-spec.yaml");
        }
        assertTrue(specFile.exists(), "api-spec.yaml must exist at repository root");

        Map<String, Object> yamlMap;
        try (InputStream in = new FileInputStream(specFile)) {
            Yaml yaml = new Yaml();
            yamlMap = yaml.load(in);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) yamlMap.get("paths");
        assertTrue(paths != null && !paths.isEmpty(), "api-spec.yaml must contain paths");

        Set<String> documentedPaths = paths.keySet();
        Set<String> controllerPaths = new TreeSet<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.financeos");
        for (BeanDefinition beanDef : candidates) {
            Class<?> clazz = Class.forName(beanDef.getBeanClassName());
            List<String> classPaths = extractClassPaths(clazz);

            for (Method method : clazz.getDeclaredMethods()) {
                List<String> methodPaths = extractMethodPaths(method);
                if (methodPaths == null) {
                    continue; // not a mapped endpoint
                }

                for (String cp : classPaths) {
                    for (String mp : methodPaths) {
                        String fullPath = normalizePath(cp, mp);
                        if (!fullPath.isEmpty() && fullPath.startsWith("/api/")) {
                            controllerPaths.add(fullPath);
                        }
                    }
                }
            }
        }

        List<String> missingPaths = new ArrayList<>();
        for (String cp : controllerPaths) {
            // Check exact or normalized match in documentedPaths
            if (!documentedPaths.contains(cp) && !matchesAnyDocumented(cp, documentedPaths)) {
                missingPaths.add(cp);
            }
        }

        assertTrue(missingPaths.isEmpty(),
                "The following controller endpoints are missing in api-spec.yaml (" + missingPaths.size() + " missing):\n" +
                        String.join("\n", missingPaths));
    }

    private boolean matchesAnyDocumented(String controllerPath, Set<String> documentedPaths) {
        String normalized = PATH_VAR_REGEX_PATTERN.matcher(controllerPath).replaceAll("{$1}");
        if (documentedPaths.contains(normalized)) {
            return true;
        }
        return false;
    }

    private List<String> extractClassPaths(Class<?> clazz) {
        RequestMapping reqMapping = clazz.getAnnotation(RequestMapping.class);
        if (reqMapping == null) {
            return List.of("");
        }
        String[] values = reqMapping.value().length > 0 ? reqMapping.value() : reqMapping.path();
        if (values.length == 0) {
            return List.of("");
        }
        return Arrays.asList(values);
    }

    private List<String> extractMethodPaths(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            GetMapping a = method.getAnnotation(GetMapping.class);
            return extractValues(a.value(), a.path());
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            PostMapping a = method.getAnnotation(PostMapping.class);
            return extractValues(a.value(), a.path());
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            PutMapping a = method.getAnnotation(PutMapping.class);
            return extractValues(a.value(), a.path());
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            DeleteMapping a = method.getAnnotation(DeleteMapping.class);
            return extractValues(a.value(), a.path());
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            PatchMapping a = method.getAnnotation(PatchMapping.class);
            return extractValues(a.value(), a.path());
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping a = method.getAnnotation(RequestMapping.class);
            return extractValues(a.value(), a.path());
        }
        return null;
    }

    private List<String> extractValues(String[] value, String[] path) {
        String[] combined = value.length > 0 ? value : path;
        if (combined.length == 0) {
            return List.of("");
        }
        return Arrays.asList(combined);
    }

    private String normalizePath(String classPath, String methodPath) {
        String p1 = classPath == null ? "" : classPath.trim();
        String p2 = methodPath == null ? "" : methodPath.trim();

        if (p1.endsWith("/")) {
            p1 = p1.substring(0, p1.length() - 1);
        }
        if (p2.startsWith("/")) {
            p2 = p2.substring(1);
        }

        String combined;
        if (p1.isEmpty()) {
            combined = "/" + p2;
        } else if (p2.isEmpty()) {
            combined = p1.startsWith("/") ? p1 : "/" + p1;
        } else {
            combined = (p1.startsWith("/") ? p1 : "/" + p1) + "/" + p2;
        }

        return PATH_VAR_REGEX_PATTERN.matcher(combined).replaceAll("{$1}");
    }
}
