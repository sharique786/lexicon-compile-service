package com.db.macs3.ecomms.spectre.model;

/**
 * Thrown when a {@code /compile/bundle} request's term ids cannot support term-number-based expression ids:
 * every {@code termId} must end with {@code ::<n>} ({@code n} a non-negative integer, e.g.
 * {@code lexicon_rule_1::1}) and every {@code n} must be unique. Both are checked for the whole request
 * before any term compiles, because two terms at one Hyperscan id would silently corrupt the combined
 * database. Mapped to HTTP 400.
 */
public class InvalidTermIdException extends RuntimeException {
    public InvalidTermIdException(String message) {
        super(message);
    }
}
