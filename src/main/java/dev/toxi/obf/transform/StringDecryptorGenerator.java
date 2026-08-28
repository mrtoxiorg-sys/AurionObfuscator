package dev.toxi.obf.transform;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Генерирует байткод класса-декриптора строк.
 *
 * Метод (публичный static):
 *   String d(String enc, int key)
 *
 * Алгоритм: строка была зашифрована посимвольным XOR'ом с ключом,
 * зависящим от позиции: c[i] ^ (key + i * 31). Обратная операция симметрична.
 *
 * Класс генерируется на лету и добавляется в выходной jar под именем,
 * которое передаёт оркестратор (обычно нечитаемое).
 */
public final class StringDecryptorGenerator {

    private StringDecryptorGenerator() {}

    /** Имя метода декриптора. */
    public static final String METHOD = "d";
    public static final String DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    /**
     * Симметричное шифрование строки. Возвращает зашифрованную строку.
     * Тот же алгоритм реализован в генерируемом байткоде для расшифровки.
     */
    public static String encrypt(String s, int key) {
        char[] chars = s.toCharArray();
        char[] out = new char[chars.length];
        for (int i = 0; i < chars.length; i++) {
            out[i] = (char) (chars[i] ^ (key + i * 31));
        }
        return new String(out);
    }

    /**
     * Собрать ClassNode декриптора с указанным internal-именем.
     */
    public static ClassNode generate(String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = V21;
        cn.access = ACC_PUBLIC | ACC_FINAL | ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";

        // приватный конструктор
        MethodVisitor init = cn.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public static String d(String enc, int key)
        MethodVisitor mv = cn.visitMethod(ACC_PUBLIC | ACC_STATIC, METHOD, DESC, null, null);
        mv.visitCode();

        // char[] c = enc.toCharArray();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mv.visitVarInsn(ASTORE, 2); // c -> slot 2

        // for (int i = 0; i < c.length; i++)
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3); // i -> slot 3

        Label loopStart = new Label();
        Label loopEnd = new Label();
        mv.visitLabel(loopStart);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // c[i] = (char)(c[i] ^ (key + i*31));
        mv.visitVarInsn(ALOAD, 2);       // c
        mv.visitVarInsn(ILOAD, 3);       // i
        mv.visitVarInsn(ALOAD, 2);       // c
        mv.visitVarInsn(ILOAD, 3);       // i
        mv.visitInsn(CALOAD);            // c[i]
        mv.visitVarInsn(ILOAD, 1);       // key
        mv.visitVarInsn(ILOAD, 3);       // i
        mv.visitIntInsn(BIPUSH, 31);
        mv.visitInsn(IMUL);              // i*31
        mv.visitInsn(IADD);              // key + i*31
        mv.visitInsn(IXOR);              // c[i] ^ (...)
        mv.visitInsn(I2C);               // (char)
        mv.visitInsn(CASTORE);           // c[i] = ...

        // i++
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(GOTO, loopStart);

        mv.visitLabel(loopEnd);
        // return new String(c);
        mv.visitTypeInsn(NEW, "java/lang/String");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0); // COMPUTE_MAXS при записи
        mv.visitEnd();

        return cn;
    }
}
