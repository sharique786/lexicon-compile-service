package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import com.db.macs3.ecomms.spectre.translator.TranslationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Core compilation service behind {@code POST /compile} and {@code POST /compile/csv}, and the
 * Natural-Language term pipeline reused by {@code /compile/bundle}.
 *
 * <p>Per term: {@link TermSyntaxTranslator#translate} turns the operator-language description into
 * Hyperscan-validated pattern(s) and flags (it already validated them against real Hyperscan), and the
 * outcome becomes a {@link TermCompilationResult}. Every term is treated as Natural Language here —
 * {@link TypedCompileRequest#getRequestType()} is not consulted, so a Regex-type request is only
 * honoured by {@code /compile/bundle}.
 *
 * <p>Translation warnings (fallback to leaves, clamped gaps, skipped whole-word matching, ...) are
 * logged, not returned: the response carries no {@code warnings} field.
 *
 * <p>Stateless; safe for concurrent virtual-thread requests.
 */
@Service
public class LexiconCompileService {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileService.class);

    private final TermSyntaxTranslator translator;
    private final HyperscanCompiler compiler;
    private final Counter passCounter;
    private final Counter failCounter;

    public LexiconCompileService(TermSyntaxTranslator translator,
                                 HyperscanCompiler compiler,
                                 MeterRegistry meterRegistry) {
        this.translator = translator;
        this.compiler = compiler;
        this.passCounter = meterRegistry.counter("lexicon.compile.pass");
        this.failCounter = meterRegistry.counter("lexicon.compile.failed");
    }

    /**
     * Compiles every term in the request and summarises the outcome. HTTP 200 is returned for any
     * structurally valid request even when individual terms fail; each term's {@code compilationStatus}
     * carries the detail.
     *
     * @param request a validated compile request
     */
    public CompileResponse compile(TypedCompileRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("Compiling {} term(s) for rule '{}'",
                request.getTerms().size(), request.getLexiconRuleName());

        List<TermCompilationResult> results = new ArrayList<>(request.getTerms().size());
        for (TypedCompileRequest.TermInput term : request.getTerms()) {
            TermCompilationResult result = compileTerm(term);
            results.add(result);
            if (result.isPass()) {
                passCounter.increment();
                log.debug("PASS  termId='{}' flags={}", term.termId(), result.hyperscanFlags());
            } else {
                failCounter.increment();
                log.warn("FAIL  termId='{}' translationError='{}' errorLog='{}'",
                        term.termId(), result.translationError(), result.errorLog());
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Compile done rule='{}': {}/{} PASS, {} FAILED, {}ms",
                request.getLexiconRuleName(),
                results.stream().filter(TermCompilationResult::isPass).count(),
                results.size(),
                results.stream().filter(TermCompilationResult::isFailed).count(),
                elapsed);

        return CompileResponse.of(
                request.getRequestId(), request.getLexiconRuleName(), results, elapsed,
                compiler.getHyperscanVersion());
    }

    /**
     * @return the engine identifier, always {@code "HYPERSCAN_NATIVE"}
     */
    public String getEngineMode() {
        return compiler.getEngineMode();
    }

    // ── Per-term pipeline ─────────────────────────────────────────────────────

    /**
     * Translates one term and builds its {@link TermCompilationResult}: PASS with its pattern(s), or FAILED
     * with the translator's message. Public so {@code LexiconCompileBundleService} reuses this exact
     * pipeline for Natural-Language terms, guaranteeing identical results across endpoints. An unexpected
     * exception during translation becomes a FAILED result rather than an error response.
     */
    public TermCompilationResult compileTerm(TypedCompileRequest.TermInput term) {
        TranslationResult translation;
        try {
            translation = translator.translate(term.termDescription());
        } catch (Exception e) {
            log.error("Translation threw exception for termId='{}': {}",
                    term.termId(), e.getMessage(), e);
            return TermCompilationResult.failedTranslation(term,
                    "Translation threw exception: " + e.getMessage());
        }

        return switch (translation) {
            case TranslationResult.Error err -> TermCompilationResult.failedTranslation(term, err.message());

            case TranslationResult.Success success -> {
                if (!success.warnings().isEmpty()) {
                    log.warn("termId='{}' compiled with {} warning(s), not included in the response body: {}",
                            term.termId(), success.warnings().size(), success.warnings());
                }
                yield TermCompilationResult.pass(
                        term, success.hsPatterns(), success.hsFlags(),
                        success.requiresExclusionCheck(), success.exclusionRegexs(),
                        success.resolvedPattern(),
                        success.patternFormulaTemplate(), success.exclusionFormulaTemplate());
            }
        };
    }
}
