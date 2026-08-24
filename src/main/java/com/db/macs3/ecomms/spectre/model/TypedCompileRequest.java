package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /api/lexicon/compile/bundle}.
 *
 * <h2>Schema changes from the previous per-term version</h2>
 * <ul>
 *   <li>{@code termType} has moved from each individual {@code TermInput} to
 *       the <em>root level</em> — a single request now declares one term type
 *       for all its terms. Mixed-type requests are no longer supported; submit
 *       separate requests for Natural Language and Regex terms.</li>
 *   <li>{@code riskDriverName} has been removed entirely.</li>
 *   <li>{@code request_id} has been added for end-to-end request tracing; it
 *       is echoed back unchanged in the response JSON.</li>
 * </ul>
 *
 * <p>JSON example:
 * <pre>
 * {
 *   "request_id": "550e8400-e29b-41d4-a716-446655440000",
 *   "lexiconRuleName": "lexicon_research_1",
 *   "termType": "Natural Language",
 *   "terms": [
 *     {
 *       "termId": "lexicon_research_1::1",
 *       "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))"
 *     },
 *     {
 *       "termId": "lexicon_research_1::2",
 *       "termDescription": "insider AND trading"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>See {@link TermType} for exactly what {@code "Natural Language"} and
 * {@code "Regex"} mean for how {@code termDescription} is interpreted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TypedCompileRequest {

    /**
     * Caller-assigned identifier for this request, echoed unchanged in the
     * response JSON. Typically a UUID but any non-blank string is accepted.
     * Used to correlate requests and responses across distributed systems.
     */
    @NotBlank(message = "request_id must not be blank")
    @JsonProperty("request_id")
    private String requestId;

    @NotBlank(message = "lexiconRuleName must not be blank")
    @JsonProperty("lexiconRuleName")
    private String lexiconRuleName;

    /**
     * Compilation strategy applied uniformly to every term in this request.
     * See {@link TermType} for the exact semantics of each value.
     */
    @NotNull(message = "termType must be exactly 'Natural Language' or 'Regex'")
    @JsonProperty("termType")
    private TermType termType;

    @NotEmpty(message = "terms list must not be empty")
    @Valid
    @JsonProperty("terms")
    private List<TermInput> terms = new ArrayList<>();

    // ── Nested record: TermInput ──────────────────────────────────────────────

    /**
     * One lexicon term. Contains only the term's identifier and its description.
     * {@code termType} and {@code riskDriverName} are no longer carried
     * per-term (see root-level fields above).
     *
     * @param termId          unique identifier, echoed back in the result
     * @param termDescription operator-language expression ({@link TermType#NATURAL_LANGUAGE})
     *                        or raw PCRE pattern ({@link TermType#REGEX}) —
     *                        determined by the root {@code termType}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TermInput(

            @JsonProperty("termId")
            @NotBlank(message = "termId must not be blank")
            String termId,

            @JsonProperty("termDescription")
            @NotBlank(message = "termDescription must not be blank")
            String termDescription

    ) {
    }

    // ── Derived helpers ────────────────────────────────────────────────────────

    /**
     * @return {@code true} when {@link #termType} is {@link TermType#REGEX},
     * meaning every term's description is a raw PCRE pattern
     * requiring no operator-language translation.
     */
    public boolean isRegexType() {
        return termType == TermType.REGEX;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = v;
    }

    public String getLexiconRuleName() {
        return lexiconRuleName;
    }

    public void setLexiconRuleName(String v) {
        this.lexiconRuleName = v;
    }

    public TermType getTermType() {
        return termType;
    }

    public void setTermType(TermType v) {
        this.termType = v;
    }

    public List<TermInput> getTerms() {
        return terms;
    }

    public void setTerms(List<TermInput> v) {
        this.terms = v;
    }
}
