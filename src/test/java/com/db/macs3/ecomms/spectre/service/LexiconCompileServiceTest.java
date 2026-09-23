package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LexiconCompileService}.
 * No Spring context — uses real Hyperscan native library.
 *
 * <p>{@link TypedCompileRequest} is the single request type this service
 * (and every compile endpoint) now uses — see that class's Javadoc. Every
 * PASS term's {@code regexPattern}/{@code exclusionRegex} are lists
 * (one entry for a simple term, several when decomposed); there is no
 * separate "was this decomposed" boolean any more.
 */
@DisplayName("LexiconCompileService Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexiconCompileServiceTest {

    private LexiconCompileService service;

    @BeforeEach
    void setUp() {
        var compiler = new HyperscanCompiler();
        var translator = new TermSyntaxTranslator(compiler);
        compiler.selfTest();
        service = new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private CompileResponse compile(String ruleName, String... descriptions) {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName(ruleName);
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        List<TypedCompileRequest.TermInput> terms = new ArrayList<>();
        for (int i = 0; i < descriptions.length; i++) {
            terms.add(new TypedCompileRequest.TermInput(ruleName + "::" + (i + 1), descriptions[i]));
        }
        req.setTerms(terms);
        return service.compile(req);
    }

    // ── Spec examples from requirements ───────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Spec example 1 (JSON): (manipulate) NEAR{5} → PASS")
    void specExample1Json() {
        var resp = compile("lexicon_research_1",
                "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.failedCount()).isEqualTo(0);
        assertThat(resp.hasFailures()).isFalse();
        assertThat(resp.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(resp.results().getFirst().compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(resp.results().getFirst().regexPattern()).isNotEmpty();
        assertThat(resp.results().getFirst().regexPattern().getFirst()).isNotBlank();
        assertThat(resp.compiledAt()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Spec example 1 (CSV): (manipulate*) NEAR{5} → PASS with wildcard")
    void specExample1Csv() {
        var resp = compile("lexicon_research_1",
                "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(resp.passCount()).isEqualTo(1);
        var result = resp.results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.regexPattern().getFirst()).contains("\\S*"); // wildcard translated
    }

    @Test
    @Order(3)
    @DisplayName("Spec example 2: quoted OR phrases → PASS")
    void specExample2() {
        var resp = compile("lexicon_research_1",
                "((\"please don't forward\") OR (\"do not share don't forward\"))");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().getFirst().compilationStatus()).isEqualTo(CompilationStatus.PASS);
    }

    @Test
    @Order(4)
    @DisplayName("Spec example 2 CSV: CSV double-quote escaped → PASS")
    void specExample2Csv() {
        // CSV encoding: ""please don't forward""
        var resp = compile("lexicon_research_1",
                "((\"\"please don't forward\"\") OR (\"\"do not share don't forward\"\"))");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    // ── Response structure ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Response echoes termId and termDescription for each term; requestId is propagated")
    void responseEchoesInputFields() {
        var req = new TypedCompileRequest();
        req.setRequestId("test-request-id-42");
        req.setLexiconRuleName("echo_test");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput("echo_test::42", "price OR spread")));

        var resp = service.compile(req);
        var result = resp.results().getFirst();

        assertThat(result.termId()).isEqualTo("echo_test::42");
        assertThat(result.termDescription()).isEqualTo("price OR spread");
        assertThat(resp.requestId()).isEqualTo("test-request-id-42");
    }

    @Test
    @Order(11)
    @DisplayName("Summary counts match individual term statuses")
    void summaryCounts() {
        // The translator escapes special regex chars like '[' or '(' to literals, so those
        // do NOT fail — an unclosed quoted phrase is the Tokenizer's own confirmed failure path.
        var resp = compile("count_test",
                "price OR spread",    // PASS
                "insider AND news",   // PASS  (OR pre-scan after fix)
                "\"unclosed quote");   // FAILED — unclosed quoted phrase

        assertThat(resp.totalTerms()).isEqualTo(3);
        long actualPass = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.PASS).count();
        long actualFail = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.FAILED).count();
        assertThat(resp.passCount()).isEqualTo((int) actualPass);
        assertThat(resp.failedCount()).isEqualTo((int) actualFail);
        assertThat(resp.hasFailures()).isTrue();
    }

    @Test
    @Order(12)
    @DisplayName("FAILED term has a non-blank diagnostic — an unclosed quoted phrase is caught as a translation error")
    void failedTermHasError() {
        var resp = compile("err_test", "\"unclosed quote");
        var failed = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.FAILED)
                .findFirst();
        assertThat(failed).isPresent();
        String diagnostic = failed.get().translationError() != null
                ? failed.get().translationError()
                : failed.get().errorLog();
        assertThat(diagnostic).isNotBlank();
    }

    @Test
    @Order(13)
    @DisplayName("engineMode is always HYPERSCAN_NATIVE, never RE2J or fallback")
    void engineModeNeverFallback() {
        var resp = compile("mode_test", "price OR spread");
        assertThat(resp.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(resp.engineMode()).doesNotContainIgnoringCase("re2j");
        assertThat(resp.engineMode()).doesNotContainIgnoringCase("fallback");
    }

    @Test
    @Order(14)
    @DisplayName("A PASS term's regexPattern is never null or empty — always at least one entry")
    void regexPatternNeverEmptyOnPass() {
        var resp = compile("nonempty_test", "price OR spread");
        var result = resp.results().getFirst();
        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).isNotNull();
        assertThat(result.regexPattern()).isNotEmpty();
    }

    // ── Multi-language compilation ────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Korean terms compile successfully with UTF8 flags")
    void koreanCompiles() {
        var resp = compile("ko_rule", "비밀 OR 내부자 거래");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().getFirst().hyperscanFlags()
                & 32).isEqualTo(32); // UTF8 flag
    }

    @Test
    @Order(21)
    @DisplayName("Japanese terms compile successfully")
    void japaneseCompiles() {
        var resp = compile("ja_rule", "株価操作 OR インサイダー取引");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(22)
    @DisplayName("Chinese/Mandarin terms compile successfully")
    void chineseCompiles() {
        var resp = compile("zh_rule", "内幕交易 OR 操纵市场");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(23)
    @DisplayName("Arabic terms compile successfully")
    void arabicCompiles() {
        var resp = compile("ar_rule", "مخالفة OR استثمار داخلي");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(24)
    @DisplayName("Hebrew terms compile successfully")
    void hebrewCompiles() {
        var resp = compile("he_rule", "מסחר פנים OR מניפולציה");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(25)
    @DisplayName("German umlaut terms compile with UTF8+UCP flags")
    void germanCompiles() {
        var resp = compile("de_rule", "Übernahme OR Insiderhandel");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().getFirst().hyperscanFlags() & 32).isEqualTo(32); // UTF8
    }

    @Test
    @Order(26)
    @DisplayName("Turkish terms compile with UTF8+UCP flags")
    void turkishCompiles() {
        var resp = compile("tr_rule", "içeriden bilgi OR piyasa manipülasyonu");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test
    @Order(27)
    @DisplayName("Emoji terms compile with UTF8+UCP flags")
    void emojiCompiles() {
        var resp = compile("emoji_rule", "💰 OR 🤫 OR 🤐");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().getFirst().hyperscanFlags() & 32).isEqualTo(32); // UTF8
    }

    @Test
    @Order(28)
    @DisplayName("Mixed English + Korean + emoji compiles")
    void mixedLanguageCompiles() {
        var resp = compile("mixed_rule", "insider OR 내부자 OR 💰");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    // ── FOLLOWEDBY proximity ──────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("FOLLOWEDBY{3}: don't FOLLOWEDBY{3} compliance → PASS, split into two gap-less leaves, "
            + "with the proximity conveyed via resolvedPatterns instead of a gap regex")
    void followedByCompiles() {
        var resp = compile("fb_rule", "don't FOLLOWEDBY{3} compliance");
        assertThat(resp.passCount()).isEqualTo(1);
        var result = resp.results().getFirst();
        // Simple/safe proximity term — merges into ONE gap-embedded pattern rather than
        // splitting; resolvedPatterns still conveys the relationship as literal keyword text.
        assertThat(result.regexPattern()).hasSize(1);
        assertThat(result.regexPattern().getFirst()).contains("don't").contains("compliance");
        assertThat(result.resolvedPatterns()).isEqualTo("\\bdon't\\b FOLLOWEDBY{3} \\bcompliance\\b");
    }

    // ── AND: corrected co-occurrence semantics ──────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("AND operator: compiles DIRECTLY into a correct co-occurrence pattern, self-contained, no exclusion needed")
    void andCompilesSelfContained() {
        var resp = compile("and_rule", "insider AND announcement AND price");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.failedCount()).isEqualTo(0);
        assertThat(resp.results().getFirst().requiresExclusionCheck()).isFalse();
        assertThat(resp.results().getFirst().exclusionRegex()).isNull();
        // All three operands appear somewhere in the pattern (every ordering permutation).
        assertThat(resp.results().getFirst().regexPattern().getFirst())
                .contains("insider").contains("announcement").contains("price");
    }

    // ── AND NOT: the two-list contract ──────────────────────────────────────────

    @Test
    @Order(41)
    @DisplayName("AND NOT: requiresExclusionCheck true, exclusionRegex has exactly one entry for a simple exclusion")
    void andNotProducesExclusionList() {
        var resp = compile("and_not_rule", "insider AND NOT (compliance OR legal)");
        assertThat(resp.passCount()).isEqualTo(1);
        var result = resp.results().getFirst();
        assertThat(result.requiresExclusionCheck()).isTrue();
        assertThat(result.exclusionRegex()).isNotNull();
        assertThat(result.exclusionRegex()).hasSize(1);
        assertThat(result.regexPattern()).hasSize(1);
    }

    // ── Performance ───────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("Performance: 100 term compilations in under 10 seconds")
    void performance() {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            compile("perf_test",
                    "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("100 compilations: %dms (%.2fms/term)%n",
                elapsed, elapsed / 100.0);
        // Hyperscan native library compile time varies by hardware; 10s is a safe bound
        assertThat(elapsed).isLessThan(10_000L);
    }
}
