package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a lexicon term's {@code termDescription} should be compiled.
 *
 * <h2>Naming history</h2>
 * <p>This replaces the earlier {@code "Standard"} / {@code "NLT"} string-based
 * {@code termType} values:
 * <ul>
 *   <li>{@code "Standard"} → {@link #NATURAL_LANGUAGE} — the term uses the
 *       operator language (OR / AND / NEAR{n} / FOLLOWEDBY{n} / wildcards /
 *       quoted phrases) and is translated into Hyperscan PCRE by
 *       {@link com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator}.</li>
 *   <li>{@code "NLT"} → {@link #REGEX} — the term's {@code termDescription}
 *       is already a valid PCRE pattern supplied by the caller. No
 *       translation step runs; the pattern is compiled by Hyperscan exactly
 *       as given. Script-aware flags (UTF8/UCP) are still derived
 *       automatically from the pattern's content.</li>
 * </ul>
 *
 * <p>This mirrors the {@code TermType} already used by the downstream
 * Lexicon Scanner Service, so the same two names mean the same thing on
 * both sides of that integration.
 */
public enum TermType {

    /** Operator-language syntax, translated via {@code TermSyntaxTranslator}. Formerly {@code "Standard"}. */
    NATURAL_LANGUAGE("Natural Language"),

    /** Already-valid PCRE, compiled verbatim with no translation. Formerly {@code "NLT"}. */
    REGEX("Regex");

    private final String jsonValue;

    TermType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** The exact string used in JSON request/response bodies, e.g. {@code "termType": "Natural Language"}. */
    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    /**
     * Resolves a JSON {@code termType} string to a {@link TermType}, exact
     * match only (case-sensitive, matching {@link #jsonValue()} precisely).
     *
     * @param value the raw JSON string, e.g. {@code "Natural Language"} or {@code "Regex"}
     * @return the matching {@link TermType}
     * @throws IllegalArgumentException if {@code value} matches neither constant
     */
    @JsonCreator
    public static TermType fromJsonValue(String value) {
        for (TermType termType : values()) {
            if (termType.jsonValue.equals(value)) {
                return termType;
            }
        }
        throw new IllegalArgumentException(
                "termType must be exactly 'Natural Language' or 'Regex', got: '" + value + "'");
    }
}
