import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * LinkCheck — статический верификатор ссылок внутри обфусцированного jar.
 *
 * Для каждого вызова метода / доступа к полю, где owner — класс ИЗ мода,
 * проверяет, что цель реально существует (с учётом наследования внутри мода).
 * Вызовы к Minecraft/библиотекам (owner не из jar) пропускаются.
 *
 * Ловит баги рассогласования переименования, приводящие к NoSuchMethodError /
 * NoSuchFieldError ещё ДО запуска игры.
 *
 * Использование:
 *   javac -cp <asm.jar или fat-jar обфускатора> LinkCheck.java
 *   java  -cp .:<fat-jar> LinkCheck <obf.jar>
 *
 * Код возврата: 0 — чисто, 1 — найдены битые ссылки.
 */
public class LinkCheck {
    static Map<String, ClassNode> classes = new HashMap<>();

    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: LinkCheck <jar>"); System.exit(2); }
        try (JarFile jf = new JarFile(a[0])) {
            var en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    classes.put(cn.name, cn);
                }
            }
        }
        int problems = 0;
        for (ClassNode cn : classes.values()) {
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min) {
                        if (!classes.containsKey(min.owner)) continue;
                        if (min.name.startsWith("<")) continue;
                        if (!methodResolves(min.owner, min.name, min.desc)) {
                            problems++;
                            if (problems <= 30) System.out.println("MISSING METHOD: "
                                + min.owner + "." + min.name + min.desc
                                + "  (from " + cn.name + "." + m.name + ")");
                        }
                    } else if (insn instanceof FieldInsnNode fin) {
                        if (!classes.containsKey(fin.owner)) continue;
                        if (!fieldResolves(fin.owner, fin.name)) {
                            problems++;
                            if (problems <= 30) System.out.println("MISSING FIELD: "
                                + fin.owner + "." + fin.name
                                + "  (from " + cn.name + "." + m.name + ")");
                        }
                    }
                }
            }
        }
        System.out.println("\n=== LinkCheck ===");
        System.out.println("Классов мода: " + classes.size());
        System.out.println("Битых ссылок (метод/поле не находится): " + problems);
        System.exit(problems == 0 ? 0 : 1);
    }

    static boolean methodResolves(String owner, String name, String desc) {
        ClassNode cn = classes.get(owner);
        if (cn == null) return true; // внешний — считаем ок
        for (MethodNode m : cn.methods)
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        if (cn.superName != null && methodResolves(cn.superName, name, desc)) return true;
        if (cn.interfaces != null)
            for (String i : cn.interfaces)
                if (methodResolves(i, name, desc)) return true;
        // owner в моде, но метод мог прийти из MC-суперкласса (не в jar) — тогда ок
        if (cn.superName != null && !classes.containsKey(cn.superName)) return true;
        return false;
    }

    static boolean fieldResolves(String owner, String name) {
        ClassNode cn = classes.get(owner);
        if (cn == null) return true;
        for (FieldNode f : cn.fields)
            if (f.name.equals(name)) return true;
        if (cn.superName != null && fieldResolves(cn.superName, name)) return true;
        if (cn.superName != null && !classes.containsKey(cn.superName)) return true;
        return false;
    }
}
