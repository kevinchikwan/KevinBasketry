package com.basketry.promotion.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The code rule against docs/coupon-code-vectors.txt, the expected answers
 * order-service's copy of the rule is tested against too (ROADMAP A9). The
 * rule is copied, the answers are not: changing one copy fails its own test,
 * and changing the file fails the other service's.
 *
 * <p>The file is found from this module's directory, not the working
 * directory, so the test reads the same file from Gradle and from an IDE. Each
 * vector runs with Turkish as the default locale, where {@code "i"} upper-cases
 * to {@code "İ"}, so a missing Locale.ROOT shows.
 *
 * <p>Mutations: the file emptied (comments only); the file renamed; in
 * CouponCode, {@code strip()} replaced by {@code trim()} (the em-space and
 * U+0001 vectors fail).
 */
class CouponCodeVectorTests {

    static final String REFUSED = "REFUSED";

    // "input"  EXPECTED  [# comment]
    private static final Pattern VECTOR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s+(\\S+)(?:\\s+#.*)?");

    record Vector(int line, String input, String expected) {
        @Override
        public String toString() {
            return "line " + line + ": " + expected;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void theRuleGivesTheExpectedAnswer(Vector vector) {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            if (vector.expected().equals(REFUSED)) {
                assertThatThrownBy(() -> CouponCode.normalise(vector.input()))
                        .isInstanceOf(InvalidCouponException.class);
            } else {
                assertThat(CouponCode.normalise(vector.input()).value()).isEqualTo(vector.expected());
            }
        } finally {
            Locale.setDefault(before);
        }
    }

    /** Fails, rather than running nothing, on a missing file, an empty one or a line it cannot read. */
    static List<Vector> vectors() throws Exception {
        Path file = vectorFile();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Vector> vectors = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = VECTOR.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException(file + " line " + (index + 1) + " is not \"input\" EXPECTED");
            }
            vectors.add(new Vector(index + 1, unescape(matcher.group(1)), matcher.group(2)));
        }
        if (vectors.stream().noneMatch(v -> v.expected().equals(REFUSED))
                || vectors.stream().allMatch(v -> v.expected().equals(REFUSED))) {
            throw new IllegalStateException(file + " needs both accepted and refused vectors; it has "
                    + vectors.size() + " vectors");
        }
        return vectors;
    }

    /** services/promotion-service/../../docs, from where this class was compiled to. */
    static Path vectorFile() throws URISyntaxException {
        Path classes = Path.of(CouponCodeVectorTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path dir = classes; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("promotion-service")
                    && Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                return dir.resolve("../../docs/coupon-code-vectors.txt").normalize();
            }
        }
        throw new IllegalStateException("No promotion-service module above " + classes);
    }

    /** {@code \}uXXXX is that UTF-16 unit, {@code \\} a backslash, {@code \"} a quote. */
    static String unescape(String quoted) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char next = quoted.charAt(++i);
            switch (next) {
                case '\\', '"' -> out.append(next);
                case 'u' -> {
                    out.append((char) Integer.parseInt(quoted.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalStateException("Unknown escape \\" + next);
            }
        }
        return out.toString();
    }
}
