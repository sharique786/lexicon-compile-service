package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record TermCompilationResult(

        /** Echoed from input. */
        @JsonProperty("termId")
        String termId,

        /** Echoed from input. */
        @JsonProperty("termDescription")
        String termDescription,

        /** Echoed from input. */
        @JsonProperty("riskDriverName")
        String riskDriverName,

        /** PASS or FAILED. */
        @JsonProperty("compilationStatus")
        CompilationStatus compilationStatus,

        /**
         * Hyperscan PCRE pattern produced by {@code TermSyntaxTranslator}.
         * Present for both PASS and FAILED (may be the partial attempt that failed).
         */
        @JsonProperty("translatedPattern")
        String translatedPattern,

        /**
         * Hyperscan error from {@code CompileErrorException.getMessage()}.
         * Null when compilationStatus = PASS.
         */
        @JsonProperty("errorLog")
        String errorLog,

        /**
         * Translation-stage error (before Hyperscan compile was attempted).
         * Null when compilationStatus = PASS.
         */
        @JsonProperty("translationError")
        String translationError,

        /**
         * Hyperscan flag bitmask used for compilation.
         * 1=CASELESS, 2=DOTALL, 32=UTF8, 64=UCP.
         */
        @JsonProperty("hyperscanFlags")
        int hyperscanFlags,

        /**
         * True when an AND operator produced a lookahead pattern.
         * The scan-time caller should apply Java-side post-filtering
         * to ensure all AND operands match the same document.
         */
        @JsonProperty("requiresAndPostFilter")
        boolean requiresAndPostFilter,

        @JsonProperty("compiledAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant compiledAt

) {
    // ── Factory methods ───────────────────────────────────────────────────────

    /** Creates a PASS result. */
    public static TermCompilationResult pass(CompileRequest.TermInput input,
                                              String translatedPattern,
                                              int hyperscanFlags,
                                              boolean requiresAndPostFilter) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(), input.riskDriverName(),
                CompilationStatus.PASS,
                translatedPattern, null, null,
                hyperscanFlags, requiresAndPostFilter,
                Instant.now());
    }

    /** Creates a FAILED result with Hyperscan compile error. */
    public static TermCompilationResult failedHyperscan(CompileRequest.TermInput input,
                                                         String translatedPattern,
                                                         String errorLog,
                                                         int hyperscanFlags) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(), input.riskDriverName(),
                CompilationStatus.FAILED,
                translatedPattern, errorLog, null,
                hyperscanFlags, false,
                Instant.now());
    }

    /** Creates a FAILED result due to translation error. */
    public static TermCompilationResult failedTranslation(CompileRequest.TermInput input,
                                                           String translationError) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(), input.riskDriverName(),
                CompilationStatus.FAILED,
                null, null, translationError,
                0, false,
                Instant.now());
    }

    public boolean isPass()   { return CompilationStatus.PASS   == compilationStatus; }
    public boolean isFailed() { return CompilationStatus.FAILED == compilationStatus; }
}
