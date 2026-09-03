package com.financeos.core.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.core.exception.GlobalExceptionHandler;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    static {
        org.springdoc.core.utils.SpringDocUtils.getConfig().replaceParameterObjectWithClass(
                org.springframework.data.domain.Pageable.class,
                org.springdoc.core.converters.models.Pageable.class
        ).replaceParameterObjectWithClass(
                org.springframework.data.domain.PageRequest.class,
                org.springdoc.core.converters.models.Pageable.class
        ).replaceWithClass(
                com.fasterxml.jackson.databind.JsonNode.class,
                Object.class
        );
    }

    /**
     * `$ref` properties cannot carry sibling extensions in OAS 3.0, so the x-required / x-nullable tags
     * set by propertyCustomizer are lost for nested-object components. Track them here by parent schema
     * name so openApiCustomizer can still compute `required` correctly.
     */
    private final Map<String, Set<String>> explicitRequiredRefProps = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> nullableRefProps = new ConcurrentHashMap<>();

    /**
     * The spec is committed and diffed in CI, so its version must not carry the per-build git SHA that
     * {@code app.version} gets from the deploy workflow ({@code -DgitSha=...}); use the plain project version.
     */
    @Value("${app.spec-version:1.0.0}")
    private String specVersion;

    @Bean
    public OpenAPI financeosOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinanceOS API")
                        .description("Production-grade personal finance backend API")
                        .version(specVersion))
                .servers(List.of(new Server().url("/").description("Relative Server")));
    }

    @Bean
    public PropertyCustomizer propertyCustomizer() {
        return (Schema property, AnnotatedType type) -> {
            if (property == null || type == null) {
                return property;
            }

            Type javaType = type.getType();
            // Jackson JsonNode / raw Object carry ANY JSON value (string, number, array, object, null).
            // springdoc renders them as `type: object`, which openapi-typescript turns into a map type and
            // rejects strings/numbers. Such properties are re-emitted below as an untyped schema (`unknown`).
            Class<?> raw = rawClass(javaType);
            boolean isAnyJson = raw != null && (JsonNode.class.isAssignableFrom(raw) || raw == Object.class);
            if (raw == BigDecimal.class) {
                property.setType("number");
                property.setFormat(null);
            }

            boolean isExplicitRequired = false;
            boolean isNullable = false;
            if (type.getCtxAnnotations() != null) {
                for (Annotation ann : type.getCtxAnnotations()) {
                    String simpleName = ann.annotationType().getSimpleName();
                    if ("NotNull".equals(simpleName) || "NotBlank".equals(simpleName) || "NotEmpty".equals(simpleName)) {
                        isExplicitRequired = true;
                    }
                    if ("Nullable".equalsIgnoreCase(simpleName)) {
                        isNullable = true;
                    }
                }
            }

            if (raw == Optional.class) {
                isNullable = true;
            }

            if (isAnyJson) {
                // The model resolver re-types a replaced schema, so only tag here; openApiCustomizer rewrites it.
                property.addExtension("x-any-json", true);
            }
            if (isExplicitRequired) {
                property.addExtension("x-required", true);
            }
            if (isNullable) {
                property.setNullable(true);
                property.addExtension("x-nullable", true);
            }
            if (type.getParent() != null && type.getParent().getName() != null && type.getPropertyName() != null) {
                if (isExplicitRequired) {
                    explicitRequiredRefProps.computeIfAbsent(type.getParent().getName(), k -> ConcurrentHashMap.newKeySet())
                            .add(type.getPropertyName());
                }
                if (isNullable) {
                    nullableRefProps.computeIfAbsent(type.getParent().getName(), k -> ConcurrentHashMap.newKeySet())
                            .add(type.getPropertyName());
                }
            }

            return property;
        };
    }

    @Bean
    public OpenApiCustomizer openApiCustomizer() {
        return openApi -> {
            openApi.setServers(List.of(new Server().url("/").description("Relative Server")));

            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                // @JsonTypeInfo(EXISTING_PROPERTY)/@JsonSubTypes polymorphism resolves (by default) to a base
                // schema carrying just the bare discriminator property plus subtypes shaped as
                // allOf: [$ref base, {every field again, including "type" re-declared with the FULL domain
                // enum}]. Intersecting the base's plain `type: string` with the subtype's full-enum `type`
                // is harmless in OpenAPI but openapi-typescript computes the TS intersection literally, and
                // the subtype's own "type" is never narrowed to the one literal it actually carries, so two
                // different subtypes' `type` fields end up structurally identical (and some generators infer
                // `never`). Rewrite these into a standard discriminated union: a flat, self-contained schema
                // per subtype (its own allOf inline segment already has every field) with `type` narrowed to
                // a single-value enum, and a base schema that's `oneOf` + `discriminator.mapping` over them.
                restructureAsDiscriminatedOneOf(openApi, AccountResponse.class);
                restructureAsDiscriminatedOneOf(openApi, CreateAccountRequest.class);

                // propertyCustomizer tags every property's @Nullable/@NotNull*-derived x-nullable/x-required
                // extensions correctly at resolution time — EXCEPT for a property whose value is itself
                // another component schema (object-typed, so it collapses to a bare `{$ref: ...}`): swagger-
                // core rebuilds that property as a fresh reference schema afterwards and the customizer's
                // extensions never make it into the final document. Re-derive those two flags straight from
                // the record component's annotations so such properties are (mis)classified no differently
                // than a plain string/number one.
                restoreRefPropertyMetadata(openApi);

                // Some request-shaped records (e.g. an inner *Dto used only inside an outer *Request,
                // or a wire-format class whose name can't be changed) don't end in "Request", so the
                // name-suffix rule alone misclassifies them as responses and demands every non-@Nullable
                // field be required. Walk the actual request-body/response reference graphs so any schema
                // reachable ONLY from a request body (never from a response) is treated as a request too.
                Set<String> requestOnlySchemas = requestOnlySchemaNames(openApi);

                openApi.getComponents().getSchemas().forEach((name, rawSchema) -> {
                    Schema<?> schema = (Schema<?>) rawSchema;
                    if (schema.getProperties() != null) {
                        boolean isRequestSchema = name.endsWith("Request") || requestOnlySchemas.contains(name);
                        List<String> requiredProps = new ArrayList<>();
                        schema.getProperties().forEach((propName, rawPropSchema) -> {
                            Schema<?> propSchema = (Schema<?>) rawPropSchema;
                            String prop = String.valueOf(propName);
                            boolean isExplicitReq = (propSchema.getExtensions() != null
                                    && Boolean.TRUE.equals(propSchema.getExtensions().get("x-required")))
                                    || explicitRequiredRefProps.getOrDefault(name, Set.of()).contains(prop);
                            boolean isNullable = Boolean.TRUE.equals(propSchema.getNullable())
                                    || (propSchema.getExtensions() != null && Boolean.TRUE.equals(propSchema.getExtensions().get("x-nullable")))
                                    || nullableRefProps.getOrDefault(name, Set.of()).contains(prop);

                            if (isRequestSchema) {
                                if (isExplicitReq) {
                                    requiredProps.add(String.valueOf(propName));
                                }
                            } else {
                                if (!isNullable) {
                                    requiredProps.add(String.valueOf(propName));
                                }
                            }
                        });
                        if (!requiredProps.isEmpty()) {
                            schema.setRequired(requiredProps);
                        } else {
                            schema.setRequired(null);
                        }
                    }
                });

                // Free-form JSON (JsonNode / Map<String,Object> / Object) renders as an empty object schema,
                // which openapi-typescript turns into Record<string, never> and makes every value unassignable.
                // Mark such properties (and array items) as additionalProperties: true → { [key: string]: unknown }.
                openApi.getComponents().getSchemas().values().forEach(rawSchema -> {
                    Schema<?> schema = (Schema<?>) rawSchema;
                    if (schema.getProperties() == null) {
                        return;
                    }
                    // JsonNode / Object properties (tagged x-any-json by propertyCustomizer) become untyped `unknown`.
                    schema.getProperties().replaceAll((name, rawProp) -> {
                        Schema<?> prop = (Schema<?>) rawProp;
                        if (prop.getExtensions() == null || !Boolean.TRUE.equals(prop.getExtensions().get("x-any-json"))) {
                            return prop;
                        }
                        Schema<Object> any = new Schema<>();
                        if (Boolean.TRUE.equals(prop.getNullable())) {
                            any.setNullable(true);
                            any.addExtension("x-nullable", true);
                        }
                        if (Boolean.TRUE.equals(prop.getExtensions().get("x-required"))) {
                            any.addExtension("x-required", true);
                        }
                        return any;
                    });
                    schema.getProperties().values().forEach(rawProp -> markFreeFormObject((Schema<?>) rawProp));
                });
                // Top-level schemas for Java interfaces (e.g. ReportData, implemented by several records) resolve to an
                // empty object, which the client would read as Record<string, never>. Make them free-form maps instead.
                openApi.getComponents().getSchemas().values().forEach(rawSchema -> {
                    Schema<?> schema = (Schema<?>) rawSchema;
                    boolean composed = schema.getOneOf() != null || schema.getAnyOf() != null || schema.getAllOf() != null;
                    boolean isObjectOrUntyped = schema.getType() == null || "object".equals(schema.getType());
                    if (!composed && isObjectOrUntyped && schema.getEnum() == null && schema.getProperties() == null
                            && schema.get$ref() == null && schema.getAdditionalProperties() == null) {
                        schema.setType("object");
                        schema.setAdditionalProperties(Boolean.TRUE);
                    }
                });

            }

            // Ensure ErrorResponse schema exists in components
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                if (!openApi.getComponents().getSchemas().containsKey("ErrorResponse")) {
                    Schema<?> errorSchema = new Schema<>()
                            .name("ErrorResponse")
                            .type("object")
                            .addProperty("code", new Schema<>().type("string"))
                            .addProperty("message", new Schema<>().type("string"))
                            .addProperty("details", new Schema<>().type("object").nullable(true).additionalProperties(new Schema<>().type("string")))
                            .addProperty("timestamp", new Schema<>().type("string").format("date-time").nullable(true))
                            .addProperty("errorId", new Schema<>().type("string").nullable(true))
                            .required(List.of("code", "message"));
                    openApi.getComponents().addSchemas("ErrorResponse", errorSchema);
                }
            }

            // Attach default error response reference to operations if missing 4xx/5xx responses
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(pathItem -> {
                    pathItem.readOperations().forEach(operation -> {
                        if (operation.getResponses() != null && !operation.getResponses().containsKey("default") && !operation.getResponses().containsKey("400")) {
                            ApiResponse defaultErr = new ApiResponse()
                                    .description("Error response")
                                    .content(new Content().addMediaType("application/json",
                                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))));
                            operation.getResponses().addApiResponse("default", defaultErr);
                        }
                    });
                });
            }
        };
    }


    /**
     * Rewrites a swagger-core allOf-inheritance polymorphic hierarchy (base interface + @JsonSubTypes) into
     * a proper discriminated union: {@code rootType}'s component becomes {@code oneOf} + {@code discriminator}
     * (with an explicit literal→schema mapping, since the JSON literal names here don't match the schema
     * names), and every subtype schema is flattened to its already-self-contained allOf inline segment with
     * its {@code type} property narrowed from the full domain enum down to the single literal it carries.
     * Idempotent: a no-op if {@code rootType}'s schema is already {@code oneOf} (e.g. re-run against a cached
     * OpenAPI instance), and safe against a subtype schema that's already flat (no allOf).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restructureAsDiscriminatedOneOf(OpenAPI openApi, Class<?> rootType) {
        JsonSubTypes subTypes = rootType.getAnnotation(JsonSubTypes.class);
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Schema<?> baseSchema = schemas.get(rootType.getSimpleName());
        if (subTypes == null || baseSchema == null || baseSchema.getOneOf() != null) {
            return;
        }

        List<Schema> oneOf = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (JsonSubTypes.Type subType : subTypes.value()) {
            String literal = subType.name();
            String memberName = subType.value().getSimpleName();
            Schema<?> memberSchema = schemas.get(memberName);
            if (memberSchema == null) {
                continue;
            }
            List<Schema> allOf = memberSchema.getAllOf();
            Schema flat = (allOf != null && !allOf.isEmpty()) ? allOf.get(allOf.size() - 1) : memberSchema;
            flat.setType("object");

            Object typeProp = flat.getProperties() != null ? flat.getProperties().get("type") : null;
            if (typeProp instanceof Schema typeSchema) {
                typeSchema.setEnum(List.of(literal));
            }
            // Leave `required` for the required-computation pass below to (re)compute from each property's
            // x-required/x-nullable extensions, now that this schema is a normal top-level component with
            // its own `properties` — same rule every other request/response schema in this file gets.

            schemas.put(memberName, flat);
            oneOf.add(new Schema<>().$ref("#/components/schemas/" + memberName));
            mapping.put(literal, "#/components/schemas/" + memberName);
        }

        Schema<Object> newBase = new Schema<>();
        newBase.setOneOf(oneOf);
        newBase.setDiscriminator(new Discriminator().propertyName("type").mapping(mapping));
        schemas.put(rootType.getSimpleName(), newBase);
    }

    /**
     * For every top-level schema that has a same-named Java record on the classpath, restores the
     * x-nullable/x-required extensions on any property that's a bare {@code $ref} — the one case where
     * {@link #propertyCustomizer()}'s work doesn't survive to the final document (see call site comment).
     * Best-effort: a schema with no matching record class, or a $ref property with no matching record
     * component, is left exactly as swagger-core produced it.
     */
    private static void restoreRefPropertyMetadata(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Map<String, Class<?>> recordClasses = scanRecordClasses();

        schemas.forEach((schemaName, rawSchema) -> {
            Schema<?> schema = (Schema<?>) rawSchema;
            Class<?> cls = recordClasses.get(schemaName);
            if (schema.getProperties() == null || cls == null || !cls.isRecord()) {
                return;
            }
            RecordComponent[] components = cls.getRecordComponents();
            schema.getProperties().forEach((propName, rawProp) -> {
                Schema<?> prop = (Schema<?>) rawProp;
                if (prop.get$ref() == null) {
                    return;
                }
                for (RecordComponent rc : components) {
                    if (!rc.getName().equals(String.valueOf(propName))) {
                        continue;
                    }
                    for (Annotation ann : rc.getAnnotations()) {
                        String simpleName = ann.annotationType().getSimpleName();
                        if ("Nullable".equalsIgnoreCase(simpleName)) {
                            prop.setNullable(true);
                            prop.addExtension("x-nullable", true);
                        }
                        if ("NotNull".equals(simpleName) || "NotBlank".equals(simpleName) || "NotEmpty".equals(simpleName)) {
                            prop.addExtension("x-required", true);
                        }
                    }
                    break;
                }
            });
        });
    }

    /** Every record type (including nested static records) under com.financeos, keyed by simple name. */
    private static Map<String, Class<?>> scanRecordClasses() {
        Map<String, Class<?>> byName = new HashMap<>();
        try {
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
            for (BeanDefinition bd : scanner.findCandidateComponents("com.financeos")) {
                try {
                    Class<?> cls = Class.forName(bd.getBeanClassName(), false, OpenApiConfig.class.getClassLoader());
                    if (cls.isRecord()) {
                        byName.putIfAbsent(cls.getSimpleName(), cls);
                    }
                } catch (Throwable ignored) {
                    // a class that fails to load here just isn't usable for this lookup
                }
            }
        } catch (Throwable ignored) {
            // best-effort: if classpath scanning fails for any reason, restoreRefPropertyMetadata simply no-ops
        }
        return byName;
    }

    /**
     * Schema names reachable from at least one operation's request body and from NO operation's response —
     * these are request-shaped even when their Java record name doesn't end in "Request" (e.g. an inner
     * *Dto nested inside a *Request, or a wire-format class whose name is fixed by client expectations).
     * A schema reachable from both a request and a response keeps the conservative (response) classification.
     */
    private static Set<String> requestOnlySchemaNames(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents() != null ? openApi.getComponents().getSchemas() : null;
        if (schemas == null || openApi.getPaths() == null) {
            return java.util.Collections.emptySet();
        }
        Set<String> requestReachable = new java.util.HashSet<>();
        Set<String> responseReachable = new java.util.HashSet<>();

        openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values().forEach(mediaType ->
                        collectReachableSchemas(mediaType.getSchema(), schemas, requestReachable));
            }
            if (operation.getResponses() != null) {
                operation.getResponses().values().forEach(response -> {
                    if (response.getContent() != null) {
                        response.getContent().values().forEach(mediaType ->
                                collectReachableSchemas(mediaType.getSchema(), schemas, responseReachable));
                    }
                });
            }
        }));

        requestReachable.removeAll(responseReachable);
        return requestReachable;
    }

    /** Recursively resolves $ref/properties/items/composed schemas, collecting every named component schema reached. */
    private static void collectReachableSchemas(Schema<?> schema, Map<String, Schema> allSchemas, Set<String> collected) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String refName = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (collected.add(refName)) {
                collectReachableSchemas(allSchemas.get(refName), allSchemas, collected);
            }
            return;
        }
        if (schema.getProperties() != null) {
            for (Object propSchema : schema.getProperties().values()) {
                collectReachableSchemas((Schema<?>) propSchema, allSchemas, collected);
            }
        }
        if (schema.getItems() != null) {
            collectReachableSchemas(schema.getItems(), allSchemas, collected);
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            collectReachableSchemas(additional, allSchemas, collected);
        }
        if (schema.getAllOf() != null) {
            for (Object s : schema.getAllOf()) {
                collectReachableSchemas((Schema<?>) s, allSchemas, collected);
            }
        }
        if (schema.getOneOf() != null) {
            for (Object s : schema.getOneOf()) {
                collectReachableSchemas((Schema<?>) s, allSchemas, collected);
            }
        }
        if (schema.getAnyOf() != null) {
            for (Object s : schema.getAnyOf()) {
                collectReachableSchemas((Schema<?>) s, allSchemas, collected);
            }
        }
    }

    /**
     * swagger-core passes resolved types as Jackson {@code JavaType} (which implements {@code Type}), not as
     * {@code Class}; unwrap all three shapes so type-based rules actually match.
     */
    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        if (t instanceof com.fasterxml.jackson.databind.JavaType jt) {
            return jt.getRawClass();
        }
        return null;
    }

    /** Empty object schemas (no properties, no $ref, no additionalProperties) become free-form maps. */
    private static void markFreeFormObject(Schema<?> prop) {
        if (prop == null) {
            return;
        }
        if (prop.getItems() != null) {
            markFreeFormObject(prop.getItems());
        }
        boolean isObject = "object".equals(prop.getType())
                || (prop.getTypes() != null && prop.getTypes().contains("object"));
        if (isObject && prop.getProperties() == null && prop.get$ref() == null && prop.getAdditionalProperties() == null) {
            prop.setAdditionalProperties(Boolean.TRUE);
        }
    }
}
