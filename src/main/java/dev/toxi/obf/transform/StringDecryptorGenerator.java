package dev.toxi.obf.transform;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Генерация байткода дешифраторов строк.
 *
 * Поддерживает две формы:
 *
 *  1) SHARED / POOL — отдельный класс-дешифратор с публичным static-методом
 *     {@code String d(String enc, int key)}. Алгоритм: посимвольный XOR
 *     {@code c[i] ^ (key + i*31)}. Используется, когда дешифратор общий.
 *
 *  2) PER-CLASS — приватный synthetic static-метод, ВСТРАИВАЕМЫЙ прямо в
 *     обфусцируемый класс. Сигнатура {@code String <name>(String enc, int idx)}.
 *     Ключ вычисляется ВНУТРИ метода из idx и трёх per-class констант,
 *     запечённых в тело: {@code key = base ^ (idx * mult)}. Формула
 *     расшифровки: {@code c[i] ^ (key + i*step) ^ (i*i*step2)}.
 *     На call-site нет ни ключа, ни формулы — только строка и маленький idx.
 *     У каждого класса свои base/mult/step/step2 => нет единой точки отказа.
 */
public final class StringDecryptorGenerator {

    private StringDecryptorGenerator() {}

    /** Имя метода общего (shared/pool) декриптора. */
    public static final String METHOD = "d";
    public static final String DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    /** Дескриптор per-class метода: (String enc, int idx) -> String. */
    public static final String PERCLASS_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    // ============================================================
    //  SHARED / POOL: симметричный XOR c[i] ^ (key + i*31)
    // ============================================================

    /** Симметричное шифрование для shared/pool-схемы. */
    public static String encrypt(String s, int key) {
        char[] chars = s.toCharArray();
        char[] out = new char[chars.length];
        for (int i = 0; i < chars.length; i++) {
            out[i] = (char) (chars[i] ^ (key + i * 31));
        }
        return new String(out);
    }

    /** Собрать ClassNode отдельного дешифратора (shared/pool). */
    public static ClassNode generate(String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = V21;
        cn.access = ACC_PUBLIC | ACC_FINAL | ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";

        MethodVisitor init = cn.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor mv = cn.visitMethod(ACC_PUBLIC | ACC_STATIC, METHOD, DESC, null, null);
        mv.visitCode();
        // char[] c = enc.toCharArray();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3);
        Label loopStart = new Label();
        Label loopEnd = new Label();
        mv.visitLabel(loopStart);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(CALOAD);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitIntInsn(BIPUSH, 31);
        mv.visitInsn(IMUL);
        mv.visitInsn(IADD);
        mv.visitInsn(IXOR);
        mv.visitInsn(I2C);
        mv.visitInsn(CASTORE);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(GOTO, loopStart);
        mv.visitLabel(loopEnd);
        mv.visitTypeInsn(NEW, "java/lang/String");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return cn;
    }

    // ============================================================
    //  PER-CLASS: контекстный ключ, встраивается в сам класс
    // ============================================================

    /**
     * Симметричное шифрование для per-class схемы.
     * key = base ^ (idx * mult); out[i] = c[i] ^ (key + i*step) ^ (i*i*step2).
     */
    public static String encryptPerClass(String s, int idx, int base, int mult, int step, int step2) {
        int key = base ^ (idx * mult);
        char[] chars = s.toCharArray();
        char[] out = new char[chars.length];
        for (int i = 0; i < chars.length; i++) {
            out[i] = (char) (chars[i] ^ (key + i * step) ^ (i * i * step2));
        }
        return new String(out);
    }

    /**
     * Собрать MethodNode per-class дешифратора с запечёнными константами.
     * Локали: 0=enc, 1=idx, 2=key(int), 3=c(char[]), 4=i(int).
     */
    public static MethodNode generatePerClassMethod(String methodName,
                                                    int base, int mult, int step, int step2) {
        MethodNode mn = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                methodName, PERCLASS_DESC, null, null);
        MethodVisitor mv = mn;
        mv.visitCode();

        // int key = base ^ (idx * mult);
        mv.visitLdcInsn(base);
        mv.visitVarInsn(ILOAD, 1);           // idx
        mv.visitLdcInsn(mult);
        mv.visitInsn(IMUL);                  // idx*mult
        mv.visitInsn(IXOR);                  // base ^ (idx*mult)
        mv.visitVarInsn(ISTORE, 2);          // key -> 2

        // char[] c = enc.toCharArray();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mv.visitVarInsn(ASTORE, 3);          // c -> 3

        // for (int i = 0; i < c.length; i++)
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 4);          // i -> 4
        Label loopStart = new Label();
        Label loopEnd = new Label();
        mv.visitLabel(loopStart);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // c[i] = (char)(c[i] ^ (key + i*step) ^ (i*i*step2));
        mv.visitVarInsn(ALOAD, 3);           // c
        mv.visitVarInsn(ILOAD, 4);           // i
        mv.visitVarInsn(ALOAD, 3);           // c
        mv.visitVarInsn(ILOAD, 4);           // i
        mv.visitInsn(CALOAD);                // c[i]
        // (key + i*step)
        mv.visitVarInsn(ILOAD, 2);           // key
        mv.visitVarInsn(ILOAD, 4);           // i
        mv.visitLdcInsn(step);
        mv.visitInsn(IMUL);                  // i*step
        mv.visitInsn(IADD);                  // key + i*step
        mv.visitInsn(IXOR);                  // c[i] ^ (key + i*step)
        // ^ (i*i*step2)
        mv.visitVarInsn(ILOAD, 4);           // i
        mv.visitVarInsn(ILOAD, 4);           // i
        mv.visitInsn(IMUL);                  // i*i
        mv.visitLdcInsn(step2);
        mv.visitInsn(IMUL);                  // i*i*step2
        mv.visitInsn(IXOR);                  // ^
        mv.visitInsn(I2C);
        mv.visitInsn(CASTORE);

        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(GOTO, loopStart);
        mv.visitLabel(loopEnd);

        // return new String(c);
        mv.visitTypeInsn(NEW, "java/lang/String");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return mn;
    }
}
