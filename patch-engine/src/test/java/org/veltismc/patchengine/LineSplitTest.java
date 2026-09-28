package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks {@link UnifiedDiffPatcher#splitLines} to {@code String.split("\\R", -1)}.
 *
 * <p>The hand-written splitter replaced a regex that profiling flagged as the
 * hottest frame of the application phase. It must stay byte-equivalent, because
 * the line list is what hunks are matched against and what gets written back.
 */
class LineSplitTest {

    private static final String ALL_BREAKS =
        "\n\r\n\r\u000b\f\u0085\u2028\u2029";

    @Test
    void matchesRegexSplitOnEveryLineBreakCharacter() {
        for (int i = 0; i < ALL_BREAKS.length(); i++) {
            var breakChar = ALL_BREAKS.charAt(i);
            assertSame("single break", String.valueOf(breakChar));
            assertSame("around break", "a" + breakChar + "b");
            assertSame("repeated break", "a" + breakChar + breakChar + "b");
            assertSame("leading break", breakChar + "tail");
            assertSame("trailing break", "head" + breakChar);
            assertSame("only break", String.valueOf(breakChar));
        }
        // CRLF must collapse into one break, not two.
        assertSame("crlf", "a\r\nb");
        assertSame("crlf repeated", "a\r\n\r\nb");
        assertSame("cr before lf pair", "a\r\rb");
    }

    @Test
    void matchesRegexSplitOnDegenerateInputs() {
        assertSame("empty", "");
        assertSame("no break", "abc");
        assertSame("mixed", "a\r\nb\nc\rd\re");
        assertSame("unicode text", "class Foo {\n    String s = \"\u2028\";\n}\n");
    }

    @Test
    void matchesRegexSplitOnRandomInputs() {
        var rng = new Random(20260928L);   // fixed seed: reproducible failure
        var alphabet = "ab \t\r\n\u000b\f\u0085\u2028\u2029";
        for (int i = 0; i < 5_000; i++) {
            var len = rng.nextInt(24);
            var sb = new StringBuilder(len);
            for (int c = 0; c < len; c++) {
                sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
            }
            assertSame("random #" + i, sb.toString());
        }
    }

    @Test
    void roundTripsThroughJoinWithDetectedSeparator() {
        var content = "one\r\ntwo\r\nthree\r\n";
        var lines = UnifiedDiffPatcher.splitLines(content);
        assertEquals(List.of("one", "two", "three", ""), lines);
        assertEquals(content, String.join("\r\n", lines));
    }

    private static void assertSame(String label, String content) {
        var expected = List.of(content.split("\\R", -1));
        var actual = UnifiedDiffPatcher.splitLines(content);
        assertArrayEquals(expected.toArray(), actual.toArray(),
            () -> label + " -> " + content.replace("\r", "\\r").replace("\n", "\\n"));
    }
}
