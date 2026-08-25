package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks an {@link Ast} and generates the Hyperscan PCRE pattern string(s),
 * accumulating flags into a {@link ParseContext}.
 *
 * <p><b>AND: co-occurrence, any order, unbounded distance — WITHOUT lookahead</b>
 * <p>Hyperscan does not support lookaround of any kind. The pattern
 * {@code (?=.*A)(?=.*B)(?=.*C).*} once documented for AND in this project's
 * README was never something Hyperscan could actually compile — every term
 * using AND silently degraded to matching on ANY single operand (effectively
 * {@code A|B|C}), not all of them together. {@link #generateAnd} replaces
 * that with the SAME technique {@link #generateNear} already uses for a
 * BOUNDED gap — bidirectional alternation over an inter-operand gap — just
 * with the gap made unbounded ({@code [\s\S]*} instead of
 * {@code (?:\s+\S+){0,n}\s+}) and generalised from 2 operands to N by
 * enumerating every ordering (permutation): for {@code A AND B AND C}, the
 * six ways A/B/C can appear in the text, joined by {@code |}. This is
 * ordinary alternation and repetition — no lookaround, no backreferences —
 * so it is something Hyperscan can actually compile and match correctly.
 *
 * <p><b>AND NOT: a two-pattern contract, not a single regex</b>
 * <p>"B does not appear anywhere in this message" has no equivalent
 * construction — unlike AND's "all appear, any order", which is expressible
 * as ordinary alternation, "absent from the whole text" is exactly what
 * negative lookaround exists for, and Hyperscan has none. {@link #generateAndNot}
 * does not pretend otherwise: it returns the REQUIRED side's pattern as the
 * term's Hyperscan-scanned {@code hsPattern} (itself fully correct AND
 * semantics if it has multiple operands, via {@link #generateAnd}'s
 * recursion), and separately records the EXCLUDED side's own valid Hyperscan
 * pattern in {@link ParseContext#setExclusionPattern}. Both patterns are
 * independently Hyperscan-validated at compile time (see
 * {@code HyperscanCompiler}); a correct scan-time result requires checking
 * BOTH — the term matches iff {@code hsPattern} matches AND
 * {@code exclusionPattern} does NOT match the same message. See the
 * project README's "AND NOT: the two-pattern contract" section for exactly
 * how a caller applies this.
 *
 * <p><b>Word encoding is a single pass (the fix for the wildcard bug)</b>
 * <p>The previous implementation had three separately-ordered word-encoding
 * methods; a word containing BOTH a wildcard AND a non-ASCII character (e.g.
 * German {@code verschwör*}) hit the wrong branch first and lost its
 * wildcard expansion. {@link #encodeWord} replaces all three with one
 * codepoint-by-codepoint scan that handles wildcard, literal {@code ?},
 * emoji, non-ASCII, and PCRE metacharacter escaping uniformly, in one place.
 *
 * <p><b>{@code ?} is always literal; quoted phrases escape {@code *} and {@code ?} too</b>
 * <p>A bare {@code ?} (e.g. {@code he?d}) is escaped to {@code \?} — literal
 * text, never the PCRE "optional" quantifier. {@link #encodeQuotedPhrase} is
 * deliberately stricter than {@link #encodeWord}: quotes mean "match this
 * exactly", so {@code *} inside a quoted phrase is a literal asterisk, not
 * a wildcard.
 */
final class PatternCodeGenerator {

    /**
     * PCRE metacharacters that always need escaping (excludes {@code *} and {@code ?}, handled specially).
     */
    private static final String PCRE_META = "\\.^$|+()[]{}<>";

    /**
     * Unbounded inter-operand gap for AND — "any character, any number of
     * times, no limit". Built from the {@code [\s\S]} character class
     * (matches any code point, whitespace or not) rather than {@code .*},
     * so it works correctly WITHOUT the DOTALL flag — {@code .} without
     * DOTALL does not match newlines, but {@code [\s\S]} always does,
     * regardless of flags. See {@link ParseContext} class Javadoc.
     */
    private static final String UNBOUNDED_GAP = "[\\s\\S]*";

    private PatternCodeGenerator() {
    }

    /**
     * Generates the Hyperscan pattern for {@code ast}, mutating {@code ctx}
     * with discovered flags and, for AND NOT terms, the exclusion pattern.
     */
    static String generate(Ast ast, ParseContext ctx) {
        return switch (ast) {
            case Ast.Or or -> generateOr(or, ctx);
            case Ast.And and -> generateAnd(and, ctx);
            case Ast.AndNot andNot -> generateAndNot(andNot, ctx);
            case Ast.Near near -> generateNear(near, ctx);
            case Ast.FollowedBy fb -> generateFollowedBy(fb, ctx);
            case Ast.Word w -> encodeWord(w.text(), ctx);
            case Ast.Phrase p -> generatePhrase(p, ctx);
            case Ast.QuotedPhrase q -> encodeQuotedPhrase(q.text(), ctx);
        };
    }

    // ── Operator code generation ─────────────────────────────────────────────

    private static String generateOr(Ast.Or or, ParseContext ctx) {
        List<String> parts = new ArrayList<>(or.operands().size());
        for (Ast operand : or.operands()) {
            parts.add(generate(operand, ctx));
        }
        return "(?:" + String.join("|", parts) + ")";
    }

    /**
     * AND → every ordering of the operands, joined by an unbounded gap,
     * alternated together — see class Javadoc. This is now a fully correct,
     * self-contained Hyperscan pattern: {@code "price AND rigging"} compiles
     * to {@code (?:price[\s\S]*rigging|rigging[\s\S]*price)}, which matches
     * "There's price change and market rigging is going on" (rigging follows
     * price, with other words between) but does NOT match "There's price
     * change" alone (rigging never appears) — exactly the required behaviour,
     * with no post-filter or downstream cooperation needed.
     *
     * <p>The operand-count ceiling is enforced by {@link ExpressionParser}
     * at parse time (see {@link ParseContext#MAX_AND_OPERANDS}), not here —
     * by the time code generation runs, the AST is already known-valid.
     */
    private static String generateAnd(Ast.And and, ParseContext ctx) {
        List<String> operandPatterns = new ArrayList<>(and.operands().size());
        for (Ast operand : and.operands()) {
            operandPatterns.add(generate(operand, ctx));
        }
        return buildUnboundedCoOccurrencePattern(operandPatterns);
    }

    /**
     * AND NOT → returns the REQUIRED side's pattern as {@code hsPattern},
     * and records the EXCLUDED side's pattern (every excluded operand
     * combined with OR into one pattern) via
     * {@link ParseContext#setExclusionPattern} — see class Javadoc for why
     * these cannot be the same expression, and {@link Ast.AndNot} for the
     * full two-pattern contract.
     */
    private static String generateAndNot(Ast.AndNot andNot, ParseContext ctx) {
        String requiredPattern = generate(andNot.required(), ctx);

        List<String> excludedPatterns = new ArrayList<>(andNot.excluded().size());
        for (Ast excluded : andNot.excluded()) {
            excludedPatterns.add(generate(excluded, ctx));
        }
        String combinedExclusionPattern = "(?:" + String.join("|", excludedPatterns) + ")";
        ctx.setExclusionPattern(combinedExclusionPattern);

        return requiredPattern;
    }

    private static String generateNear(Ast.Near near, ParseContext ctx) {
        String leftPat = generate(near.left(), ctx);
        String rightPat = generate(near.right(), ctx);
        MultiLanguagePatternBuilder.BuildResult r =
                MultiLanguagePatternBuilder.buildNear(leftPat, rightPat, near.distance());
        propagateProximityFlags(r, ctx);
        return r.pattern();
    }

    private static String generateFollowedBy(Ast.FollowedBy fb, ParseContext ctx) {
        String leftPat = generate(fb.left(), ctx);
        String rightPat = generate(fb.right(), ctx);
        MultiLanguagePatternBuilder.BuildResult r =
                MultiLanguagePatternBuilder.buildFollowedBy(leftPat, rightPat, fb.distance());
        propagateProximityFlags(r, ctx);
        return r.pattern();
    }

    private static void propagateProximityFlags(MultiLanguagePatternBuilder.BuildResult r, ParseContext ctx) {
        if ((r.recommendedHsFlags() & ParseContext.HS_FLAG_UTF8) != 0) {
            ctx.setNeedsUtf8();
        }
    }

    // ── AND: unbounded co-occurrence pattern construction ───────────────────────

    /**
     * Builds a single Hyperscan-valid pattern expressing "every one of
     * {@code operandPatterns} appears somewhere in the text, in ANY order,
     * with NO distance limit" — see class Javadoc.
     *
     * @param operandPatterns already-generated PCRE fragments for each AND operand
     * @return {@code (?:seq1|seq2|...)} where each {@code seqN} is one
     * permutation of the operands joined by {@link #UNBOUNDED_GAP}
     */
    private static String buildUnboundedCoOccurrencePattern(List<String> operandPatterns) {
        List<List<String>> permutations = permutationsOf(operandPatterns);
        List<String> orderedSequences = new ArrayList<>(permutations.size());
        for (List<String> permutation : permutations) {
            orderedSequences.add(String.join(UNBOUNDED_GAP, permutation));
        }
        return "(?:" + String.join("|", orderedSequences) + ")";
    }

    /**
     * Standard backtracking permutation generator — N! permutations for N items.
     */
    private static List<List<String>> permutationsOf(List<String> items) {
        List<List<String>> result = new ArrayList<>();
        permute(new ArrayList<>(items), 0, result);
        return result;
    }

    private static void permute(List<String> items, int fixedPrefixLength, List<List<String>> result) {
        if (fixedPrefixLength == items.size() - 1) {
            result.add(new ArrayList<>(items));
            return;
        }
        for (int swapIndex = fixedPrefixLength; swapIndex < items.size(); swapIndex++) {
            java.util.Collections.swap(items, fixedPrefixLength, swapIndex);
            permute(items, fixedPrefixLength + 1, result);
            java.util.Collections.swap(items, fixedPrefixLength, swapIndex); // backtrack
        }
    }

    // ── Leaf code generation ──────────────────────────────────────────────────

    /**
     * Multiple bare words that appeared together inside one set of parens
     * with no operator between them, e.g. {@code (bomb this place)}. Each
     * word is independently wildcard/emoji/literal-? aware; words are joined
     * with a single literal space, matching the existing quoted-phrase
     * whitespace convention (exact single-space match, not a flexible
     * {@code \s+} gap — this is deliberately a phrase, not a proximity operator).
     */
    private static String generatePhrase(Ast.Phrase phrase, ParseContext ctx) {
        List<String> encoded = new ArrayList<>(phrase.words().size());
        for (String word : phrase.words()) {
            encoded.add(encodeWord(word, ctx));
        }
        return String.join(" ", encoded);
    }

    /**
     * Encodes one bare (unquoted) word into its PCRE fragment. Single
     * codepoint-by-codepoint pass — see class Javadoc for why this replaces
     * three separately-ordered methods from the previous implementation.
     */
    static String encodeWord(String word, ParseContext ctx) {
        StringBuilder patternBuilder = new StringBuilder(word.length() * 2);
        int charIndex = 0;
        while (charIndex < word.length()) {
            int codePoint = word.codePointAt(charIndex);
            int charCountForCodePoint = Character.charCount(codePoint);

            if (codePoint == '*') {
                patternBuilder.append("\\S*");
            } else if (codePoint == '?') {
                // Always literal — never a live PCRE quantifier (requirement:
                // '?' between words in a Natural Language term is literal text).
                patternBuilder.append("\\?");
            } else if (isEmojiCodePoint(codePoint)) {
                patternBuilder.append(String.format("\\x{%X}", codePoint));
                ctx.setNeedsUtf8();
            } else if (codePoint > 0x7F) {
                patternBuilder.appendCodePoint(codePoint); // literal non-ASCII; UTF8 flag handles matching
                ctx.setNeedsUtf8();
            } else {
                char asciiChar = (char) codePoint;
                if (PCRE_META.indexOf(asciiChar) >= 0) {
                    patternBuilder.append('\\');
                }
                patternBuilder.append(asciiChar);
            }
            charIndex += charCountForCodePoint;
        }
        return patternBuilder.toString();
    }

    /**
     * Encodes a double-quoted phrase's inner text. Unlike {@link #encodeWord},
     * {@code *} and {@code ?} are escaped as ordinary literal characters here
     * (never wildcard-expanded or left as live quantifiers) — quotes mean
     * "match this exactly".
     */
    static String encodeQuotedPhrase(String text, ParseContext ctx) {
        StringBuilder patternBuilder = new StringBuilder(text.length() * 2);
        int charIndex = 0;
        while (charIndex < text.length()) {
            int codePoint = text.codePointAt(charIndex);
            int charCountForCodePoint = Character.charCount(codePoint);

            if (isEmojiCodePoint(codePoint)) {
                patternBuilder.append(String.format("\\x{%X}", codePoint));
                ctx.setNeedsUtf8();
            } else if (codePoint > 0x7F) {
                patternBuilder.appendCodePoint(codePoint);
                ctx.setNeedsUtf8();
            } else {
                char asciiChar = (char) codePoint;
                if (PCRE_META.indexOf(asciiChar) >= 0 || asciiChar == '*' || asciiChar == '?') {
                    patternBuilder.append('\\');
                }
                patternBuilder.append(asciiChar);
            }
            charIndex += charCountForCodePoint;
        }
        return patternBuilder.toString();
    }

    /**
     * Returns {@code true} if the Unicode codepoint belongs to an emoji block.
     * Same coverage as the previous implementation (emoticons, symbols,
     * transport, flags, supplemental blocks).
     */
    static boolean isEmojiCodePoint(int codePoint) {
        return (codePoint >= 0x1F600 && codePoint <= 0x1F64F)
                || (codePoint >= 0x1F300 && codePoint <= 0x1F5FF)
                || (codePoint >= 0x1F680 && codePoint <= 0x1F6FF)
                || (codePoint >= 0x1F700 && codePoint <= 0x1F77F)
                || (codePoint >= 0x1F780 && codePoint <= 0x1F7FF)
                || (codePoint >= 0x1F800 && codePoint <= 0x1F8FF)
                || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)
                || (codePoint >= 0x1FA00 && codePoint <= 0x1FA6F)
                || (codePoint >= 0x1FA70 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x26FF)
                || (codePoint >= 0x2700 && codePoint <= 0x27BF)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F1E0 && codePoint <= 0x1F1FF)
                || (codePoint >= 0x1F100 && codePoint <= 0x1F1FF);
    }
}
