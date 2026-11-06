package com.financeos.statement.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PdfPasswordsTest {

    @Test
    void variantsAreAsGivenThenLowerThenUpper() {
        assertThat(PdfPasswords.variants("AbCd12")).containsExactly("AbCd12", "abcd12", "ABCD12");
        assertThat(PdfPasswords.variants("ABCD1234")).containsExactly("ABCD1234", "abcd1234");
        assertThat(PdfPasswords.variants("abcd1234")).containsExactly("abcd1234", "ABCD1234");
    }

    @Test
    void variantsAreDistinctAndSkipBlank() {
        assertThat(PdfPasswords.variants("12345678")).containsExactly("12345678");
        assertThat(PdfPasswords.variants(null)).isEmpty();
        assertThat(PdfPasswords.variants("  ")).isEmpty();
        assertThat(PdfPasswords.variants("")).isEmpty();
    }

    @Test
    void workingPasswordIsTheVariantThatOpens() throws Exception {
        assertThat(PdfPasswords.workingPdf(ProtectedPdfs.pdf("abcd1234"), "ABCD1234")).isEqualTo("abcd1234");
        assertThat(PdfPasswords.workingPdf(ProtectedPdfs.pdf("ABCD1234"), "ABCD1234")).isEqualTo("ABCD1234");
        assertThat(PdfPasswords.workingPdf(ProtectedPdfs.pdf("other"), "ABCD1234")).isEqualTo("ABCD1234");
    }

    @Test
    void documentReaderOpensWithTheOtherCase() throws Exception {
        // StatementPdfReader reads through DocumentReader
        var read = (java.util.function.BiFunction<String, String, com.financeos.gmail.accountdetect.StatementPdfReader.Status>)
                (pdfPwd, typed) -> {
                    try {
                        return com.financeos.gmail.accountdetect.StatementPdfReader.read(ProtectedPdfs.pdf(pdfPwd), "PDF_MAIL",
                                "hdfc", java.time.YearMonth.of(2026, 10), typed).status();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
        assertThat(read.apply("abcd1234", "ABCD1234")).isEqualTo(com.financeos.gmail.accountdetect.StatementPdfReader.Status.OPENED);
        assertThat(read.apply("ABCD1234", "ABCD1234")).isEqualTo(com.financeos.gmail.accountdetect.StatementPdfReader.Status.OPENED);
        assertThat(read.apply("other", "ABCD1234")).isNotEqualTo(com.financeos.gmail.accountdetect.StatementPdfReader.Status.OPENED);
    }

    @Test
    void pdfWordExtractorOpensWithTheOtherCase() throws Exception {
        List<Line> lines = new PdfWordExtractor().extract(ProtectedPdfs.pdf("abcd1234"), "ABCD1234");
        assertThat(lines).isNotEmpty();
        assertThat(new PdfWordExtractor().extract(ProtectedPdfs.pdf("ABCD1234"), "ABCD1234")).isNotEmpty();
        assertThatThrownBy(() -> new PdfWordExtractor().extract(ProtectedPdfs.pdf("other"), "ABCD1234"))
                .isInstanceOf(StatementParseException.class).hasMessageContaining("password");
    }
}
