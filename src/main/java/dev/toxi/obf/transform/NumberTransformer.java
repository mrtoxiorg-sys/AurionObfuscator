package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.concurrent.ThreadLocalRandom;

import static org.objectweb.asm.Opcodes.*;

/**
 * Обфускация числовых констант.
 *
 * Заменяет прямые загрузки int/long-констант на арифметически эквивалентные
 * выражения вида {@code (encoded ^ key)}, где encoded = value ^ key.
 * В декомпиляторе вместо "magic number" виден XOR двух случайных чисел,
 * что скрывает реальные значения (тайминги, битовые маски, лимиты).
 *
 * НЕ трогаем константы внутри аннотаций/дефолтов — работаем только с
 * инструкциями в теле методов, что безопасно для семантики.
 */
public final class NumberTransformer implements Transformer {

    private final ObfConfig cfg;
    private int count = 0;

    public NumberTransformer(ObfConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "number-obfuscation"; }
    @Override public boolean enabled() { return cfg.numbers.enabled; }
    public int count() { return count; }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            process(m);
        }
    }

    private void process(MethodNode m) {
        InsnList list = m.instructions;
        for (AbstractInsnNode insn = list.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();

            if (cfg.numbers.integers) {
                Integer iv = intValue(insn);
                if (iv != null && shouldObf()) {
                    int key = ThreadLocalRandom.current().nextInt();
                    int enc = iv ^ key;
                    InsnList repl = new InsnList();
                    repl.add(pushInt(enc));
                    repl.add(pushInt(key));
                    repl.add(new InsnNode(IXOR));
                    list.insert(insn, repl);
                    list.remove(insn);
                    count++;
                    insn = next;
                    continue;
                }
            }

            if (cfg.numbers.longs) {
                Long lv = longValue(insn);
                if (lv != null && shouldObf()) {
                    long key = ThreadLocalRandom.current().nextLong();
                    long enc = lv ^ key;
                    InsnList repl = new InsnList();
                    repl.add(new LdcInsnNode(enc));
                    repl.add(new LdcInsnNode(key));
                    repl.add(new InsnNode(LXOR));
                    list.insert(insn, repl);
                    list.remove(insn);
                    count++;
                    insn = next;
                    continue;
                }
            }

            insn = next;
        }
    }

    // 70% констант обфусцируем — оставляем часть, чтобы не раздувать код чрезмерно
    private boolean shouldObf() {
        return ThreadLocalRandom.current().nextInt(100) < 70;
    }

    /** Извлечь int-значение из инструкции-загрузки константы, если это она. */
    private static Integer intValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) {
            return op - ICONST_0;
        }
        if (insn instanceof IntInsnNode iin && (op == BIPUSH || op == SIPUSH)) {
            return iin.operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) {
            return i;
        }
        return null;
    }

    private static Long longValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == LCONST_0) return 0L;
        if (op == LCONST_1) return 1L;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) {
            return l;
        }
        return null;
    }

    /** Оптимальная загрузка int. */
    static AbstractInsnNode pushInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, v);
        return new LdcInsnNode(v);
    }
}
