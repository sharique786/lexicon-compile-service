package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context passed through the recursive {@link TermSyntaxTranslator} parser.
 *
 * <p>Accumulates flags and metadata discovered during parsing:
 * <ul>
 *   <li>{@code hasAndOp}  — set when AND/AND NOT/NOT is found → needs HS_FLAG_DOTALL</li>
 *   <li>{@code needsUtf8} — set when any non-ASCII char is found → needs HS_FLAG_UTF8|UCP</li>
 *   <li>{@code andOperands} — individual AND operand patterns for post-filter use</li>
 * </ul>
 */
public class ParseContext {

    /** HS_FLAG_CASELESS (1) — always applied. */
    public static final int HS_FLAG_CASELESS = 1;
    /** HS_FLAG_DOTALL (2) — dot matches newlines; needed for AND/NOT lookaheads. */
    public static final int HS_FLAG_DOTALL   = 2;
    /** HS_FLAG_UTF8 (32) — treat pattern as UTF-8; needed for non-ASCII content. */
    public static final int HS_FLAG_UTF8     = 32;
    /** HS_FLAG_UCP (64) — use Unicode character properties; applied alongside UTF8. */
    public static final int HS_FLAG_UCP      = 64;

    private boolean hasAndOp  = false;
    private boolean needsUtf8 = false;
    private final List<String> andOperands = new ArrayList<>();

    /** Mark that an AND / AND NOT / NOT operator was encountered. */
    void setHasAndOp()                    { this.hasAndOp  = true; }

    /** Mark that a non-ASCII character was encountered. */
    void setNeedsUtf8()                   { this.needsUtf8 = true; }

    /** Record one AND operand pattern for post-filter use. */
    void addAndOperand(String op)         { andOperands.add(op); }

    boolean isHasAndOp()                  { return hasAndOp; }
    boolean isNeedsUtf8()                 { return needsUtf8; }

    /** @return true when AND operator was used (caller should apply post-filter). */
    boolean requiresAndPostFilter()       { return hasAndOp; }

    /** @return immutable copy of AND operand patterns. */
    List<String> getAndOperands()         { return List.copyOf(andOperands); }

    /**
     * Computes the Hyperscan flag bitmask from accumulated context.
     *
     * <ul>
     *   <li>CASELESS (1)  — always</li>
     *   <li>DOTALL   (2)  — if AND / NOT used</li>
     *   <li>UTF8    (32)  — if non-ASCII present</li>
     *   <li>UCP     (64)  — alongside UTF8</li>
     * </ul>
     */
    int computeFlags() {
        int flags = HS_FLAG_CASELESS;
        if (hasAndOp)  { flags |= HS_FLAG_DOTALL; }
        if (needsUtf8) { flags |= HS_FLAG_UTF8 | HS_FLAG_UCP; }
        return flags;
    }
}
