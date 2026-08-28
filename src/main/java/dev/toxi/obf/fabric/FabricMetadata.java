package dev.toxi.obf.fabric;

import java.util.HashSet;
import java.util.Set;

/**
 * Результат анализа Fabric-метаданных.
 *
 * keepClasses — internal-имена классов (a/b/C), которые НЕЛЬЗЯ переименовывать,
 * потому что на них ссылаются извне по строковому имени:
 *   - entrypoints из fabric.mod.json
 *   - mixin-классы из *.mixins.json (+ их package)
 *   - Fabric loader ищет их через рефлексию/имя.
 *
 * Даже если класс в keepClasses, его ВНУТРЕННОСТИ (строки, числа, control flow,
 * приватные методы без override) всё равно можно обфусцировать — этим занимается
 * отдельная логика. Здесь только защита ИМЕНИ класса.
 */
public final class FabricMetadata {

    public boolean isFabricMod = false;

    /** Internal-имена классов, чьи ИМЕНА защищены от переименования. */
    public final Set<String> keepClassNames = new HashSet<>();

    /** Internal-имена mixin-классов (их не трогаем даже частично при rename методов). */
    public final Set<String> mixinClasses = new HashSet<>();

    /** Точки входа (entrypoints) — internal имена. */
    public final Set<String> entrypoints = new HashSet<>();

    /**
     * Классы, чьи ЧЛЕНЫ (методы/поля) тоже нельзя переименовывать —
     * entrypoint-классы (loader зовёт их методы интерфейса по имени),
     * mixin-классы и mixin-интерфейсы.
     */
    public final Set<String> fullyKeptClasses = new HashSet<>();

    public void merge(FabricMetadata other) {
        this.isFabricMod |= other.isFabricMod;
        this.keepClassNames.addAll(other.keepClassNames);
        this.mixinClasses.addAll(other.mixinClasses);
        this.entrypoints.addAll(other.entrypoints);
        this.fullyKeptClasses.addAll(other.fullyKeptClasses);
    }
}
