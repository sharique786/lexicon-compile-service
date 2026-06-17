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

public record CompileResponse(

        @JsonProperty("lexiconRuleName")
        String lexiconRuleName,

        @JsonProperty("totalTerms")
        int totalTerms,

        @JsonProperty("passCount")
        int passCount,

        @JsonProperty("failedCount")
        int failedCount,

        @JsonProperty("hasFailures")
        boolean hasFailures,

        /** Always "HYPERSCAN_NATIVE" — no fallback. */
        @JsonProperty("engineMode")
        String engineMode,

        @JsonProperty("hyperscanVersion")
        String hyperscanVersion,

        @JsonProperty("compiledAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant compiledAt,

        @JsonProperty("processingTimeMs")
        long processingTimeMs,

        @JsonProperty("results")
        List<TermCompilationResult> results

) {
    /** Factory — computes all summary counts from the results list. */
    public static CompileResponse of(String ruleName,
                                      List<TermCompilationResult> results,
                                      long processingTimeMs,
                                      String hyperscanVersion) {
        int passCount   = (int) results.stream().filter(TermCompilationResult::isPass).count();
        int failedCount = (int) results.stream().filter(TermCompilationResult::isFailed).count();
        return new CompileResponse(
                ruleName,
                results.size(),
                passCount, failedCount,
                failedCount > 0,
                "HYPERSCAN_NATIVE",
                hyperscanVersion,
                Instant.now(),
                processingTimeMs,
                results);
    }
}
