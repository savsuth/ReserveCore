package io.github.savsuth.reservecore.demo;

import java.util.Objects;

/** Prints one PASS/FAIL line per check and remembers whether anything failed. */
final class Checks {

    private int passed;
    private int failed;

    void expect(String what, Object expected, Object actual) {
        check(what, Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }

    void check(String what, boolean ok, String detailIfFailed) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + ": " + detailIfFailed);
        }
    }

    boolean allPassed() {
        return failed == 0;
    }

    String totals() {
        return passed + " passed, " + failed + " failed";
    }
}
