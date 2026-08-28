package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Удаление отладочной информации.
 *
 * Что убираем:
 *  - LineNumberTable (номера строк) — в стектрейсах не будет строк исходника.
 *  - LocalVariableTable / LocalVariableTypeTable — имена локальных переменных
 *    (в декомпиляторе будут var0, var1... вместо осмысленных имён).
 *  - SourceFile / SourceDebugExtension — имя исходного файла.
 *
 * Это дёшево и заметно ухудшает читаемость декомпиляции, при этом абсолютно
 * безопасно для исполнения.
 */
public final class DebugStripTransformer implements Transformer {

    private final ObfConfig cfg;

    public DebugStripTransformer(ObfConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "debug-strip"; }
    @Override public boolean enabled() { return cfg.debugStrip.enabled; }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        if (cfg.debugStrip.sourceFile) {
            cn.sourceFile = null;
            cn.sourceDebug = null;
        }

        for (MethodNode m : cn.methods) {
            if (cfg.debugStrip.localVariables) {
                m.localVariables = null;
                // parameters содержат имена параметров — тоже убираем
                m.parameters = null;
            }

            if (cfg.debugStrip.lineNumbers && m.instructions != null) {
                removeLineNumbers(m);
            }
        }
    }

    private void removeLineNumbers(MethodNode m) {
        AbstractInsnNode insn = m.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof LineNumberNode) {
                m.instructions.remove(insn);
            }
            insn = next;
        }
    }
}
