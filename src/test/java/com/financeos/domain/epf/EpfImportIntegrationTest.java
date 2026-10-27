package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.epf.passbook.PassbookTestPdf;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EPF passbook import over MockMvc with synthetic PDFs: employer resolution, row statuses, commit, errors, tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfImportIntegrationTest extends EpfIntegrationBase {

    private static final String MEMBER = "MHBAN00123450000067890";
    private static final String UAN = "100123454321";
    private static final String CONTRIB_APR = "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250";
    private static final String CONTRIB_MAY = "May-2026 15-06-2026 CR Cont. For Due-Month 062026 60,000 15,000 7,200 5,950 1,250";
    private static final String INTEREST = "Int. Updated upto 31/03/2026 32,410 23,770 0";
    private static final String WITHDRAWAL = "15-06-2015 DR PF Withdrawal 10,000 8,000 0";

    private static byte[] passbook(String member, String uan, String estName, String... rows) throws Exception {
        List<String> lines = new ArrayList<>(List.of(
                "Employees Provident Fund Organisation",
                "Establishment ID/Name  MHBAN0012345000 / " + estName,
                "Member ID/Name  " + member + " / ZZQUILL TESTPERSON"));
        if (uan != null) lines.add("UAN  " + uan);
        lines.add("Wage Month Transaction Date Transaction Type Particulars EPF Wages EPS Wages Employee Share Employer Share Pension");
        lines.addAll(List.of(rows));
        return PassbookTestPdf.pdf(lines);
    }

    private static byte[] standard(String... rows) throws Exception {
        return passbook(MEMBER, UAN, "NORTHWIND LABS PRIVATE LIMITED", rows);
    }

    private JsonNode preview(byte[] file, UUID employerId, int expected) throws Exception {
        Map<String, String> f = employerId == null ? Map.of() : Map.of("employerId", employerId.toString());
        return api.postMultipart(BASE + "/import/preview", "p.pdf", file, f, expected);
    }

    private JsonNode commit(byte[] file, UUID employerId, int expected) throws Exception {
        Map<String, String> f = employerId == null ? Map.of() : Map.of("employerId", employerId.toString());
        return api.postMultipart(BASE + "/import/commit", "p.pdf", file, f, expected);
    }

    private JsonNode entries(UUID employerId) throws Exception {
        return api.getJson(BASE + "/entries?employerId=" + employerId + "&size=100", 200).get("content");
    }

    @Test
    void newEmployerFromEstablishmentNameMemberIdAndUan() throws Exception {
        byte[] file = standard(CONTRIB_APR, CONTRIB_MAY, INTEREST);
        JsonNode p = preview(file, null, 200);
        assertTrue(p.get("employer").get("existingId").isNull());
        assertEquals("NORTHWIND LABS PRIVATE LIMITED", p.get("employer").get("name").asText());
        assertEquals(MEMBER, p.get("employer").get("memberId").asText());
        assertEquals("MHBAN0012345000", p.get("employer").get("establishmentId").asText());
        assertEquals("4321", p.get("employer").get("uanLast4").asText());
        assertEquals(3, p.get("newCount").asInt());
        assertEquals(0, p.get("existsCount").asInt());
        assertEquals(0, p.get("replacesCount").asInt());
        assertEquals(0, p.get("employerOptions").size());
        JsonNode first = p.get("rows").get(0);
        assertEquals("CONTRIBUTION", first.get("type").asText());
        assertEquals("2026-04-01", first.get("wageMonth").asText());
        assertEquals("2026-05-15", first.get("creditedOn").asText());
        assertAmount("7200", first.get("employeeAmount"));
        assertAmount("1250", first.get("pensionAmount"));
        assertEquals("NEW", first.get("status").asText());
        assertEquals(0, api.getJson(BASE + "/employers", 200).size(), "preview writes nothing");

        JsonNode r = commit(file, null, 200);
        assertEquals(3, r.get("imported").asInt());
        assertEquals(0, r.get("replaced").asInt());
        assertEquals(0, r.get("skipped").asInt());
        assertEquals("NORTHWIND LABS PRIVATE LIMITED", r.get("employerName").asText());
        JsonNode employers = api.getJson(BASE + "/employers", 200);
        assertEquals(1, employers.size());
        assertEquals(MEMBER, employers.get(0).get("memberId").asText());
        assertEquals(UAN, employers.get(0).get("uan").asText());
        assertEquals("MHBAN0012345000", employers.get(0).get("establishmentId").asText());
        JsonNode content = entries(UUID.fromString(r.get("employerId").asText()));
        assertEquals(3, content.size());
        for (JsonNode e : content) {
            assertEquals("PASSBOOK", e.get("source").asText());
            assertNotNull(e.get("importedAt").asText());
            assertTrue(e.get("tdsAmount").isNull());
        }
    }

    @Test
    void blankEstablishmentNameFallsBackToGenericName() throws Exception {
        JsonNode r = commit(passbook(MEMBER, UAN, "  ", CONTRIB_APR), null, 200);
        assertEquals("EPF employer", r.get("employerName").asText());
    }

    @Test
    void importedEntriesShowInEntriesAndSummary() throws Exception {
        JsonNode r = commit(standard("OB Int. Updated upto 31/03/2025 4,10,920 2,97,210 0", CONTRIB_APR), null, 200);
        assertEquals(2, r.get("imported").asInt());
        assertEquals(2, api.getJson(BASE + "/entries?size=50", 200).get("content").size());
        JsonNode summary = api.getJson(BASE + "/summary", 200);
        assertTrue(summary.get("hasData").asBoolean());
        assertAmount("721280", summary.get("balance")); // 410920 + 297210 + 7200 + 5950
    }

    @Test
    void matchesAnExistingEmployerByMemberIdAndSecondCommitSkipsEverything() throws Exception {
        UUID existing = employer(api, "Hand made", MEMBER, "2020-01-01", null);
        byte[] file = standard(CONTRIB_APR, CONTRIB_MAY, INTEREST);
        JsonNode p = preview(file, null, 200);
        assertEquals(existing.toString(), p.get("employer").get("existingId").asText());
        assertEquals("Hand made", p.get("employer").get("name").asText());
        assertEquals(0, p.get("employerOptions").size(), "employers with a member id are not options");

        JsonNode first = commit(file, null, 200);
        assertEquals(existing.toString(), first.get("employerId").asText());
        assertEquals(3, first.get("imported").asInt());
        JsonNode again = preview(file, null, 200);
        assertEquals(3, again.get("existsCount").asInt());
        assertEquals(0, again.get("newCount").asInt());
        JsonNode second = commit(file, null, 200);
        assertEquals(0, second.get("imported").asInt());
        assertEquals(3, second.get("skipped").asInt());
        assertEquals(3, entries(existing).size());
        assertEquals(1, api.getJson(BASE + "/employers", 200).size());
    }

    @Test
    void explicitEmployerGetsMemberIdEstablishmentAndUanFilledIn() throws Exception {
        UUID byHand = employer("My old job", "2018-01-01", null);
        byte[] file = standard(CONTRIB_APR);
        JsonNode p = preview(file, null, 200);
        assertTrue(p.get("employer").get("existingId").isNull(), "no member id match, so a new employer");
        assertEquals(1, p.get("employerOptions").size());
        assertEquals(byHand.toString(), p.get("employerOptions").get(0).get("id").asText());
        assertEquals("My old job", p.get("employerOptions").get(0).get("name").asText());

        JsonNode chosen = preview(file, byHand, 200);
        assertEquals(byHand.toString(), chosen.get("employer").get("existingId").asText());
        assertEquals("My old job", chosen.get("employer").get("name").asText());

        JsonNode r = commit(file, byHand, 200);
        assertEquals(byHand.toString(), r.get("employerId").asText());
        assertEquals("My old job", r.get("employerName").asText());
        JsonNode e = api.getJson(BASE + "/employers", 200).get(0);
        assertEquals(1, api.getJson(BASE + "/employers", 200).size());
        assertEquals(MEMBER, e.get("memberId").asText());
        assertEquals(UAN, e.get("uan").asText());
        assertEquals("MHBAN0012345000", e.get("establishmentId").asText());
        // now it has a member id it is no longer an option, and a plain upload matches it
        assertEquals(0, preview(file, null, 200).get("employerOptions").size());
        assertEquals(1, preview(file, null, 200).get("existsCount").asInt());
    }

    @Test
    void explicitEmployerWithADifferentMemberIdIsRejected() throws Exception {
        UUID other = employer(api, "Other", "MHBAN00123450000011111", "2018-01-01", null);
        assertEquals("VALIDATION_ERROR", preview(standard(CONTRIB_APR), other, 400).get("code").asText());
        assertEquals("VALIDATION_ERROR", commit(standard(CONTRIB_APR), other, 400).get("code").asText());
        assertEquals(0, entries(other).size());
    }

    @Test
    void anotherUsersEmployerIs404() throws Exception {
        UUID theirs = employer(otherApi, "Theirs", null, "2018-01-01", null);
        assertEquals("NOT_FOUND", preview(standard(CONTRIB_APR), theirs, 404).get("code").asText());
        assertEquals("NOT_FOUND", commit(standard(CONTRIB_APR), theirs, 404).get("code").asText());
        assertEquals(0, otherApi.getJson(BASE + "/entries", 200).get("content").size());
    }

    @Test
    void otherUsersEmployersNeverAppearAsOptionsOrMatches() throws Exception {
        employer(otherApi, "Theirs", MEMBER, "2018-01-01", null);
        JsonNode p = preview(standard(CONTRIB_APR), null, 200);
        assertTrue(p.get("employer").get("existingId").isNull());
        assertEquals(0, p.get("employerOptions").size());
        commit(standard(CONTRIB_APR), null, 200);
        assertEquals(0, otherApi.getJson(BASE + "/entries", 200).get("content").size());
        assertEquals(1, api.getJson(BASE + "/employers", 200).size());
    }

    @Test
    void differentUanIsRejectedWithBothLastFourDigits() throws Exception {
        UUID e = employer(api, "Acme", "MHBAN00123450000011111", "2018-01-01", null);
        EpfEmployer stored = employerRepository.findById(e).orElseThrow();
        stored.setUan("100999998765");
        employerRepository.saveAndFlush(stored);
        JsonNode err = preview(standard(CONTRIB_APR), null, 400);
        assertEquals("UAN_MISMATCH", err.get("code").asText());
        assertEquals("This passbook is for a different UAN (…4321). Your EPF uses UAN …8765.", message(err));
        assertEquals("UAN_MISMATCH", commit(standard(CONTRIB_APR), null, 400).get("code").asText());
        assertEquals(1, api.getJson(BASE + "/employers", 200).size());
    }

    @Test
    void parserErrorsComeBackAsCodes() throws Exception {
        JsonNode notPassbook = preview(PassbookTestPdf.pdf(List.of("Grocery list", "Milk 2")), null, 400);
        assertEquals("NOT_A_PASSBOOK", notPassbook.get("code").asText());
        assertFalse(message(notPassbook).isBlank());
        byte[] locked = PassbookTestPdf.pdf(List.of("Employees Provident Fund Organisation", "UAN  " + UAN, CONTRIB_APR), "secret");
        assertEquals("PASSWORD_PROTECTED", preview(locked, null, 400).get("code").asText());
        assertEquals("UNREADABLE", preview("not a pdf".getBytes(), null, 400).get("code").asText());
        assertEquals("UNREADABLE", commit("not a pdf".getBytes(), null, 400).get("code").asText());
        assertEquals(0, api.getJson(BASE + "/employers", 200).size());
    }

    @Test
    void identicalRowsAreMatchedAsAMultiset() throws Exception {
        UUID e2 = employer(api, "Beta", MEMBER, "2020-01-01", null);
        String row = "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 100 50 0";
        byte[] file = standard(row, row);
        EpfEntry stored = new EpfEntry();
        stored.setUser(userRepository.getReferenceById(userId));
        stored.setEmployer(employerRepository.getReferenceById(e2));
        stored.setType(EpfEntryType.CONTRIBUTION);
        stored.setSource(EpfEntrySource.PASSBOOK);
        stored.setWageMonth(java.time.LocalDate.of(2026, 4, 1));
        stored.setCreditedOn(java.time.LocalDate.of(2026, 5, 15));
        stored.setEmployeeAmount(new java.math.BigDecimal("100"));
        stored.setEmployerAmount(new java.math.BigDecimal("50"));
        entryRepository.saveAndFlush(stored);
        JsonNode m = preview(file, null, 200);
        assertEquals(1, m.get("existsCount").asInt());
        assertEquals(1, m.get("newCount").asInt());
        assertEquals("EXISTS", m.get("rows").get(0).get("status").asText());
        assertEquals("NEW", m.get("rows").get(1).get("status").asText());
        JsonNode r = commit(file, null, 200);
        assertEquals(1, r.get("imported").asInt());
        assertEquals(1, r.get("skipped").asInt());
        assertEquals(2, entries(e2).size());
    }

    @Test
    void manualEntriesInTheSameSlotAreReplacedInPlaceKeepingNoteTdsAndLink() throws Exception {
        UUID e = employer(api, "Acme", MEMBER, "2014-01-01", null);
        UUID contribution = addEntryId(withNote(contribution(e, "2026-04-01", "2026-05-10", 7000, 5000, 1000), "my note"));
        UUID interestId = addEntryId(interest(e, 2025, "2026-03-31", 1, 1));
        UUID withdrawal = addEntryId(withNote(withdrawal(e, "PARTIAL", "2015-06-15", 10000, 8000, 0, 500), "house"));
        UUID credit = transaction(17500, "2015-06-20", "EPFO credit");
        api.putJson(BASE + "/entries/" + withdrawal + "/transaction", Map.of("transactionId", credit.toString(), "recordTds", false), 200);

        byte[] file = standard(CONTRIB_APR, CONTRIB_MAY, INTEREST, WITHDRAWAL);
        JsonNode p = preview(file, null, 200);
        assertEquals(1, p.get("newCount").asInt());
        assertEquals(3, p.get("replacesCount").asInt());
        assertEquals(contribution.toString(), p.get("rows").get(0).get("replacesEntryId").asText());
        assertTrue(p.get("rows").get(1).get("replacesEntryId").isNull());
        assertEquals(interestId.toString(), p.get("rows").get(2).get("replacesEntryId").asText());
        assertEquals(withdrawal.toString(), p.get("rows").get(3).get("replacesEntryId").asText());

        JsonNode r = commit(file, null, 200);
        assertEquals(1, r.get("imported").asInt());
        assertEquals(3, r.get("replaced").asInt());
        assertEquals(0, r.get("skipped").asInt());
        assertEquals(4, entries(e).size());

        JsonNode c = api.getJson(BASE + "/entries/" + contribution, 200);
        assertEquals("PASSBOOK", c.get("source").asText());
        assertEquals("my note", c.get("note").asText());
        assertEquals("2026-05-15", c.get("creditedOn").asText());
        assertAmount("7200", c.get("employeeAmount"));
        assertAmount("1250", c.get("pensionAmount"));
        assertNotNull(c.get("importedAt").asText());
        JsonNode i = api.getJson(BASE + "/entries/" + interestId, 200);
        assertEquals("PASSBOOK", i.get("source").asText());
        assertAmount("32410", i.get("employeeAmount"));
        JsonNode w = api.getJson(BASE + "/entries/" + withdrawal, 200);
        assertEquals("PASSBOOK", w.get("source").asText());
        assertEquals("house", w.get("note").asText());
        assertAmount("500", w.get("tdsAmount"));
        assertEquals(credit.toString(), w.get("transaction").get("id").asText());

        // a re-upload now finds everything stored
        JsonNode again = commit(file, null, 200);
        assertEquals(0, again.get("imported").asInt());
        assertEquals(4, again.get("skipped").asInt());
    }

    @Test
    void eachManualEntryIsReplacedByOnlyTheFirstMatchingRow() throws Exception {
        UUID e = employer(api, "Acme", MEMBER, "2014-01-01", null);
        UUID manual = addEntryId(contribution(e, "2026-04-01", "2026-05-10", 1, 1, 0));
        String twin = "Apr-2026 16-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250";
        JsonNode p = preview(standard(CONTRIB_APR, twin), null, 200);
        assertEquals("REPLACES", p.get("rows").get(0).get("status").asText());
        assertEquals(manual.toString(), p.get("rows").get(0).get("replacesEntryId").asText());
        assertEquals("NEW", p.get("rows").get(1).get("status").asText());
    }

    @Test
    void previewCountsEqualCommitResult() throws Exception {
        UUID e = employer(api, "Acme", MEMBER, "2014-01-01", null);
        addEntry(contribution(e, "2026-04-01", "2026-05-10", 1, 1, 0));
        passbookEntry(e, EpfEntryType.WITHDRAWAL, "2015-06-15", 10000, 8000);
        byte[] file = standard(CONTRIB_APR, CONTRIB_MAY, WITHDRAWAL);
        JsonNode p = preview(file, null, 200);
        JsonNode r = commit(file, null, 200);
        assertEquals(p.get("newCount").asInt(), r.get("imported").asInt());
        assertEquals(p.get("replacesCount").asInt(), r.get("replaced").asInt());
        assertEquals(p.get("existsCount").asInt(), r.get("skipped").asInt());
        assertEquals(1, r.get("replaced").asInt());
    }

    private static Map<String, Object> withNote(Map<String, Object> body, String note) {
        body.put("note", note);
        return body;
    }
}
