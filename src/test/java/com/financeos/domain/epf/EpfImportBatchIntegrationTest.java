package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.epf.passbook.PassbookTestPdf;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Batch EPF passbook import over MockMvc with synthetic PDFs: sections, merging, refusals, assign, commit. */
@SpringBootTest
@AutoConfigureMockMvc
class EpfImportBatchIntegrationTest extends EpfIntegrationBase {

    private static final String MEMBER = "MHBAN00123450000067890";
    private static final String MEMBER_B = "MHBAN00123450000099999";
    private static final String UAN = "100123454321";
    private static final String C_APR = "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250";
    private static final String C_MAY = "May-2026 15-06-2026 CR Cont. For Due-Month 062026 60,000 15,000 7,200 5,950 1,250";
    private static final String C_JUN = "Jun-2026 15-07-2026 CR Cont. For Due-Month 072026 60,000 15,000 7,200 5,950 1,250";
    private static final String INTEREST = "Int. Updated upto 31/03/2026 32,410 23,770 0";

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

    private static byte[] a(String... rows) throws Exception {
        return passbook(MEMBER, UAN, "NORTHWIND LABS PRIVATE LIMITED", rows);
    }

    private static byte[] b(String... rows) throws Exception {
        return passbook(MEMBER_B, UAN, "CONTOSO WORKS LIMITED", rows);
    }

    private JsonNode preview(List<String> names, List<byte[]> files, List<String> assign, int expected) throws Exception {
        return api.postMultipartFiles(BASE + "/import/batch/preview", names, files, assign, expected);
    }

    private JsonNode commit(List<String> names, List<byte[]> files, List<String> assign, int expected) throws Exception {
        return api.postMultipartFiles(BASE + "/import/batch/commit", names, files, assign, expected);
    }

