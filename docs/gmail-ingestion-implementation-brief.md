# Gmail Ingestion — Implementation Brief (for the coding agent)

You are implementing the Gmail → Transaction ingestion feature in this Spring Boot / Oracle / Maven repo.

> _Note for the human running this agent: use **Gemini 3.5 Flash** as the agent model — currently Google's strongest agentic/coding model._

## 1. Read first — the source of truth
- **`docs/gmail-ingestion-spec.md`** is the complete design. Read it fully before writing any code. §0 lists the locked decisions; §4 the detailed design; §5 the migrations; §12 the milestones.
- Do **not** redesign. If something seems wrong or ambiguous, stop and ask the human — do not silently deviate.

## 2. Work milestone-by-milestone (mandatory)
Implement in order **M1 → M2 → M3 → M4** (spec §12). After **each** milestone: make sure it compiles and the app boots (Flyway migrations apply), then **STOP and wait for human review** before starting the next. Do not implement all milestones in one shot — the tenancy, idempotency, watermark, and matching logic are subtle and must be reviewed incrementally.

## 3. Stack & conventions (match the existing code exactly)
- **Java + Spring Boot**, build with `./mvnw`. DB is **Oracle** (`OracleDialect`).
- **Migrations:** Flyway in `src/main/resources/db/migration/`, Oracle DDL (`VARCHAR2`/`NUMBER`/`TIMESTAMP`/`DATE`), UUID columns as `VARCHAR2(36)`. **Next version = V16** (do not reuse/renumber existing). Migrations are additive — never edit a shipped migration.
- **Packages:** new ingestion code under `com.financeos.gmail.*` (existing sub-packages: `oauth`, `client`, `engine`, `history`, `domain`, `internal`; add `ingest`, `ingest.gemini`, `reconcile` per the spec). Controllers/DTOs under `com.financeos.api.gmail`.
- **Entities:** Lombok `@Getter/@Setter/@NoArgsConstructor`; UUID id with `@GeneratedValue(strategy=GenerationType.UUID)` + `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(length=36)`; `@Filter(name="userFilter", condition="user_id = :userId")` on user-owned entities; `@PrePersist`/`@PreUpdate` for timestamps. Copy the pattern from `GmailConnection` / `Transaction`.
- **Multi-tenancy:** `HibernateFilterAspect` enables the `userFilter` before any `@Transactional` method using `UserContext.getCurrentUserId()` (a `ThreadLocal`). Services are `@Transactional` and user-scoped.
- **Encryption:** sensitive string columns use `@Convert(converter = EncryptedStringConverter.class)`.
- **DTOs:** Java `record`s with a static `from(...)` factory (see `GmailFetchResultDto`).
- **Repos:** Spring Data JPA interfaces (`findByUserIdAnd…`).

## 4. Hard guardrails (do NOT)
- **Background tenancy:** cron/ingestion runs have no HTTP request, so `UserContext` is unset and the tenant filter won't apply. Per connection you MUST wrap work in `UserContext.setCurrentUserId(connection.getUser().getId())` … `finally { UserContext.clear(); }` (spec §4.5). Never process two users on one thread without resetting.
- **Do not reuse `TransactionService.createTransaction`** for ingestion — it hardcodes `source=manual` and reads request-time `UserContext`. Add a dedicated `GmailTransactionWriter` / `createFromIngestion(...)` write path.
- **Keep `GmailEngine` a pure fetch engine** — only extend it to capture the body (§4.1) and lazy attachments. No parsing/persistence/business logic inside it.
- **Do not touch `isTransactionUnderMonitoring`** — `reviewType` is a new, separate column.
- **Do not** write raw email content into `sourcedDescription`, persist full email bodies, or log bodies/PII.
- **Do not** add Pub/Sub (cron only), broaden the OAuth scope beyond `gmail.readonly`, or assume any currency other than INR.
- Idempotency is mandatory before any persistence: the `gmail_processed_messages` ledger + unique `source_message_id` (per-line for statements: `<statementMsgId>:<lineIndex>`).

## 5. Dependencies to add to `pom.xml`
- **M2+:** Google Gen AI Java SDK (`com.google.genai:google-genai`) for Gemini, or call the REST API with the existing HTTP stack + Jackson. Default both runtime models to the current top Flash model (**Gemini 3.5 Flash**) via `gemini.model` / `gemini.statement-model`; reserve **Pro** only as a statement-parsing fallback.
- **M4:** `org.apache.pdfbox:pdfbox` (decrypt + read PDF statements) and `org.apache.poi:poi-ooxml` (XLSX).

## 6. What the human will provide (ask if missing)
- `GEMINI_API_KEY` (AI Studio) → `gemini.api-key` config.
- Gmail OAuth client id/secret/redirect (likely already in `application.yml` as `gmail.oauth.*`).
- The **sender allowlist** rows (`gmail_senders`: address, purpose, bound account) — seed via the new API or SQL.
- Account `last4` values populated (for resolution) and, when relevant, `accounts.ingest_from_date` (watermark) and encrypted `statement_password` (M4).

## 7. Definition of done & verification per milestone
- Every milestone: `./mvnw compile` clean; app boots; new Flyway migrations apply without error.
- **M1:** body capture in `GmailMessage`/engine; migrations V16–V18; ledger + `review_type`/`source_message_id`/`ingest_from_date` exist; background `UserContext` helper. No behavior change yet.
- **M2:** `gmail_senders` (V19) + CRUD; `GeminiExtractor` (Flash, JSON schema, temp 0); `AccountResolver`; `GmailTransactionWriter`; `POST /api/v1/gmail/sync` creates `gmail_transaction_alert`/`NEEDS_REVIEW` txns, watermark-gated, idempotent. Verify by connecting a Gmail account, seeding a sender, and calling `/sync`.
- **M3:** `@EnableScheduling` + the cron (`0 0 10-22/2 * * *`, `Asia/Kolkata`); fetch via `from:(allowlist) after:<last_synced_at>`; advance `last_synced_at`.
- **M4:** V20 (source values + statement passwords); STATEMENT routing; PDFBox/POI decrypt; Gemini Pro parse; `StatementReconciliationService` (1:1 greedy match, amount+direction+date±N, watermark-gated); outcomes per spec §4.10/§4.8.

Report what you changed after each milestone and stop for review.
