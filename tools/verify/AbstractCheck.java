import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * AbstractCheck — статический верификатор реализации абстрактных методов.
 *
 * Для каждого НЕ-абстрактного класса проверяет, что все абстрактные методы его
 * супертипов (объявленные внутри мода) реально реализованы (по имени+desc).
 *
 * Ловит AbstractMethodError, возникающий при несогласованном переименовании
 * override у sibling-классов с общим абстрактным предком, — до запуска игры.
 *
 * Использование:
 *   javac -cp <fat-jar> AbstractCheck.java
 *   java  -cp .:<fat-jar> AbstractCheck <obf.jar>
 *
 * Код возврата: 0 — чисто, 1 — есть нереализованные абстрактные методы.
 */
public class AbstractCheck {
    static Map<String, ClassNode> cs = new HashMap<>();

    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: AbstractCheck <jar>"); System.exit(2); }
        try (JarFile jf = new JarFile(a[0])) {
            var en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, 0);
                    cs.put(cn.name, cn);
                }
            }
        }
        int problems = 0;
        for (ClassNode cn : cs.values()) {
            if ((cn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;

            Set<String> abstractMethods = new HashSet<>();
            collectAbstract(cn.superName, abstractMethods);
            if (cn.interfaces != null)
                for (String i : cn.interfaces) collectAbstract(i, abstractMethods);

            Set<String> concrete = new HashSet<>();
            collectConcrete(cn.name, concrete);

            for (String am : abstractMethods) {
                if (!concrete.contains(am)) {
                    problems++;
                    if (problems <= 30)
                        System.out.println("UNIMPLEMENTED: " + cn.name + " не реализует " + am);
                }
            }
        }
        System.out.println("\n=== AbstractCheck ===");
        System.out.println("Нереализованных абстрактных методов: " + problems);
        System.exit(problems == 0 ? 0 : 1);
    }

    static void collectAbstract(String name, Set<String> out) {
        if (name == null) return;
        ClassNode cn = cs.get(name);
        if (cn == null) return; // внешний (MC) — пропускаем
        for (MethodNode m : cn.methods)
            if ((m.access & Opcodes.ACC_ABSTRACT) != 0) out.add(m.name + m.desc);
        collectAbstract(cn.superName, out);
        if (cn.interfaces != null) for (String i : cn.interfaces) collectAbstract(i, out);
    }

    static void collectConcrete(String name, Set<String> out) {
        if (name == null) return;
        ClassNode cn = cs.get(name);
        if (cn == null) return;
        for (MethodNode m : cn.methods)
            if ((m.access & Opcodes.ACC_ABSTRACT) == 0) out.add(m.name + m.desc);
        collectConcrete(cn.superName, out);
        if (cn.interfaces != null) for (String i : cn.interfaces) collectConcrete(i, out);
    }
}
