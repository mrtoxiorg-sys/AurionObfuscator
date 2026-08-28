package dev.toxi.obf.transform;

import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.tree.ClassNode;

/**
 * Единый интерфейс этапа обфускации, работающего над одним классом.
 * Оркестратор прогоняет все включённые трансформеры по всем input-классам.
 */
public interface Transformer {

    /** Человекочитаемое имя этапа (для логов). */
    String name();

    /** Включён ли этап согласно конфигу. */
    boolean enabled();

    /**
     * Трансформировать класс на месте.
     * @param pool общий пул (для анализа иерархии/контекста)
     * @param cn   класс для трансформации
     */
    void transform(ClassPool pool, ClassNode cn);
}
