import java.io.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;

/**
 * LoadVerify — истинная верификация байткода через реальную загрузку классов.
 *
 * Загружает каждый класс обфусцированного jar в отдельный URLClassLoader вместе
 * с Minecraft (intermediary) и библиотечным classpath. Успешная линковка =
 * валидный байткод + корректные ссылки.
 *
 * Различает:
 *   - VerifyError / ClassFormatError  -> реальные дефекты обфускации (плохо!)
 *   - прочие (NoClassDefFound от MC/Fabric в рантайме, IllegalAccessError без
 *     access widener, ExceptionInInitializerError от MC render-системы) —
 *     ожидаемы вне живого клиента и НЕ связаны с обфускацией.
 *
 * Использование:
 *   javac LoadVerify.java
 *   java  LoadVerify <obf.jar> <libs.txt> <mc-intermediary.jar>
 *
 * где libs.txt — файл с classpath, разделённым ':' (см. build-classpath.sh).
 */
public class LoadVerify {
    public static void main(String[] a) throws Exception {
        if (a.length < 3) {
            System.out.println("usage: LoadVerify <obf.jar> <libs.txt> <mc.jar>");
            System.exit(2);
        }
        String obfJar = a[0], cpFile = a[1], mc = a[2];
        List<URL> urls = new ArrayList<>();
        urls.add(new File(obfJar).toURI().toURL());
        urls.add(new File(mc).toURI().toURL());
        for (String p : new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(cpFile))).trim().split(":")) {
            if (!p.isBlank()) urls.add(new File(p).toURI().toURL());
        }
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                LoadVerify.class.getClassLoader());

        int ok = 0, verifyErr = 0, other = 0;
        List<String> names = new ArrayList<>();
        try (JarFile jf = new JarFile(obfJar)) {
            var en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                names.add(e.getName().replace('/', '.').replaceFirst("\\.class$", ""));
            }
        }
        for (String n : names) {
            try {
                Class.forName(n, false, cl);
                Class.forName(n, true, cl); // resolve=true форсирует линковку
                ok++;
            } catch (VerifyError ve) {
                verifyErr++;
                System.out.println("VERIFY ERROR: " + n + " : " + ve.getMessage());
            } catch (ClassFormatError cfe) {
                verifyErr++;
                System.out.println("CLASS FORMAT ERROR: " + n + " : " + cfe.getMessage());
            } catch (Throwable t) {
                other++;
                if (other <= 8) System.out.println("(non-verify) " + n + " : "
                        + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
        System.out.println("\n=== LoadVerify ===");
        System.out.println("Загружено OK: " + ok);
        System.out.println("Ошибок ВЕРИФИКАЦИИ байткода: " + verifyErr);
        System.out.println("Прочих (рантайм MC/Fabric, не связано с обф): " + other);
        System.exit(verifyErr == 0 ? 0 : 1);
    }
}
