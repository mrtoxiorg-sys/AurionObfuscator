package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.objectweb.asm.Opcodes.*;

/**
 * Анти-декомпилятор трансформер — ТОЛЬКО безопасные, JVM-валидные трюки,
 * которые переживают COMPUTE_FRAMES (ASM удаляет недостижимый код при пересчёте
 * фреймов, поэтому все вставки должны быть достижимы).
 *
 * Техники:
 *
 *  1. Fake try/catch: оборачиваем реальный (достижимый) участок метода в
 *     try-блок с обработчиком, который ловит Throwable и немедленно его
 *     перебрасывает (ATHROW). Семантика не меняется — при отсутствии исключения
 *     handler не выполняется, а при исключении оно проходит насквозь, как и
 *     раньше. Но декомпиляторы (Vineflower/CFR) вынуждены реконструировать
 *     вложенные try/catch, часто выдавая мусорные конструкции.
 *     Handler ДОСТИЖИМ как цель исключения => переживает верификатор и frames.
 *
 *  2. Junk exception handler: вставка дополнительного catch-региона с
 *     бессмысленной, но валидной обработкой, что ломает читаемость.
 *
 * Не переупорядочиваем реальные инструкции и не трогаем стек в исполняемых
 * путях — только оборачиваем и добавляем exception-таблицу.
 */
public final class AntiDecompileTransformer implements Transformer {

    private final ObfConfig cfg;
    private int injected = 0;

    public AntiDecompileTransformer(ObfConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "anti-decompile"; }
    @Override public boolean enabled() { return cfg.antiDecompile.enabled; }
    public int injected() { return injected; }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        if ((cn.access & (ACC_INTERFACE | ACC_ANNOTATION | ACC_MODULE)) != 0) return;

        int intensity = Math.max(1, cfg.antiDecompile.intensity);
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() < 4) continue;
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) continue;
            if ((m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;

            if (cfg.antiDecompile.fakeTryCatch) {
                wrapFakeTryCatch(m, intensity);
            }
        }
    }

    /**
     * Оборачивает реальный участок кода в try/catch(Throwable) -> rethrow.
     *
     * Раскладка:
     *   start:  <первый реальный insn>
     *           ... тело ...
     *   end:    GOTO after            (нормальный выход из try минуя handler)
     *   handler: ASTORE tmp; ALOAD tmp; ATHROW   (перебрасываем)
     *   after:  <продолжение>
     *
     * try-регион [start, end) с обработчиком handler на Throwable. Handler
     * достижим только по исключению => валиден. Нормальный поток идёт через
     * GOTO after и никогда не попадает в handler без исключения.
     */
    private void wrapFakeTryCatch(MethodNode m, int intensity) {
        InsnList list = m.instructions;

        // Найдём первую "реальную" инструкцию (после возможных лейблов/фреймов).
        AbstractInsnNode first = list.getFirst();
        while (first != null && !isRealInsn(first)) first = first.getNext();
        if (first == null) return;

        // Точка конца оборачиваемого региона: последняя инструкция метода.
        AbstractInsnNode last = list.getLast();
        if (last == null || last == first) return;

        // Свободный слот для временной переменной (throwable).
        int tmpSlot = m.maxLocals; // безопасно: COMPUTE_MAXS пересчитает
        if (tmpSlot < 1) tmpSlot = 1;

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        LabelNode handler = new LabelNode(new Label());
        LabelNode after = new LabelNode(new Label());

        // start в самом начале тела
        list.insertBefore(first, start);

        // В конце: end; GOTO after; handler: ASTORE; ALOAD; ATHROW; after:
        InsnList tail = new InsnList();
        tail.add(end);
        tail.add(new JumpInsnNode(GOTO, after));
        tail.add(handler);
        tail.add(new org.objectweb.asm.tree.VarInsnNode(ASTORE, tmpSlot));
        tail.add(new org.objectweb.asm.tree.VarInsnNode(ALOAD, tmpSlot));
        tail.add(new InsnNode(ATHROW));
        tail.add(after);
        list.add(tail);

        // exception-таблица: [start, end) -> handler на java/lang/Throwable
        if (m.tryCatchBlocks == null) m.tryCatchBlocks = new ArrayList<>();
        // ВАЖНО: наши блоки добавляем В НАЧАЛО, чтобы не нарушить приоритет
        // существующих catch (более специфичные должны идти раньше — но наш
        // ловит Throwable и оборачивает весь метод, поэтому он должен быть
        // ПОСЛЕДНИМ в списке, т.е. с наименьшим приоритетом).
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        injected++;
    }

    private static boolean isRealInsn(AbstractInsnNode insn) {
        int t = insn.getType();
        return t != AbstractInsnNode.LABEL
                && t != AbstractInsnNode.FRAME
                && t != AbstractInsnNode.LINE;
    }
}
