/**
 * The pattern switch of Java 21, on an ahead-of-time image.
 *
 * <h2>What this is for</h2>
 *
 * <p>{@code java.lang.runtime.SwitchBootstraps} is not in this runtime, so until 03.10.2026 a class
 * using a pattern switch compiled to a planted {@code NoSuchMethodError} and a warning — a build
 * that succeeded and a device that did not. {@code SwitchBootstrapsDelegate} resolves the bootstrap
 * at build time instead, into a chain of {@code instanceof}.
 *
 * <p>Which means the thing to check is not that it compiles, but that it computes the same answers.
 * The bootstrap has three rules that are easy to get subtly wrong and each has a case below:
 * <b>null answers -1</b> and not "no match"; <b>no match answers the label count</b> and not -1; and
 * a <b>guard that fails restarts at the next index</b> rather than falling to the default.
 *
 * <p>Compiled at source 8 like everything else here, except that a pattern switch needs 21 — see
 * native-check.sh, which compiles this one file differently for exactly that reason.
 */
public class SwitchCheck {

    static int checked;
    static int failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    sealed interface Beleg permits Rechnung, Gutschrift { }
    record Rechnung(double betrag) implements Beleg { }
    record Gutschrift(double betrag) implements Beleg { }

    /** Types, a sealed hierarchy, null and a default — the ordinary shape. */
    static String typen(Object o) {
        return switch (o) {
            case Integer i -> "int:" + i;
            case String s -> "str:" + s;
            case Beleg b -> "beleg";
            case null -> "null";
            default -> "anderes";
        };
    }

    /** Guards, which is where the restart index earns its keep. */
    static String wachen(Object o) {
        return switch (o) {
            case Integer i when i > 100 -> "gross:" + i;
            case Integer i when i > 10 -> "mittel:" + i;
            case Integer i -> "klein:" + i;
            case String s when s.isEmpty() -> "leer";
            case String s -> "text:" + s;
            default -> "anderes";
        };
    }

    /** Exhaustive over a sealed interface: no default, so "no match" must be unreachable. */
    static double betrag(Beleg b) {
        return switch (b) {
            case Rechnung r -> r.betrag();
            case Gutschrift g -> -g.betrag();
        };
    }

    /** An array label, which is the one case whose descriptor is not a plain class name. */
    static String felder(Object o) {
        return switch (o) {
            case int[] a -> "int[" + a.length + "]";
            case String[] a -> "String[" + a.length + "]";
            case Object[] a -> "Object[" + a.length + "]";
            default -> "kein Feld";
        };
    }

    public static void main(String[] args) {
        eq("an Integer", typen(42), "int:42");
        eq("a String", typen("x"), "str:x");
        eq("a sealed subtype", typen(new Rechnung(1)), "beleg");
        eq("the other subtype", typen(new Gutschrift(1)), "beleg");
        // null is -1 from the bootstrap and its own arm here. A chain that answered "no match"
        // would land in the default and say "anderes".
        eq("null", typen(null), "null");
        eq("none of them", typen(3.5), "anderes");

        // Each guard failing has to restart at the *next* index, not at the default.
        eq("guard: big", wachen(200), "gross:200");
        eq("guard: middle", wachen(50), "mittel:50");
        eq("guard: small", wachen(5), "klein:5");
        eq("guard: empty string", wachen(""), "leer");
        eq("guard: other string", wachen("x"), "text:x");
        eq("guard: neither", wachen(3.5), "anderes");

        eq("exhaustive, first arm", betrag(new Rechnung(10)), 10.0);
        eq("exhaustive, second arm", betrag(new Gutschrift(4)), -4.0);

        eq("int array", felder(new int[3]), "int[3]");
        eq("String array", felder(new String[2]), "String[2]");
        eq("Object array", felder(new Object[1]), "Object[1]");
        eq("not an array", felder("x"), "kein Feld");

        System.out.println(checked + " checked, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
