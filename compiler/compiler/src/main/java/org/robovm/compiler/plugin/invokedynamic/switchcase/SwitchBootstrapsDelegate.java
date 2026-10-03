package org.robovm.compiler.plugin.invokedynamic.switchcase;

import org.robovm.compiler.ModuleBuilder;
import org.robovm.compiler.clazz.Clazz;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.plugin.invokedynamic.InvokeDynamicCompilerPlugin;
import soot.Body;
import soot.IntType;
import soot.Local;
import soot.RefType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.ClassConstant;
import soot.jimple.DefinitionStmt;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.NopStmt;
import soot.tagkit.LineNumberTag;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * The pattern switch of Java 21, resolved at build time.
 *
 * <h2>Why this had to exist</h2>
 *
 * <p>{@code java.lang.runtime.SwitchBootstraps} is not in this runtime and cannot easily be: it is
 * built on {@code MethodHandles} and spins classes at run time, which an ahead-of-time image has no
 * machinery for. Until 03.10.2026 an {@code invokedynamic} naming it therefore fell to
 * {@code UnrecognizedBootstrapDelegate}, which plants a {@code NoSuchMethodError} <em>and lets the
 * build succeed</em>. A library compiled with Java 21 and containing one pattern switch would
 * install, start, and die in the line nobody had looked at.
 *
 * <p>That is the shape of defect this project keeps finding — SQLite's connection class, ICU's
 * time-zone-name factory — and the only one of them that a customer's own dependency could carry.
 *
 * <h2>What the bootstrap means</h2>
 *
 * <p>{@code typeSwitch(Object target, int restartIndex)} answers an index:
 *
 * <ul>
 *   <li>{@code -1} when {@code target} is null;</li>
 *   <li>otherwise the smallest {@code i >= restartIndex} whose label matches;</li>
 *   <li>{@code labels.length} when none does.</li>
 * </ul>
 *
 * <p>The caller then does a {@code tableswitch} on that index. The restart index is how a guarded
 * case re-enters: {@code case Integer i when i > 100} that fails its guard stores {@code i + 1} and
 * jumps back, so the next label is tried.
 *
 * <h2>What the labels actually are, measured rather than read</h2>
 *
 * <p>javac 25, asked directly on a switch with sealed types, guards and a default: <b>every label is
 * a class constant</b>. A guarded case contributes its class <em>twice</em> — {@code Integer,
 * Integer, String, String} for two guarded and two unguarded cases — because each arm is its own
 * index. Nothing else appeared. A plain {@code switch} over an enum produces no
 * {@code invokedynamic} at all; it is still a {@code tableswitch} on the ordinal.
 *
 * <p>So this handles class labels, and refuses anything else <b>at build time with a message</b>.
 * That is the point of the whole change: the failure belongs in the build, where somebody is
 * reading, and not on a phone.
 *
 * <p><i>What a qualified enum constant does, since it is the obvious next question.</i> A
 * {@code case Art.KLEIN} in a pattern switch compiles to {@code typeSwitch} as well — with its
 * labels built by {@code ConstantBootstraps.invoke}, which is a {@code CONSTANT_Dynamic} entry.
 * Soot 2.5 cannot read those at all, so {@code RewritingClassProvider} refuses the class before
 * this is ever reached, with a message of its own. The {@code enumSwitch} branch below is therefore
 * belt and braces rather than a path anything has been seen to take.
 *
 * <h2>What it compiles to</h2>
 *
 * <pre>
 *     if target == null goto NULL
 *     tableswitch(restartIndex) { 0: L0, 1: L1, ..., default: NONE }
 * L0: if target instanceof C0 goto HIT0
 * L1: if target instanceof C1 goto HIT1
 *     ...
 * NONE:  result = n;   goto END
 * HIT0:  result = 0;   goto END
 * HIT1:  result = 1;   goto END
 *     ...
 * NULL:  result = -1
 * END:
 * </pre>
 *
 * <p>A chain of {@code instanceof} rather than a loop over an array of {@code Class} objects: the
 * types are known here, so the array, the loop and the reflective {@code isInstance} can all go. The
 * table switch at the top is what makes a restart jump straight to its label instead of re-testing
 * the ones before it.
 */
