package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.*;

/**
 * Шифрование строковых литералов.
 *
 * Три стратегии (см. {@link ObfConfig.StringEncryptOptions#strategy}):
 *
 *  - "shared": один общий класс-дешифратор. Call-site:
 *        LDC enc; LDC key; INVOKESTATIC shared.d(String,int)String
 *    (единая точка отказа — слабейшая).
 *
 *  - "pool": N дешифраторов; класс детерминированно привязан к одному из них
 *    по хешу имени. Снятие требует обработки нескольких точек.
 *
 *  - "perClass": в КАЖДЫЙ класс встраивается приватный synthetic-дешифратор
 *    с контекстным ключом (per-class константы base/mult/step/step2).
 *    Call-site: LDC enc; <idx>; INVOKESTATIC self.<dm>(String,int)String —
 *    ни ключа, ни формулы на месте вызова. Опция lazyArray дополнительно
 *    материализует все строки в static final String[] через <clinit>, тогда
 *    call-site вырождается в GETSTATIC arr; <idx>; AALOAD.
 */
public final class StringEncryptTransformer implements Transformer {

    private final ObfConfig cfg;
    private final Pattern[] skip;
    private int encryptedCount = 0;

    // shared/pool
    private final String sharedName;             // для shared
    private final List<String> poolNames;        // для pool

    public StringEncryptTransformer(ObfConfig cfg, String sharedName, List<String> poolNames) {
        this.cfg = cfg;
        this.sharedName = sharedName;
        this.poolNames = poolNames;
        this.skip = cfg.stringEncryption.skipPatterns.stream()
                .map(Pattern::compile)
                .toArray(Pattern[]::new);
    }

    @Override public String name() { return "string-encryption"; }
    @Override public boolean enabled() { return cfg.stringEncryption.enabled; }
    public int encryptedCount() { return encryptedCount; }

