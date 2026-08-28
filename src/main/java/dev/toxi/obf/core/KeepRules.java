package dev.toxi.obf.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Матчер keep-правил (ProGuard-lite синтаксис).
 *
 * Работает с именами в ТОЧЕЧНОЙ нотации (com.example.Foo). Правила:
 *   com.example.Foo         — сам класс Foo
 *   com.example.Foo.bar     — член bar класса Foo
 *   com.example.*           — классы непосредственно в пакете
 *   com.example.**          — классы в пакете и всех вложенных
 *   com.example.Foo$*       — внутренние классы Foo (через $-совпадение)
 */
public final class KeepRules {

    private final List<String> patterns = new ArrayList<>();

    public void add(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            patterns.add(pattern.trim());
        }
    }

    public void addAll(List<String> list) {
        if (list != null) list.forEach(this::add);
    }

    /** Совпадает ли класс (internal name a/b/C) с любым keep-правилом. */
    public boolean matchesClass(String internalName) {
        String dotted = internalName.replace('/', '.');
        for (String p : patterns) {
            if (matchClassPattern(p, dotted)) return true;
        }
        return false;
    }

    /** Совпадает ли член класса (класс internal + имя члена). */
    public boolean matchesMember(String ownerInternal, String memberName) {
        String dottedOwner = ownerInternal.replace('/', '.');
        String full = dottedOwner + "." + memberName;
        for (String p : patterns) {
            if (p.equals(full)) return true;
            // правило на класс защищает и все его члены
            if (matchClassPattern(p, dottedOwner)) return true;
        }
        return false;
    }

    private static boolean matchClassPattern(String pattern, String dottedClass) {
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return dottedClass.equals(prefix) || dottedClass.startsWith(prefix + ".");
        }
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (!dottedClass.startsWith(prefix + ".")) return false;
            // только прямые члены пакета (без дополнительной точки)
            String rest = dottedClass.substring(prefix.length() + 1);
            return !rest.contains(".");
        }
        if (pattern.endsWith("$*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return dottedClass.startsWith(prefix + "$");
        }
        // точное совпадение класса
        return pattern.equals(dottedClass);
    }
}
