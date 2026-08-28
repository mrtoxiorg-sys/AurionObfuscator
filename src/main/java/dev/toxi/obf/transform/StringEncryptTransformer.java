package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.INVOKESTATIC;

/**
 * Шифрование строковых литералов.
 *
 * Заменяет каждый {@code LDC "text"} на:
 *   LDC "<encrypted>"
 *   LDC <key>
 *   INVOKESTATIC <decryptor>.d(String,I)String
 *
 * В декомпиляторе исходная строка исчезает — виден лишь мусорный литерал и
 * вызов расшифровщика, что заметно усложняет понимание логики (URL, ключи,
 * названия команд и т.п. скрыты).
 *
 * Защита: не шифруем слишком короткие строки и строки под skipPatterns.
 * Также НЕ трогаем строки, которые являются аргументами invokedynamic
 * bootstrap (обрабатываются отдельно ASM), — здесь мы работаем только с LDC
 * в теле метода, что безопасно.
 */
public final class StringEncryptTransformer implements Transformer {

    private final ObfConfig cfg;
    private final String decryptorInternalName;
    private final Pattern[] skip;
    private int encryptedCount = 0;

    public StringEncryptTransformer(ObfConfig cfg, String decryptorInternalName) {
        this.cfg = cfg;
        this.decryptorInternalName = decryptorInternalName;
        this.skip = cfg.stringEncryption.skipPatterns.stream()
                .map(Pattern::compile)
                .toArray(Pattern[]::new);
    }

    @Override public String name() { return "string-encryption"; }

    @Override public boolean enabled() { return cfg.stringEncryption.enabled; }

    public int encryptedCount() { return encryptedCount; }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        // Не шифруем строки в самом декрипторе (его нет среди input) и в mixin-
        // классах трогать LDC безопасно, поэтому ограничений тут нет.
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            processMethod(m);
        }
    }

    private void processMethod(MethodNode m) {
        InsnList list = m.instructions;
        for (AbstractInsnNode insn = list.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                if (shouldEncrypt(s)) {
                    int key = ThreadLocalRandom.current().nextInt(1, 0x7FFF);
                    String enc = StringDecryptorGenerator.encrypt(s, key);

                    InsnList repl = new InsnList();
                    repl.add(new LdcInsnNode(enc));
                    repl.add(intConst(key));
                    repl.add(new MethodInsnNode(INVOKESTATIC,
                            decryptorInternalName,
                            StringDecryptorGenerator.METHOD,
                            StringDecryptorGenerator.DESC,
                            false));

                    list.insert(insn, repl);
                    list.remove(insn);
                    encryptedCount++;
                }
            }
            insn = next;
        }
    }

    private boolean shouldEncrypt(String s) {
        if (s.length() < cfg.stringEncryption.minLength) return false;
        for (Pattern p : skip) {
            if (p.matcher(s).matches()) return false;
        }
        return true;
    }

    /** Оптимальная загрузка int-константы. */
    private static AbstractInsnNode intConst(int value) {
        if (value >= -1 && value <= 5) {
            return new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }
}
