package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks an {@link Ast} and generates the Hyperscan PCRE text, recording flag needs and warnings in
 * a {@link ParseContext}. Hyperscan supports no lookaround or backreferences, so every construct
 * below is plain alternation and bounded/unbounded repetition.
 *
 * <p><b>Nodes</b>
 * <ul>
 *   <li><b>OR</b> → {@code (?:a|b|c)}.</li>
 *   <li><b>AND</b> → every ordering of the operands joined by {@code [\s\S]*}, alternated:
 *       {@code (?:A[\s\S]*B|B[\s\S]*A)}. Operand count is capped ({@link ParseContext#MAX_AND_OPERANDS}).</li>
 *   <li><b>AND NOT</b> → the required side's pattern is returned; the excluded side (its operands
 *       OR'd) is recorded through {@link ParseContext#setexclusionRegex}. The term matches iff the
 *       required pattern is found AND the exclusion pattern is not — see {@link Ast.AndNot}.</li>
 *   <li><b>NEAR / FOLLOWEDBY</b> → a gap-embedded pattern from {@link MultiLanguagePatternBuilder}.</li>
 *   <li><b>Word / Phrase / QuotedPhrase</b> → an escaped literal, described next.</li>
 * </ul>
 *
 * <p><b>Literals.</b> A bare word is scanned code point by code point ({@link #encodeWord}):
 * <ul>
 *   <li>{@code *} → {@code \S*} (zero or more non-whitespace characters);</li>
 *   <li>{@code ?} → {@code \S} (exactly one non-whitespace character), so {@code I?ll} matches
 *       "I'll" and {@code he?d} matches "held";</li>
 *   <li>an emoji code point → {@code \x{HEX}}; any other non-ASCII character is kept literally.
 *       Both set {@link ParseContext#setNeedsUtf8};</li>
 *   <li>PCRE metacharacters ({@code \ . ^ $ | + ( ) [ ] { } < >}) are escaped.</li>
 * </ul>
 * A bare multi-word phrase joins its words with one literal space (not a flexible gap). A
 * double-quoted phrase ({@link #encodeQuotedPhrase}) means "match exactly", so {@code *} and
 * {@code ?} in it are escaped literal characters, never wildcards.
 *
 * <p><b>Whole-word matching.</b> Each literal is wrapped in {@code \b} at an edge whose first/last
 * source character is an ASCII word character ({@code [A-Za-z0-9_]}), so {@code pd} matches the word
 * "pd" but not the "pd" inside "updates" ({@link #withWordBoundaries}):
 * <ul>
 *   <li>a wildcard edge or non-word edge ({@code $100}, {@code u.s.}) gets no {@code \b}, so
 *       {@code pd*} matches "pdf" and {@code *pd*} matches "updates" — the wildcard is the author's
 *       explicit substring opt-in;</li>
 *   <li>a term containing ANY non-ASCII text gets no boundaries at all
 *       ({@link #containsNonAscii}) because Hyperscan rejects {@code \b} in UCP mode, which such a
 *       term needs;</li>
 *   <li>the trailing {@code \b} is omitted when {@link ParseContext#isTrailingBoundaries()} is
 *       false — see {@link ParseContext}.</li>
 * </ul>
 */
final class PatternCodeGenerator {

    /**
     * PCRE metacharacters that always need escaping ({@code *} and {@code ?} are handled separately).
     */
    private static final String PCRE_META = "\\.^$|+()[]{}<>";

    /**
     * The unbounded gap AND joins its operands with: any character, any number of times. Built from
     * {@code [\s\S]} rather than {@code .*} so it matches newlines whatever the DOTALL flag says.
     */
    private static final String UNBOUNDED_GAP = "[\\s\\S]*";

    private PatternCodeGenerator() {
    }

    /**
     * Generates the Hyperscan pattern for {@code ast}, mutating {@code ctx} with discovered flag needs
     * and, for an AND NOT node, the exclusion pattern.
     */
    static String generate(Ast ast, ParseContext ctx) {
        return switch (ast) {
            case Ast.Or or -> generateOr(or, ctx);
            case Ast.And and -> generateAnd(and, ctx);
            case Ast.AndNot andNot -> generateAndNot(andNot, ctx);
            case Ast.Near near -> generateNear(near, ctx);
            case Ast.FollowedBy fb -> generateFollowedBy(fb, ctx);
            case Ast.Not ignored -> throw new IllegalStateException(
                    "unreachable — every Ast.Not is folded into Ast.AndNot (or rejected) by "
                    + "ExpressionParser.parseAnd() before an Ast is ever returned; see Ast.Not Javadoc");
            case Ast.Word w -> withWordBoundaries(encodeWord(w.text(), ctx), w.text(), ctx);
            case Ast.Phrase p -> withWordBoundaries(generatePhrase(p, ctx), String.join(" ", p.words()), ctx);
            case Ast.QuotedPhrase q -> withWordBoundaries(encodeQuotedPhrase(q.text(), ctx), q.text(), ctx);
        };
    }

    // ── Whole-word matching ──────────────────────────────────────────────────

    /**
     * Wraps a literal in {@code \b} on each edge whose first/last SOURCE character is an ASCII word
     * character. The decision is made on the raw, pre-encoding text:
     * <ul>
     *   <li>an edge that is a wildcard ({@code *}/{@code ?} in a bare word) or any non-word character
     *       ({@code $100}, {@code u.s.}, {@code #tag}) gets no boundary — {@code \b} between two non-word
     *       characters would demand a word character that is not there;</li>
     *   <li>nothing is added when {@link ParseContext#isWordBoundaries()} is false (non-ASCII term);</li>
     *   <li>the END edge is skipped when {@link ParseContext#isTrailingBoundaries()} is false.</li>
     * </ul>
     */
    private static String withWordBoundaries(String encoded, String rawText, ParseContext ctx) {
        if (!ctx.isWordBoundaries() || rawText.isEmpty()) {
            return encoded;
        }
        String prefix = isAsciiWordChar(rawText.charAt(0)) ? "\\b" : "";
        String suffix = ctx.isTrailingBoundaries() && isAsciiWordChar(rawText.charAt(rawText.length() - 1))
                ? "\\b" : "";
        return prefix + encoded + suffix;
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * True when any literal in {@code ast} contains a non-ASCII character — the condition under which
     * {@link ParseContext#computeFlags()} adds UCP, and so under which {@code \b} cannot be used. It is
     * computed from the AST before generation so the decision is uniform across every leaf of the term.
     */
    static boolean containsNonAscii(Ast ast) {
        return leafTexts(ast).stream().anyMatch(text -> text.chars().anyMatch(c -> c > 0x7F));
    }

    /**
     * True when at least one literal in {@code ast} would receive a {@code \b} edge. Used to warn only
     * when skipping boundaries actually changes the term's behavior (a pure-CJK term would never have
     * had any).
     */
    static boolean hasBoundaryCandidate(Ast ast) {
        return leafTexts(ast).stream().anyMatch(text -> !text.isEmpty()
                && (isAsciiWordChar(text.charAt(0)) || isAsciiWordChar(text.charAt(text.length() - 1))));
    }

    /**
     * The raw source text of every literal (word, phrase, quoted phrase) in {@code ast}.
     */
    private static List<String> leafTexts(Ast ast) {
        List<String> texts = new ArrayList<>();
        collectLeafTexts(ast, texts);
        return texts;
    }

    private static void collectLeafTexts(Ast ast, List<String> out) {
        switch (ast) {
            case Ast.Or or -> or.operands().forEach(child -> collectLeafTexts(child, out));
            case Ast.And and -> and.operands().forEach(child -> collectLeafTexts(child, out));
            case Ast.AndNot andNot -> {
                collectLeafTexts(andNot.required(), out);
                andNot.excluded().forEach(child -> collectLeafTexts(child, out));
            }
            case Ast.Near near -> {
                collectLeafTexts(near.left(), out);
                collectLeafTexts(near.right(), out);
            }
            case Ast.FollowedBy fb -> {
                collectLeafTexts(fb.left(), out);
                collectLeafTexts(fb.right(), out);
            }
            case Ast.Not not -> collectLeafTexts(not.operand(), out);
            case Ast.Word w -> out.add(w.text());
            case Ast.Phrase p -> out.add(String.join(" ", p.words()));
            case Ast.QuotedPhrase q -> out.add(q.text());
        }
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
     * AND → every ordering of the operands joined by an unbounded gap, alternated together, so
     * {@code "price AND rigging"} becomes {@code (?:price[\s\S]*rigging|rigging[\s\S]*price)}. It
     * matches "price change and market rigging is going on" but not "price change" alone. The operand
     * ceiling is enforced by {@link ExpressionParser}, not here.
     */
    private static String generateAnd(Ast.And and, ParseContext ctx) {
        List<String> operandPatterns = new ArrayList<>(and.operands().size());
        for (Ast operand : and.operands()) {
            operandPatterns.add(generate(operand, ctx));
        }
        return buildUnboundedCoOccurrencePattern(operandPatterns);
    }

    /**
     * AND NOT → returns the required side's pattern and records the excluded side's pattern (every
     * excluded operand OR'd together) in {@link ParseContext#setexclusionRegex}; see {@link Ast.AndNot}
     * for why these cannot be one expression.
     */
    private static String generateAndNot(Ast.AndNot andNot, ParseContext ctx) {
        String requiredPattern = generate(andNot.required(), ctx);

        List<String> excludedPatterns = new ArrayList<>(andNot.excluded().size());
        for (Ast excluded : andNot.excluded()) {
            excludedPatterns.add(generate(excluded, ctx));
        }
        String combinedexclusionRegex = "(?:" + String.join("|", excludedPatterns) + ")";
        ctx.setexclusionRegex(combinedexclusionRegex);

        return requiredPattern;
    }

    /**
     * Builds a gap-embedded NEAR pattern via {@link MultiLanguagePatternBuilder}. Reached from
     * {@code TermSyntaxTranslator#resolveSide}'s single-pattern attempt and from a NEAR nested inside
     * a multi-operand {@code OR}; {@link PatternDecomposer} handles every other NEAR/FOLLOWEDBY node
     * when a side falls back to leaves.
     */
    private static String generateNear(Ast.Near near, ParseContext ctx) {
        String leftPat = generate(near.left(), ctx);
        String rightPat = generate(near.right(), ctx);
        MultiLanguagePatternBuilder.BuildResult nearBuildResult =
                MultiLanguagePatternBuilder.buildNear(leftPat, rightPat, near.distance());
        propagateProximityResult(nearBuildResult, ctx);
        return nearBuildResult.pattern();
    }

    /**
     * Builds a gap-embedded FOLLOWEDBY pattern; reachable from the same two places as {@link #generateNear}.
     */
    private static String generateFollowedBy(Ast.FollowedBy fb, ParseContext ctx) {
        String leftPat = generate(fb.left(), ctx);
        String rightPat = generate(fb.right(), ctx);
        MultiLanguagePatternBuilder.BuildResult followedByBuildResult =
                MultiLanguagePatternBuilder.buildFollowedBy(leftPat, rightPat, fb.distance());
        propagateProximityResult(followedByBuildResult, ctx);
        return followedByBuildResult.pattern();
    }

    /**
     * Propagates a {@link MultiLanguagePatternBuilder.BuildResult}'s UTF8 need and warning (mixed
     * RTL/LTR FOLLOWEDBY, or a clamped/narrowed gap) into {@code ctx}; the translator reads
     * {@link ParseContext#getWarnings()} back once the term is done.
     */
    private static void propagateProximityResult(MultiLanguagePatternBuilder.BuildResult buildResult, ParseContext ctx) {
        if ((buildResult.recommendedHsFlags() & ParseContext.HS_FLAG_UTF8) != 0) {
            ctx.setNeedsUtf8();
        }
        ctx.addWarning(buildResult.warning());
    }

    // ── AND: unbounded co-occurrence pattern construction ───────────────────────

    /**
     * Builds one pattern for "every operand appears somewhere, in any order, at any distance":
     * {@code (?:seq1|seq2|...)} where each sequence is one permutation of {@code operandPatterns}
     * joined by {@link #UNBOUNDED_GAP}.
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
     * A bare multi-word phrase such as {@code (bomb this place)}: each word is encoded by
     * {@link #encodeWord} (so wildcards and emoji work) and the words are joined by ONE literal space.
     * This is a phrase, deliberately not a flexible {@code \s+} gap.
     */
    private static String generatePhrase(Ast.Phrase phrase, ParseContext ctx) {
        List<String> encoded = new ArrayList<>(phrase.words().size());
        for (String word : phrase.words()) {
            encoded.add(encodeWord(word, ctx));
        }
        return String.join(" ", encoded);
    }

    /**
     * Encodes one bare word in a single code-point pass — wildcards, emoji, non-ASCII text and
     * metacharacter escaping are all handled here, so a word mixing a wildcard with a non-ASCII
     * character (German {@code verschwör*}) expands correctly. See the class Javadoc for the rules.
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
                // Single-character wildcard — "exactly one non-whitespace character" —
                // never a literal question mark and never a live PCRE quantifier. See
                // class Javadoc for why this replaces the earlier always-literal escaping.
                patternBuilder.append("\\S");
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
     * Encodes a double-quoted phrase's inner text. Unlike {@link #encodeWord}, {@code *} and
     * {@code ?} are escaped as literal characters: quotes mean "match this exactly".
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
     * True for a code point in an emoji block (emoticons, symbols, transport, flags, supplemental
     * blocks, dingbats). Such code points are emitted as {@code \x{HEX}}.
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
