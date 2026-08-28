package dev.toxi.obf.core;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Ввод/вывод jar: чтение прочих ресурсов и запись финального jar.
 */
public final class JarIO {

    private JarIO() {}

    /** Прочитать все НЕ-.class ресурсы входного jar. */
    public static List<JarResource> readResources(Path jar) throws IOException {
        List<JarResource> out = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    out.add(new JarResource(e.getName(), in.readAllBytes()));
                }
            }
        }
        return out;
    }

    /**
     * ClassWriter с корректным getCommonSuperClass, использующим наш ClassPool,
     * а не рантайм-загрузчик. Это обязательно для COMPUTE_FRAMES — иначе ASM
     * попытается загрузить классы Minecraft через Class.forName и упадёт.
     */
    public static class PoolClassWriter extends ClassWriter {
        private final ClassPool pool;

        public PoolClassWriter(ClassPool pool, int flags) {
            super(flags);
            this.pool = pool;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // Быстрые пути
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object"))
                return "java/lang/Object";

            // Пытаемся вычислить по нашему пулу
            try {
                if (isAssignable(type1, type2)) return type1;
                if (isAssignable(type2, type1)) return type2;

                // Идём вверх по type1 пока не найдём общий супертип
                String t = type1;
                while (t != null && !t.equals("java/lang/Object")) {
                    ClassInfo ci = pool.get(t);
                    if (ci == null) break;
                    t = ci.node.superName;
                    if (t != null && isAssignable(t, type2)) return t;
                }
            } catch (Exception ignored) {}

            // Фолбэк на попытку рантайма, иначе Object
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }

        /** type target достижим из type source по цепочке наследования? */
        private boolean isAssignable(String target, String source) {
            if (target.equals(source)) return true;
            var supers = pool.allSuperTypes(source);
            return supers.contains(target);
        }
    }

    /**
     * Записать финальный jar: трансформированные классы + ресурсы.
     * Классы, отсутствующие в списке nodes (например, удалённые), не пишутся.
     */
    public static void write(Path outJar,
                             ClassPool pool,
                             List<ClassNode> nodes,
                             List<JarResource> resources) throws IOException {
        Files.createDirectories(outJar.toAbsolutePath().getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar))) {
            // Классы
            for (ClassNode cn : nodes) {
                PoolClassWriter cw = new PoolClassWriter(pool,
                        ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                byte[] bytes;
                try {
                    cn.accept(cw);
                    bytes = cw.toByteArray();
                } catch (Throwable t) {
                    // Если COMPUTE_FRAMES упал (редко, из-за экзотики) —
                    // пробуем без frames как деградацию, чтобы не терять класс.
                    Log.warn("Не удалось записать " + cn.name + " c COMPUTE_FRAMES ("
                            + t.getMessage() + "), fallback COMPUTE_MAXS");
                    PoolClassWriter cw2 = new PoolClassWriter(pool, ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw2);
                    bytes = cw2.toByteArray();
                }
                JarEntry je = new JarEntry(cn.name + ".class");
                jos.putNextEntry(je);
                jos.write(bytes);
                jos.closeEntry();
            }
            // Ресурсы
            for (JarResource r : resources) {
                JarEntry je = new JarEntry(r.path);
                jos.putNextEntry(je);
                jos.write(r.data);
                jos.closeEntry();
            }
        }
    }
}