    private String strategy() {
        String s = cfg.stringEncryption.strategy;
        return s == null ? "shared" : s;
    }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        switch (strategy()) {
            case "perClass" -> transformPerClass(cn);
            case "pool"     -> transformShared(cn, pickPoolName(cn.name));
            default         -> transformShared(cn, sharedName);
        }
    }

    // ---------------------------------------------------------
    //  SHARED / POOL
    // ---------------------------------------------------------
    private void transformShared(ClassNode cn, String decryptorName) {
        if (decryptorName == null) return;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            InsnList list = m.instructions;
            for (AbstractInsnNode insn = list.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && shouldEncrypt(s)) {
                    int key = ThreadLocalRandom.current().nextInt(1, 0x7FFF);
                    String enc = StringDecryptorGenerator.encrypt(s, key);
                    InsnList repl = new InsnList();
                    repl.add(new LdcInsnNode(enc));
                    repl.add(intConst(key));
                    repl.add(new MethodInsnNode(INVOKESTATIC, decryptorName,
                            StringDecryptorGenerator.METHOD, StringDecryptorGenerator.DESC, false));
                    list.insert(insn, repl);
                    list.remove(insn);
                    encryptedCount++;
                }
                insn = next;
            }
        }
    }

    private String pickPoolName(String className) {
        if (poolNames == null || poolNames.isEmpty()) return sharedName;
        int idx = Math.floorMod(className.hashCode(), poolNames.size());
        return poolNames.get(idx);
    }

    // ---------------------------------------------------------
    //  PER-CLASS
    // ---------------------------------------------------------
    private void transformPerClass(ClassNode cn) {
        // интерфейсы/аннотации/модули не имеют clinit-исполнения и обычно без
        // тел — но LDC могут быть в default-методах интерфейса; тем не менее
        // встраивание static-метода в интерфейс валидно (private static с V21).
        // Собираем все шифруемые LDC.
        List<Site> sites = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            if (m.name.equals("<clinit>") && cfg.stringEncryption.lazyArray) {
                // lazyArray наполняет clinit сам — не трогаем существующие LDC в
                // clinit, чтобы избежать курицы-яйца (доступ к массиву до его
                // инициализации). Безопаснее пропустить.
                continue;
            }
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && shouldEncrypt(s)) {
                    sites.add(new Site(m, ldc, s));
                }
            }
        }
        if (sites.isEmpty()) return;

        // per-class константы
        int base  = ThreadLocalRandom.current().nextInt();
        int mult  = ThreadLocalRandom.current().nextInt() | 1; // нечётный
        int step  = ThreadLocalRandom.current().nextInt(1, 0x4000);
        int step2 = ThreadLocalRandom.current().nextInt(0, 0x2000);

        String dm = pickMethodName(cn);

        if (cfg.stringEncryption.lazyArray) {
            transformLazyArray(cn, sites, base, mult, step, step2, dm);
        } else {
            transformInline(cn, sites, base, mult, step, step2, dm);
        }

        // встроить сам дешифратор
        cn.methods.add(StringDecryptorGenerator.generatePerClassMethod(dm, base, mult, step, step2));
    }

    /** Inline: каждый call-site = LDC enc; idx; INVOKESTATIC self.dm. */
    private void transformInline(ClassNode cn, List<Site> sites,
                                 int base, int mult, int step, int step2, String dm) {
        int idx = 0;
        for (Site st : sites) {
            String enc = StringDecryptorGenerator.encryptPerClass(st.value, idx, base, mult, step, step2);
            InsnList repl = new InsnList();
            repl.add(new LdcInsnNode(enc));
            repl.add(intConst(idx));
            repl.add(new MethodInsnNode(INVOKESTATIC, cn.name, dm,
                    StringDecryptorGenerator.PERCLASS_DESC, (cn.access & ACC_INTERFACE) != 0));
            st.method.instructions.insert(st.ldc, repl);
            st.method.instructions.remove(st.ldc);
            encryptedCount++;
            idx++;
        }
    }

    /**
     * Lazy array: строки складываются в static final String[] arr, наполняемый
     * в <clinit> (arr[i] = dm(enc_i, i)). Call-site => GETSTATIC arr; idx; AALOAD.
     * Не применяем к интерфейсам (final-поле + clinit-инициализация массива
     * усложняют валидность) — там падаем обратно на inline.
     */
    private void transformLazyArray(ClassNode cn, List<Site> sites,
                                    int base, int mult, int step, int step2, String dm) {
        if ((cn.access & ACC_INTERFACE) != 0) {
            transformInline(cn, sites, base, mult, step, step2, dm);
            return;
        }
        String arrField = pickFieldName(cn);
        int n = sites.size();

        // поле: private static final String[] arrField
        cn.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC,
                arrField, "[Ljava/lang/String;", null, null));

        // байткод наполнения массива
        InsnList init = new InsnList();
        init.add(NumberTransformer.pushInt(n));
        init.add(new TypeInsnNode(ANEWARRAY, "java/lang/String"));
        // stack: [arr]
        int idx = 0;
        for (Site st : sites) {
            String enc = StringDecryptorGenerator.encryptPerClass(st.value, idx, base, mult, step, step2);
            init.add(new InsnNode(DUP));                 // arr, arr
            init.add(NumberTransformer.pushInt(idx));    // arr, arr, idx
            init.add(new LdcInsnNode(enc));              // arr, arr, idx, enc
            init.add(NumberTransformer.pushInt(idx));    // arr, arr, idx, enc, idx
            init.add(new MethodInsnNode(INVOKESTATIC, cn.name, dm,
                    StringDecryptorGenerator.PERCLASS_DESC, false)); // arr, arr, idx, str
            init.add(new InsnNode(AASTORE));             // arr
            idx++;
        }
        init.add(new FieldInsnNode(PUTSTATIC, cn.name, arrField, "[Ljava/lang/String;"));

        // вставить в начало <clinit> (создать при отсутствии)
        MethodNode clinit = cn.methods.stream()
                .filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(RETURN));
            cn.methods.add(clinit);
        } else {
            clinit.instructions.insert(init);
        }

        // переписать call-sites на GETSTATIC arr; idx; AALOAD
        idx = 0;
        for (Site st : sites) {
            InsnList repl = new InsnList();
            repl.add(new FieldInsnNode(GETSTATIC, cn.name, arrField, "[Ljava/lang/String;"));
            repl.add(intConst(idx));
            repl.add(new InsnNode(AALOAD));
            st.method.instructions.insert(st.ldc, repl);
            st.method.instructions.remove(st.ldc);
            encryptedCount++;
            idx++;
        }
    }

    // ---------------------------------------------------------
    //  Вспомогательное
    // ---------------------------------------------------------
    private record Site(MethodNode method, LdcInsnNode ldc, String value) {}

    private boolean shouldEncrypt(String s) {
        if (s.length() < cfg.stringEncryption.minLength) return false;
        for (Pattern p : skip) {
            if (p.matcher(s).matches()) return false;
        }
        return true;
    }

    private String pickMethodName(ClassNode cn) {
        String[] cand = {"lI", "Il", "ll", "II", "l1", "i1"};
        for (int t = 0; t < 10000; t++) {
            String c = cand[ThreadLocalRandom.current().nextInt(cand.length)]
                    + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xFFFF));
            String finalC = c;
            boolean clash = cn.methods.stream().anyMatch(m -> m.name.equals(finalC));
            if (!clash) return c;
        }
        return "$decrypt";
    }

    private String pickFieldName(ClassNode cn) {
        String[] cand = {"lI", "Il", "ll", "II"};
        for (int t = 0; t < 10000; t++) {
            String c = cand[ThreadLocalRandom.current().nextInt(cand.length)]
                    + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xFFFF));
            String finalC = c;
            boolean clash = cn.fields.stream().anyMatch(f -> f.name.equals(finalC));
            if (!clash) return c;
        }
        return "$strs";
    }

    private static AbstractInsnNode intConst(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            return new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            return new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value);
        return new LdcInsnNode(value);
    }
}