    @Test
    void twoYearFilesOfOneMemberMergeIntoOneSortedSectionWithOverlapKeptOnce() throws Exception {
        byte[] fy1 = a(C_MAY, INTEREST);
        byte[] fy2 = a(C_MAY, C_JUN, C_APR);
        JsonNode p = preview(List.of("fy1.pdf", "fy2.pdf"), List.of(fy1, fy2), List.of(), 200);
        assertEquals(0, p.get("refused").size());
        assertEquals(1, p.get("sections").size());
        JsonNode s = p.get("sections").get(0);
        assertEquals("member:" + MEMBER, s.get("key").asText());
        assertEquals("fy1.pdf", s.get("fileNames").get(0).asText());
        assertEquals("fy2.pdf", s.get("fileNames").get(1).asText());
        JsonNode pv = s.get("preview");
        assertEquals(4, pv.get("rows").size());
        assertEquals(4, pv.get("newCount").asInt());
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assertEquals(i, pv.get("rows").get(i).get("index").asInt());
            dates.add(pv.get("rows").get(i).get("creditedOn").asText());
        }
        assertEquals(dates.stream().sorted().toList(), dates);
        assertEquals("2026-06-01", pv.get("employer").get("throughMonth").asText());
        assertEquals("NORTHWIND LABS PRIVATE LIMITED", pv.get("employer").get("name").asText());
    }

    @Test
    void duplicateRowsWithinOneFileAreStillKeptTwice() throws Exception {
        JsonNode p = preview(List.of("one.pdf"), List.of(a(C_APR, C_APR)), List.of(), 200);
        assertEquals(2, p.get("sections").get(0).get("preview").get("rows").size());
        JsonNode q = preview(List.of("one.pdf", "two.pdf"), List.of(a(C_APR, C_APR), a(C_APR)), List.of(), 200);
        assertEquals(2, q.get("sections").get(0).get("preview").get("rows").size(), "second file's row overlaps one of the pair");
    }

    @Test
    void twoMembersGiveTwoSectionsInOrderOfFirstAppearance() throws Exception {
        JsonNode p = preview(List.of("b.pdf", "a.pdf", "b2.pdf"), List.of(b(C_APR), a(C_APR), b(C_MAY)), List.of(), 200);
        assertEquals(2, p.get("sections").size());
        assertEquals("member:" + MEMBER_B, p.get("sections").get(0).get("key").asText());
        assertEquals(2, p.get("sections").get(0).get("fileNames").size());
        assertEquals("b2.pdf", p.get("sections").get(0).get("fileNames").get(1).asText());
        assertEquals("member:" + MEMBER, p.get("sections").get(1).get("key").asText());
    }

    @Test
    void aFileWithoutMemberIdIsItsOwnSectionKeyedByPosition() throws Exception {
        byte[] noMember = PassbookTestPdf.pdf(List.of(
                "Employees Provident Fund Organisation",
                "Establishment ID/Name  MHBAN0012345000 / NORTHWIND LABS PRIVATE LIMITED",
                "UAN  " + UAN,
                "Wage Month Transaction Date Transaction Type Particulars EPF Wages EPS Wages Employee Share Employer Share Pension",
                C_APR));
        JsonNode p = preview(List.of("a.pdf", "n.pdf"), List.of(a(C_APR), noMember), List.of(), 200);
        assertEquals(2, p.get("sections").size());
        assertEquals("file:1", p.get("sections").get(1).get("key").asText());
    }

    @Test
    void nonPassbookFileIsRefusedWhileOthersPreview() throws Exception {
        JsonNode p = preview(List.of("a.pdf", "junk.pdf"),
                List.of(a(C_APR), PassbookTestPdf.pdf(List.of("Grocery list", "Milk 2"))), List.of(), 200);
        assertEquals(1, p.get("sections").size());
        assertEquals(1, p.get("refused").size());
        assertEquals("junk.pdf", p.get("refused").get(0).get("fileName").asText());
        assertEquals("NOT_A_PASSBOOK", p.get("refused").get(0).get("code").asText());
        assertTrue(p.get("refused").get(0).get("message").asText().length() > 0);
    }

    @Test
    void everyFileRefusedStillPreviewsWith200() throws Exception {
        JsonNode p = preview(List.of("x.pdf"), List.of("not a pdf".getBytes()), List.of(), 200);
        assertEquals(0, p.get("sections").size());
        assertEquals("UNREADABLE", p.get("refused").get(0).get("code").asText());
    }

    @Test
    void differentUanFileIsRefusedAgainstFirstParsedUan() throws Exception {
        byte[] other = passbook(MEMBER_B, "100999998765", "CONTOSO WORKS LIMITED", C_APR);
        JsonNode p = preview(List.of("a.pdf", "other.pdf"), List.of(a(C_APR), other), List.of(), 200);
        assertEquals(1, p.get("sections").size());
        assertEquals("UAN_MISMATCH", p.get("refused").get(0).get("code").asText());
        assertEquals("This passbook is for a different UAN (…8765). Your EPF uses UAN …4321.",
                p.get("refused").get(0).get("message").asText());
    }

    @Test
    void differentUanIsRefusedAgainstStoredEmployerUan() throws Exception {
        UUID e = employer(api, "Acme", "MHBAN00123450000011111", "2018-01-01", null);
        EpfEmployer stored = employerRepository.findById(e).orElseThrow();
        stored.setUan("100999998765");
        employerRepository.saveAndFlush(stored);
        JsonNode p = preview(List.of("a.pdf"), List.of(a(C_APR)), List.of(), 200);
        assertEquals(0, p.get("sections").size());
        assertEquals("UAN_MISMATCH", p.get("refused").get(0).get("code").asText());
    }

    @Test
    void sectionCanBeAssignedToAMemberlessEmployerAndOptionsListIt() throws Exception {
        UUID memberless = employer(api, "Hand made", null, "2020-01-01", null);
        JsonNode p = preview(List.of("a.pdf"), List.of(a(C_APR)), List.of("member:" + MEMBER + "=" + memberless), 200);
        JsonNode pv = p.get("sections").get(0).get("preview");
        assertEquals(memberless.toString(), pv.get("employer").get("existingId").asText());
        assertEquals(1, pv.get("employerOptions").size());
        JsonNode r = commit(List.of("a.pdf"), List.of(a(C_APR)), List.of("member:" + MEMBER + "=" + memberless), 200);
        assertEquals(memberless.toString(), r.get("results").get(0).get("employerId").asText());
        assertEquals(MEMBER, api.getJson(BASE + "/employers", 200).get(0).get("memberId").asText());
    }

    @Test
    void sameEmployerAssignedTwiceIsRejected() throws Exception {
        UUID memberless = employer(api, "Hand made", null, "2020-01-01", null);
        List<String> assign = List.of("member:" + MEMBER + "=" + memberless, "member:" + MEMBER_B + "=" + memberless);
        JsonNode err = preview(List.of("a.pdf", "b.pdf"), List.of(a(C_APR), b(C_APR)), assign, 400);
        assertEquals("VALIDATION_ERROR", err.get("code").asText());
        assertEquals("VALIDATION_ERROR",
                commit(List.of("a.pdf", "b.pdf"), List.of(a(C_APR), b(C_APR)), assign, 400).get("code").asText());
    }

    @Test
    void unknownAssignKeyIsRejected() throws Exception {
        UUID memberless = employer(api, "Hand made", null, "2020-01-01", null);
        JsonNode err = preview(List.of("a.pdf"), List.of(a(C_APR)), List.of("member:NOPE=" + memberless), 400);
        assertEquals("VALIDATION_ERROR", err.get("code").asText());
    }

    @Test
    void moreThanTwelveFilesIsRejected() throws Exception {
        List<String> names = new ArrayList<>();
        List<byte[]> files = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            names.add("f" + i + ".pdf");
            files.add(a(C_APR));
        }
        JsonNode err = preview(names, files, List.of(), 400);
        assertEquals("VALIDATION_ERROR", err.get("code").asText());
        assertEquals("Upload at most 12 passbooks at once.", message(err));
    }

    @Test
    void commitImportsAllSectionsInOneGoAndRecommitSkipsEverything() throws Exception {
        List<String> names = List.of("a1.pdf", "a2.pdf", "b.pdf", "junk.pdf");
        List<byte[]> files = List.of(a(C_APR, INTEREST), a(C_APR, C_MAY), b(C_JUN),
                PassbookTestPdf.pdf(List.of("Grocery list", "Milk 2")));
        JsonNode r = commit(names, files, List.of(), 200);
        assertEquals(2, r.get("results").size());
        assertEquals(4, r.get("imported").asInt()); // APR, INTEREST, MAY + JUN
        assertEquals(0, r.get("replaced").asInt());
        assertEquals(0, r.get("skipped").asInt());
        assertAmount("95630", r.get("balance")); // 56180 + 3 x 13150
        assertEquals(1, r.get("refused").size());
        assertEquals(2, api.getJson(BASE + "/employers", 200).size());
        assertEquals(3, entries(r.get("results").get(0)).size());
        assertEquals(1, entries(r.get("results").get(1)).size());

        JsonNode again = commit(names, files, List.of(), 200);
        assertEquals(0, again.get("imported").asInt());
        assertEquals(4, again.get("skipped").asInt());
        assertEquals(2, api.getJson(BASE + "/employers", 200).size());
    }

    @Test
    void commitWithEverythingRefusedIsRejected() throws Exception {
        JsonNode err = commit(List.of("x.pdf"), List.of("not a pdf".getBytes()), List.of(), 400);
        assertEquals("VALIDATION_ERROR", err.get("code").asText());
        assertEquals("None of these files could be imported.", message(err));
    }

    @Test
    void batchReplacesManualEntriesAndSectionPoolsStayIndependent() throws Exception {
        UUID empA = employer(api, "Acme", MEMBER, "2014-01-01", null);
        UUID empB = employer(api, "Contoso", MEMBER_B, "2014-01-01", null);
        UUID manualA = addEntryId(contribution(empA, "2026-04-01", "2026-05-10", 7000, 5000, 1000));
        List<String> names = List.of("a.pdf", "b.pdf");
        List<byte[]> files = List.of(a(C_MAY), b(C_APR));
        JsonNode p = preview(names, files, List.of(), 200);
        JsonNode pa = p.get("sections").get(0).get("preview");
        JsonNode pb = p.get("sections").get(1).get("preview");
        assertEquals(0, pa.get("replacesCount").asInt());
        assertEquals(1, pa.get("newCount").asInt());
        assertEquals(0, pb.get("replacesCount").asInt(), "A's manual entry is not consumed by B's section");
        assertEquals(1, pb.get("newCount").asInt());

        files = List.of(a(C_APR), b(C_APR));
        p = preview(names, files, List.of(), 200);
        assertEquals(1, p.get("sections").get(0).get("preview").get("replacesCount").asInt());
        assertEquals(manualA.toString(),
                p.get("sections").get(0).get("preview").get("rows").get(0).get("replacesEntryId").asText());
        assertEquals(0, p.get("sections").get(1).get("preview").get("replacesCount").asInt());
        JsonNode r = commit(names, files, List.of(), 200);
        assertEquals(1, r.get("replaced").asInt());
        assertEquals(1, r.get("imported").asInt());
        assertEquals("PASSBOOK", api.getJson(BASE + "/entries/" + manualA, 200).get("source").asText());
        assertEquals(1, entries(r.get("results").get(1)).size());
        assertEquals(empB.toString(), r.get("results").get(1).get("employerId").asText());
    }

    @Test
    void assigningANonExistentEmployerIs404() throws Exception {
        List<String> assign = List.of("member:" + MEMBER + "=" + UUID.randomUUID());
        assertEquals("NOT_FOUND", preview(List.of("a.pdf"), List.of(a(C_APR)), assign, 404).get("code").asText());
        assertEquals("NOT_FOUND", commit(List.of("a.pdf"), List.of(a(C_APR)), assign, 404).get("code").asText());
    }

    @Test
    void filesWithoutAUanLinePreviewAndCommitAlongsideAFileWithOne() throws Exception {
        byte[] noUan = passbook(MEMBER_B, null, "CONTOSO WORKS LIMITED", C_APR);
        List<String> names = List.of("noUan.pdf", "a.pdf");
        List<byte[]> files = List.of(noUan, a(C_APR));
        JsonNode p = preview(names, files, List.of(), 200);
        assertEquals(2, p.get("sections").size());
        assertEquals(0, p.get("refused").size());
        assertTrue(p.get("sections").get(0).get("preview").get("employer").get("uanLast4").isNull());
        assertEquals("4321", p.get("sections").get(1).get("preview").get("employer").get("uanLast4").asText());
        JsonNode r = commit(names, files, List.of(), 200);
        assertEquals(2, r.get("imported").asInt());
        assertEquals(0, r.get("refused").size());
    }

    @Test
    void aFailedCommitWritesNothing() throws Exception {
        UUID memberless = employer(api, "Hand made", null, "2020-01-01", null);
        List<String> assign = List.of("member:" + MEMBER + "=" + memberless, "member:" + MEMBER_B + "=" + memberless);
        commit(List.of("a.pdf", "b.pdf"), List.of(a(C_APR), b(C_APR)), assign, 400);
        assertEquals(1, api.getJson(BASE + "/employers", 200).size());
        assertEquals(0, api.getJson(BASE + "/entries?size=50", 200).get("content").size());
        assertTrue(api.getJson(BASE + "/employers", 200).get(0).get("memberId").isNull());
    }

    private JsonNode entries(JsonNode result) throws Exception {
        return api.getJson(BASE + "/entries?employerId=" + result.get("employerId").asText() + "&size=100", 200)
                .get("content");
    }
}
