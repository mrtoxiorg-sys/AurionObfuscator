package dev.toxi.obf.core;

import dev.toxi.obf.config.ObfConfig;

import java.util.List;

/**
 * Определяет, применять ли конкретную технику обфускации к данному классу,
 * с учётом include/exclude-списков техники и глобальных excludePackages.
 *
 * Семантика:
 *   - exclude имеет наивысший приоритет (перекрывает всё);
 *   - если include НЕ пуст — техника применяется ТОЛЬКО к include-классам;
 *   - если include пуст — применяется ко всем (кроме exclude и глобальных
 *     excludePackages).
 *
 * Синтаксис шаблонов совпадает с keep-правилами (см. {@link KeepRules}):
 *   com.example.Foo, com.example.**, com.example.*, com.example.Foo$*.
 */
public final class TargetMatcher {

    private final KeepRules include;
    private final KeepRules exclude;
    private final boolean hasInclude;
    private final KeepRules globalExclude;

    public TargetMatcher(ObfConfig.TargetOptions opts, List<String> globalExcludePackages) {
        this.include = new KeepRules();
        this.exclude = new KeepRules();
        if (opts != null) {
            include.addAll(opts.include);
            exclude.addAll(opts.exclude);
            this.hasInclude = opts.include != null && !opts.include.isEmpty();
        } else {
            this.hasInclude = false;
        }
        this.globalExclude = new KeepRules();
        if (globalExcludePackages != null) globalExclude.addAll(globalExcludePackages);
    }

    /** Разрешено ли применять технику к классу (internal name a/b/C). */
    public boolean allows(String internalName) {
        if (globalExclude.matchesClass(internalName)) return false;
        if (exclude.matchesClass(internalName)) return false;
        if (hasInclude) return include.matchesClass(internalName);
        return true;
    }
}
