package com.db.macs3.ecomms.spectre.translator;

import java.util.List;

/**
 * Sealed result type for one {@link TermSyntaxTranslator#translate} call.
 *
 * <p>JDK 21 sealed interface with two permitted records. Use pattern matching switch:
 * <pre>
 * switch (result) {
 *   case TranslationResult.Success s -> use(s.hsPattern(), s.hsFlags());
 *   case TranslationResult.Error   e -> log(e.message());
 * }
 * </pre>
 */
public sealed interface TranslationResult
        permits TranslationResult.Success, TranslationResult.Error {

    /** Returns true when translation succeeded. */
    boolean isSuccess();

    /**
     * Successful translation.
     *
     * @param hsPattern            Hyperscan PCRE pattern string
     * @param hsFlags              Bitmask: 1=CASELESS, 2=DOTALL, 32=UTF8, 64=UCP
     * @param requiresAndPostFilter true when AND lookahead pattern was generated
     * @param andOperands          individual AND operand patterns for post-filtering
     */
    record Success(
            String       hsPattern,
            int          hsFlags,
            boolean      requiresAndPostFilter,
            List<String> andOperands
    ) implements TranslationResult {

        /** @return true always */
        public boolean isSuccess() { return true; }
    }

    /**
     * Failed translation.
     *
     * @param message human-readable error
     */
    record Error(String message) implements TranslationResult {

        /** @return false always */
        public boolean isSuccess() { return false; }
    }

    /** Factory: successful translation. */
    static TranslationResult success(String pattern, int flags,
                                      boolean requiresPost, List<String> operands) {
        return new Success(pattern, flags, requiresPost, List.copyOf(operands));
    }

    /** Factory: failed translation. */
    static TranslationResult error(String message) {
        return new Error(message);
    }
}
