package dev.toxi.obf.core;

import org.objectweb.asm.tree.ClassNode;

/**
 * Обёртка над разобранным классом.
 *
 * Различаем два вида классов:
 *  - input (isLibrary == false): классы из входного jar, которые МОЖНО
 *    трансформировать.
 *  - library (isLibrary == true): классы из библиотек/classpath (Minecraft,
 *    Fabric, JDK), которые нужны только для анализа иерархии и НИКОГДА
 *    не переименовываются и не пишутся в выход.
 */
public final class ClassInfo {

    public final ClassNode node;
    public final boolean isLibrary;

    public ClassInfo(ClassNode node, boolean isLibrary) {
        this.node = node;
        this.isLibrary = isLibrary;
    }

    public String name() {
        return node.name;
    }

    public String superName() {
        return node.superName;
    }
}
