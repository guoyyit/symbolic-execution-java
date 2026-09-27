package com.github.guoyyit.symbolicexecutionjava.model.symbolic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSA-style symbolic environment.
 *
 * <p>Every variable name points at a current <em>versioned symbol</em>
 * ({@code x}, {@code x__1}, {@code x__2}, …). Declarations without an
 * initialiser introduce a fresh free input symbol; an assignment creates a new
 * version whose affine value is expressed in terms of the versions live at
 * that point. Because versions are immutable, path constraints accumulated
 * earlier keep their meaning even after the source-level variable is
 * reassigned — this is what makes a model a consistent witness for a whole
 * path.</p>
 *
 * <p>The store is forked with {@link #copy()} at every branch; sibling paths
 * therefore have independent version counters and environments.</p>
 */
public final class SymbolicStore {

    /** Source variable name -> current versioned symbol. */
    private final Map<String, String> currentVersion = new LinkedHashMap<>();
    /** Versioned symbol -> affine expression over (possibly other) versioned symbols. */
    private final Map<String, AffineExpr> definitions = new LinkedHashMap<>();
    /** Free input versioned symbols (never assigned to an expression). */
    private final List<String> freeSymbols = new ArrayList<>();
    /** Per-name next version suffix. */
    private final Map<String, Integer> nextVersion = new LinkedHashMap<>();
    /** Original declaration order of source names. */
    private final List<String> sourceNames = new ArrayList<>();

    public SymbolicStore() {
    }

    private SymbolicStore(SymbolicStore other) {
        this.currentVersion.putAll(other.currentVersion);
        this.definitions.putAll(other.definitions);
        this.freeSymbols.addAll(other.freeSymbols);
        this.nextVersion.putAll(other.nextVersion);
        this.sourceNames.addAll(other.sourceNames);
    }

    /** Declares a fresh free input symbol under the given source name. */
    public void declareFree(String name) {
        if (currentVersion.containsKey(name)) {
            return;
        }
        sourceNames.add(name);
        nextVersion.put(name, 1);
        String version = name; // version 0 uses the bare name
        currentVersion.put(name, version);
        definitions.put(version, AffineExpr.variable(version));
        freeSymbols.add(version);
    }

    /** Declares a variable whose initial value is an affine expression. */
    public void declare(String name, AffineExpr value) {
        if (currentVersion.containsKey(name)) {
            assign(name, value);
            return;
        }
        sourceNames.add(name);
        nextVersion.put(name, 1);
        String version = name;
        currentVersion.put(name, version);
        definitions.put(version, value);
        // Track any free symbols this initialiser transitively uses.
    }

    /** Creates a new immutable version for an assignment. */
    public void assign(String name, AffineExpr value) {
        int v = nextVersion.getOrDefault(name, 0);
        String newVersion = name + "__" + v;
        nextVersion.put(name, v + 1);
        currentVersion.put(name, newVersion);
        definitions.put(newVersion, value);
    }

    /** Current affine value (in versioned symbols) of a source variable. */
    public AffineExpr get(String name) {
        String version = currentVersion.get(name);
        if (version == null) {
            return null;
        }
        return definitions.get(version);
    }

    /** Current versioned symbol for a source name. */
    public String currentVersionOf(String name) {
        return currentVersion.get(name);
    }

    public boolean contains(String name) {
        return currentVersion.containsKey(name);
    }

    public List<String> freeSymbols() {
        return List.copyOf(freeSymbols);
    }

    public List<String> sourceNames() {
        return List.copyOf(sourceNames);
    }

    public Map<String, AffineExpr> values() {
        Map<String, AffineExpr> view = new LinkedHashMap<>();
        currentVersion.forEach((name, version) -> view.put(name, definitions.get(version)));
        return Map.copyOf(view);
    }

    /**
     * Fully expands an affine expression in versioned symbols until it refers
     * only to free input symbols.
     */
    public AffineExpr expandToInputs(AffineExpr expr) {
        Map<String, Long> result = new LinkedHashMap<>();
        long constant = expr.constant();
        for (Map.Entry<String, Long> e : expr.coefficients().entrySet()) {
            String sym = e.getKey();
            long coeff = e.getValue();
            if (freeSymbols.contains(sym)) {
                result.merge(sym, coeff, Long::sum);
            } else {
                AffineExpr sub = definitions.get(sym);
                if (sub == null) {
                    result.merge(sym, coeff, Long::sum);
                    continue;
                }
                AffineExpr expandedSub = expandToInputs(sub);
                for (Map.Entry<String, Long> se : expandedSub.coefficients().entrySet()) {
                    result.merge(se.getKey(), coeff * se.getValue(), Long::sum);
                }
                constant += coeff * expandedSub.constant();
            }
        }
        return new AffineExpr(normalize(result), constant);
    }

    /** Concrete value of a source variable under a free-symbol model. */
    public long evaluate(String name, Map<String, Long> inputModel) {
        AffineExpr e = get(name);
        if (e == null) {
            throw new IllegalArgumentException("unknown variable: " + name);
        }
        return expandToInputs(e).evaluate(inputModel);
    }

    /** Concrete values of all declared source variables under a model. */
    public Map<String, Long> evaluateAll(Map<String, Long> inputModel) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : sourceNames) {
            result.put(name, evaluate(name, inputModel));
        }
        return result;
    }

    public SymbolicStore copy() {
        return new SymbolicStore(this);
    }

    private static Map<String, Long> normalize(Map<String, Long> raw) {
        Map<String, Long> c = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != 0L) {
                c.put(k, v);
            }
        });
        return Map.copyOf(c);
    }
}
