package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompileRequest;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
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
 * Core compilation service.
 *
 * <p>Per-term pipeline:
 * <ol>
 *   <li>{@link TermSyntaxTranslator#translate} — custom query lang → PCRE + flags</li>
 *   <li>{@link HyperscanCompiler#validate}     — Hyperscan Database.compile() check</li>
 *   <li>Build {@link TermCompilationResult}    — echo input + append result fields</li>
 * </ol>
 *
 * <p>Uses JDK 21 pattern matching switch on the sealed {@link TranslationResult}.
 * Stateless — safe for concurrent virtual-thread requests.
 */
@Service
public class LexiconCompileService {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileService.class);

    private final TermSyntaxTranslator translator;
    private final HyperscanCompiler    compiler;
    private final Counter              passCounter;
    private final Counter              failCounter;

    public LexiconCompileService(TermSyntaxTranslator translator,
                                  HyperscanCompiler compiler,
                                  MeterRegistry meterRegistry) {
        this.translator  = translator;
        this.compiler    = compiler;
        this.passCounter = meterRegistry.counter("lexicon.compile.pass");
        this.failCounter = meterRegistry.counter("lexicon.compile.failed");
    }

    /**
     * Compiles all terms in the request.
     *
     * <p>HTTP 200 is always returned for structurally valid requests even when
     * individual terms fail. Per-term {@code compilationStatus=FAILED} carries details.
     *
     * @param request validated compile request
     * @return compile response with per-term results and summary counts
     */
    public CompileResponse compile(CompileRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("Compiling {} term(s) for rule '{}'",
                request.getTerms().size(), request.getLexiconRuleName());

        List<TermCompilationResult> results = new ArrayList<>(request.getTerms().size());
        for (CompileRequest.TermInput term : request.getTerms()) {
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
                request.getLexiconRuleName(), results, elapsed,
                compiler.getHyperscanVersion());
    }

    /**
     * Returns the engine mode identifier for the health endpoint.
     *
     * @return always {@code "HYPERSCAN_NATIVE"}
     */
    public String getEngineMode() {
        return compiler.getEngineMode();
    }

    // ── Per-term pipeline ─────────────────────────────────────────────────────

    /**
     * Runs translate → validate for one term using JDK 21 pattern matching switch.
     */
    private TermCompilationResult compileTerm(CompileRequest.TermInput term) {
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
            case TranslationResult.Error err ->
                    TermCompilationResult.failedTranslation(term, err.message());

            case TranslationResult.Success success -> {
                HyperscanCompiler.ValidationResult validation =
                        compiler.validate(success.hsPattern(), success.hsFlags());
                yield validation.isPass()
                        ? TermCompilationResult.pass(
                                term, success.hsPattern(),
                                success.hsFlags(), success.requiresAndPostFilter())
                        : TermCompilationResult.failedHyperscan(
                                term, success.hsPattern(),
                                validation.errorMessage(), success.hsFlags());
            }
        };
    }
}