public class SwitchBootstrapsDelegate implements InvokeDynamicCompilerPlugin.Delegate {

    private static final String BOOTSTRAP_CLASS = "java.lang.runtime.SwitchBootstraps";
    private static final String TYPE_SWITCH = "typeSwitch";
    private static final String ENUM_SWITCH = "enumSwitch";

    private int tmpCounter;

    @Override
    public void beforeMethod(Config config, Clazz clazz, SootMethod method,
                             ModuleBuilder moduleBuilder) {
        tmpCounter = 0;
    }

    @Override
    public LinkedList<Unit> transformDynamicInvoke(Config config, Clazz clazz, SootClass sootClass,
                                                   SootMethod method, DefinitionStmt defStmt,
                                                   DynamicInvokeExpr invokeExpr,
                                                   ModuleBuilder moduleBuilder) {
        String owner = invokeExpr.getBootstrapMethodRef().declaringClass().getName();
        if (!BOOTSTRAP_CLASS.equals(owner)) {
            return null;
        }
        String bootstrap = invokeExpr.getBootstrapMethodRef().name();
        if (ENUM_SWITCH.equals(bootstrap)) {
            // Refused rather than mis-compiled. Its labels are EnumDesc objects, which name their
            // constant by string and need the enum resolved — a different piece of work.
            //
            // Measured 03.10.2026: javac 25 does not emit this for a qualified enum constant
            // either. It uses typeSwitch with labels built by ConstantBootstraps, which is condy,
            // which RewritingClassProvider refuses before this is reached. So this branch has not
            // been seen to run and exists so that it cannot run silently.
            throw new org.robovm.compiler.CompilerException(
                    "SwitchBootstraps.enumSwitch is not supported, in " + method.getSignature()
                    + ". It comes from a pattern switch over qualified enum constants. Rewrite that"
                    + " switch, or compile that library for Java 17.");
        }
        if (!TYPE_SWITCH.equals(bootstrap)) {
            throw new org.robovm.compiler.CompilerException(
                    "Unknown " + BOOTSTRAP_CLASS + " method '" + bootstrap + "' in "
                    + method.getSignature() + ".");
        }

        List<Value> labels = invokeExpr.getBootstrapArgs();
        List<Type> typen = new ArrayList<>(labels.size());
        for (Value label : labels) {
            if (!(label instanceof ClassConstant)) {
                // Measured: javac only ever produces class constants here. Anything else is a
                // compiler, a language version or a hand-written class file this has not seen, and
                // guessing at it would put the guess on somebody's phone.
                throw new org.robovm.compiler.CompilerException(
                        "SwitchBootstraps.typeSwitch with a label that is not a class: " + label
                        + ", in " + method.getSignature() + ". Only class labels are supported.");
            }
            typen.add(alsTyp(((ClassConstant) label).getValue(), method));
        }

        List<Value> args = invokeExpr.getArgs();
        if (args.size() != 2) {
            throw new org.robovm.compiler.CompilerException(
                    "SwitchBootstraps.typeSwitch takes (target, restartIndex) but got " + args.size()
                    + " arguments, in " + method.getSignature() + ".");
        }

        Body body = method.retrieveActiveBody();
        Local ergebnis = (Local) defStmt.getLeftOp();
        Value ziel = args.get(0);
        Value start = args.get(1);

        LinkedList<Unit> units = baue(body, ergebnis, ziel, start, typen);

        // The line number of the invokedynamic, carried to every unit replacing it — otherwise a
        // debugger stepping through a switch lands nowhere.
        for (Object tag : defStmt.getTags()) {
            if (tag instanceof LineNumberTag) {
                int zeile = ((LineNumberTag) tag).getLineNumber();
                for (Unit unit : units) {
                    unit.addTag(new LineNumberTag(zeile));
                }
                break;
            }
        }
        return units;
    }

