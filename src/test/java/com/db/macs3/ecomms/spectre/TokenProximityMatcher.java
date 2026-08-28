package com.db.macs3.ecomms.spectre;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Original minimal prototype: two hardcoded patterns, FOLLOWEDBY only, no
 * NEAR, no AND NOT, no parsing of a {@code resolvedPatterns} string. Kept
 * as-is for its illustrative value. See {@link ResolvedPatternMatcher} for
 * the fuller reference implementation this grew into — parametrized leaf
 * patterns, NEAR (bidirectional) + FOLLOWEDBY (directional) + AND NOT, and a
 * small parser that consumes an actual
 * {@code TermCompilationResult#resolvedPatterns()} string end-to-end.
 */
public class TokenProximityMatcher {

    private static final Pattern PATTERN_A =
            Pattern.compile("\\bbash\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PATTERN_B =
            Pattern.compile("\\b(?:fuck|fck)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Evaluates: A FOLLOWEDBY(30) B (inclusive up to 30 words)
     */
    public static boolean matchesFollowedBy(String text, int maxWordsBetween) {

        // 1. Tokenise the text into an array of words
        String[] words = text.split("\\s+");

        List<Integer> indicesA = getMatchingWordIndices(words, PATTERN_A);
        List<Integer> indicesB = getMatchingWordIndices(words, PATTERN_B);

        // Quick exit if either word is completely missing
        if (indicesA.isEmpty() || indicesB.isEmpty()) {
            return false;
        }

        // 2. Evaluate the ordered proximity (B must follow A, distance <= maxWordsBetween)
        for (int indexA : indicesA) {
            for (int indexB : indicesB) {

                if (indexB > indexA) { // Enforces "FOLLOWEDBY" (unidirectional)

                    int wordsBetween = indexB - indexA - 1;

                    if (wordsBetween <= maxWordsBetween) {
                        return true; // Match found within inclusive range!
                    }
                }
            }
        }

        return false;
    }

    private static List<Integer> getMatchingWordIndices(
            String[] words, Pattern pattern) {

        List<Integer> indices = new ArrayList<>();

        for (int i = 0; i < words.length; i++) {

            String cleanWord = words[i].replaceAll("[^a-zA-Z0-9]", "");

            if (pattern.matcher(cleanWord).matches()) {
                indices.add(i);
            }
        }

        return indices;
    }
}
