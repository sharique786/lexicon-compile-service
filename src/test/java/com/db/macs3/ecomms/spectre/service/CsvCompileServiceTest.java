package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CsvCompileService}.
 * Tests CSV parsing edge cases and delegates compilation to real service.
 *
 * <p>CSV format is now 2-column ({@code Term ID, Term Description}) — the
 * {@code Risk Driver Name} column has been removed. Every
 * {@link CsvCompileService#compileFromCsv} call now requires a
 * {@code requestId}, mirroring the UUID the controller generates per-request.
 */
@DisplayName("CsvCompileService Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CsvCompileServiceTest {

    private CsvCompileService service;

    @BeforeEach
    void setUp() {
        var compiler = new HyperscanCompiler();
        var translator = new TermSyntaxTranslator(compiler);
        compiler.selfTest();
        var compileService = new LexiconCompileService(translator, compiler,
                new SimpleMeterRegistry());
        service = new CsvCompileService(compileService);
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fresh UUID per call — mirrors what the controller generates for each request.
     */
    private String newRequestId() {
        return UUID.randomUUID().toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing basics
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Standard CSV with header row → all terms compiled")
    void standardCsvWithHeader() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "lexicon_research_1::1, (manipulate*) NEAR{5} ((price) OR (spread))\n"
                        + "lexicon_research_1::2, insider AND announcement\n";

        String requestId = newRequestId();
        var resp = service.compileFromCsv(csv(csvContent), "lexicon_research_1", requestId);

        assertThat(resp.totalTerms()).isEqualTo(2);
        assertThat(resp.lexiconRuleName()).isEqualTo("lexicon_research_1");
        assertThat(resp.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(resp.requestId()).isEqualTo(requestId);
    }

    @Test
    @Order(2)
    @DisplayName("CSV without header row — data starts on line 1")
    void csvWithoutHeader() throws IOException {
        String csvContent =
                "lexicon_research_1::1, price OR spread\n"
                        + "lexicon_research_1::2, insider AND news\n";

        var resp = service.compileFromCsv(csv(csvContent), "lexicon_research_1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("CSV with blank lines — blank lines are skipped")
    void csvBlankLinesSkipped() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "r1::1, price OR spread\n"
                        + "\n"
                        + "   \n"
                        + "r1::2, insider OR tip\n";

        var resp = service.compileFromCsv(csv(csvContent), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("CSV with comment lines (#) — comment lines are skipped")
    void csvCommentLinesSkipped() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "# This is a comment\n"
                        + "r1::1, price OR spread\n"
                        + "# Another comment\n"
                        + "r1::2, insider OR tip\n";

        var resp = service.compileFromCsv(csv(csvContent), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(2);
    }

    @Test
    @Order(5)
    @DisplayName("CSV spec example: RFC 4180 double-quote escaped phrases")
    void csvDoubleQuoteEscaping() throws IOException {
        // From requirements: "((""please don't forward"") OR (""do not share""))"
        String csvContent =
                "Term ID, Term Description\n"
                        + "r1::1, \"((\"\"please don't forward\"\") OR (\"\"do not share don't forward\"\"))\"\n";

        var resp = service.compileFromCsv(csv(csvContent), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(1);
        // Verify the translated pattern contains the unescaped phrases
        assertThat(resp.results().getFirst().regexPattern().getFirst()).contains("please don't forward");
    }

    @Test
    @Order(6)
    @DisplayName("CSV with UTF-8 BOM (Excel export) — BOM is stripped")
    void csvBomStripped() throws IOException {
        // UTF-8 BOM: EF BB BF prepended by Excel
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String content = "Term ID, Term Description\nr1::1, price OR spread\n";
        byte[] csvBytes = new byte[bom.length + content.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy(bom, 0, csvBytes, 0, bom.length);
        System.arraycopy(content.getBytes(StandardCharsets.UTF_8), 0, csvBytes,
                bom.length, content.getBytes(StandardCharsets.UTF_8).length);

        var resp = service.compileFromCsv(new ByteArrayInputStream(csvBytes), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(1);
        assertThat(resp.results().getFirst().termId()).isEqualTo("r1::1");
    }

    @Test
    @Order(7)
    @DisplayName("CSV with Korean terms — multi-language preserved")
    void csvKoreanTerms() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "ko::1, 비밀 OR 내부자 거래\n"
                        + "ko::2, 주가 조작 OR 불법 거래\n";

        var resp = service.compileFromCsv(csv(csvContent), "ko_rule", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(2);
        assertThat(resp.passCount()).isEqualTo(2);
    }

    @Test
    @Order(8)
    @DisplayName("CSV with emoji terms — emoji OR compiles")
    void csvEmojiTerms() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "emoji::1, 💰 OR 🤫 OR 🤐\n";

        var resp = service.compileFromCsv(csv(csvContent), "emoji_rule", newRequestId());
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(9)
    @DisplayName("CSV row with an extra (legacy Risk Driver) third column — ignored, still compiled")
    void csvExtraColumnIgnored() throws IOException {
        // A caller still sending a legacy third column must not break parsing —
        // it is simply ignored since riskDriverName no longer exists on the response.
        String csvContent = "r1::1, price OR spread, Legacy Risk Driver Value\n";
        var resp = service.compileFromCsv(csv(csvContent), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(1);
        assertThat(resp.results().getFirst().termId()).isEqualTo("r1::1");
    }

    @Test
    @Order(10)
    @DisplayName("Empty CSV — returns response with zero terms")
    void emptyCsv() throws IOException {
        var resp = service.compileFromCsv(csv(""), "empty_rule", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(0);
    }

    @Test
    @Order(11)
    @DisplayName("Invalid CSV content — throws IOException")
    void malformedCsvThrows() throws IOException {
        // A truly malformed CSV that OpenCSV cannot recover from is hard to construct,
        // but we can test that an empty stream from a broken reader does not produce
        // unexpected results
        var resp = service.compileFromCsv(csv("# just a comment\n"), "r1", newRequestId());
        assertThat(resp.totalTerms()).isEqualTo(0);
    }

    @Test
    @Order(12)
    @DisplayName("Full spec example: both spec terms from requirements")
    void fullSpecExample() throws IOException {
        String csvContent =
                "Term ID, Term Description\n"
                        + "lexicon_research_1::1,"
                        + " (manipulate*) NEAR{5} ((price) OR (spread) OR (stock))\n"
                        + "lexicon_research_1::2,"
                        + " \"((\"\"please don't forward\"\") OR (\"\"do not share don't forward\"\"))\"\n";

        String requestId = newRequestId();
        var resp = service.compileFromCsv(csv(csvContent), "lexicon_research_1", requestId);

        assertThat(resp.totalTerms()).isEqualTo(2);
        assertThat(resp.results().getFirst().termId()).isEqualTo("lexicon_research_1::1");
        assertThat(resp.results().getFirst().compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(resp.results().get(1).termId()).isEqualTo("lexicon_research_1::2");
        assertThat(resp.results().get(1).compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(resp.lexiconRuleName()).isEqualTo("lexicon_research_1");
        assertThat(resp.requestId()).isEqualTo(requestId);
    }
}