    private LinkedList<Unit> baue(Body body, Local ergebnis, Value ziel, Value start,
                                  List<Type> typen) {
        Jimple jimple = Jimple.v();
        LinkedList<Unit> units = new LinkedList<>();

        int anzahl = typen.size();
        NopStmt ende = jimple.newNopStmt();
        NopStmt keiner = jimple.newNopStmt();
        NopStmt istNull = jimple.newNopStmt();

        // One label per case to jump *to* when restarting, and one per case to jump to when it
        // matches. Both are needed: the first is where the test begins, the second is where the
        // answer is written.
        List<NopStmt> pruefe = new ArrayList<>(anzahl);
        List<NopStmt> treffer = new ArrayList<>(anzahl);
        for (int i = 0; i < anzahl; i++) {
            pruefe.add(jimple.newNopStmt());
            treffer.add(jimple.newNopStmt());
        }

        // null first: the bootstrap answers -1 for it, and an instanceof would answer false and
        // walk the whole chain to reach the wrong end.
        units.add(jimple.newIfStmt(jimple.newEqExpr(ziel, soot.jimple.NullConstant.v()), istNull));

        if (anzahl == 0) {
            // A switch with no labels at all. Degenerate, and cheaper to allow than to special-case
            // further down.
            units.add(jimple.newGotoStmt(keiner));
        } else {
            // The restart jump. A guarded case that fails stores its index + 1 and comes back here,
            // and this is what lets it skip straight past the labels already tried.
            List<Unit> ziele = new ArrayList<>(pruefe);
            units.add(jimple.newTableSwitchStmt(start, 0, anzahl - 1, ziele, keiner));

            for (int i = 0; i < anzahl; i++) {
                units.add(pruefe.get(i));
                Local passt = neuesLocal(body, soot.BooleanType.v());
                units.add(jimple.newAssignStmt(passt,
                        jimple.newInstanceOfExpr(ziel, typen.get(i))));
                units.add(jimple.newIfStmt(
                        jimple.newEqExpr(passt, IntConstant.v(1)), treffer.get(i)));
            }
            // Falling off the end of the chain is "none matched", which the next statement is.
        }

        units.add(keiner);
        units.add(jimple.newAssignStmt(ergebnis, IntConstant.v(anzahl)));
        units.add(jimple.newGotoStmt(ende));

        for (int i = 0; i < anzahl; i++) {
            units.add(treffer.get(i));
            units.add(jimple.newAssignStmt(ergebnis, IntConstant.v(i)));
            units.add(jimple.newGotoStmt(ende));
        }

        units.add(istNull);
        units.add(jimple.newAssignStmt(ergebnis, IntConstant.v(-1)));
        units.add(ende);
        return units;
    }

    /**
     * The type a class label names.
     *
     * <p>Soot hands the label back as the internal name it had in the constant pool, which is
     * {@code java/lang/Integer} for a class and a descriptor like {@code [I} or
     * {@code [Ljava/lang/String;} for an array. {@code RecordObjectMethodsDelegate} does the same
     * conversion for the first form and has never needed the second; a pattern switch can have
     * {@code case String[] s}, so both are here.
     */
    private static Type alsTyp(String intern, SootMethod method) {
        if (!intern.startsWith("[")) {
            return RefType.v(intern.replace('/', '.'));
        }
        int dimensionen = 0;
        while (dimensionen < intern.length() && intern.charAt(dimensionen) == '[') {
            dimensionen++;
        }
        String rest = intern.substring(dimensionen);
        Type element;
        switch (rest) {
            case "Z": element = soot.BooleanType.v(); break;
            case "B": element = soot.ByteType.v(); break;
            case "C": element = soot.CharType.v(); break;
            case "S": element = soot.ShortType.v(); break;
            case "I": element = IntType.v(); break;
            case "J": element = soot.LongType.v(); break;
            case "F": element = soot.FloatType.v(); break;
            case "D": element = soot.DoubleType.v(); break;
            default:
                if (!rest.startsWith("L") || !rest.endsWith(";")) {
                    throw new org.robovm.compiler.CompilerException(
                            "Cannot read the array label '" + intern + "' of a pattern switch in "
                            + method.getSignature() + ".");
                }
                element = RefType.v(rest.substring(1, rest.length() - 1).replace('/', '.'));
        }
        return soot.ArrayType.v(element, dimensionen);
    }

    private Local neuesLocal(Body body, Type type) {
        Local local = Jimple.v().newLocal("$sw" + (tmpCounter++), type);
        body.getLocals().add(local);
        return local;
    }
}
