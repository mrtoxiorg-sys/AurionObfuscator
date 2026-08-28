package dev.toxi.obf.transform;

import java.util.HashSet;
import java.util.Set;

/**
 * Генератор обфусцированных имён.
 *
 * Стили:
 *  - "alpha"      : a, b, c, ... aa, ab (короткие, легальные)
 *  - "illegal"    : символы, визуально путающие человека (Il1|О0) — валидные
 *                   идентификаторы JVM, но нечитаемые в декомпиляторе.
 *  - "dictionary" : длинные confusing-последовательности из I l 1 O 0.
 *
 * ВАЖНО: имена в JVM-байткоде могут содержать почти любые символы (JVM не
 * применяет правила идентификаторов Java-исходников), поэтому "illegal"-стиль
 * абсолютно валиден на уровне class-файла и ломает большинство декомпиляторов
 * при попытке восстановить читаемый исходник.
 */
public final class NameGenerator {

    private final String style;
    private final String prefix;
    private long counter = 0;
    private final Set<String> used = new HashSet<>();

    // Наборы символов для confusing-стилей.
    private static final char[] ILLEGAL = {'I', 'l', '1', 'i', 'j', 'L'};
    private static final char[] DICT = {'I', 'l', '1', 'O', '0', 'o'};

    public NameGenerator(String style, String prefix) {
        this.style = style == null ? "alpha" : style;
        this.prefix = prefix == null ? "" : prefix;
    }

    public String next() {
        String name;
        do {
            name = generate(counter++);
        } while (!used.add(prefix + name));
        return prefix + name;
    }

    private String generate(long n) {
        switch (style) {
            case "illegal":
                return fromCharset(n, ILLEGAL, 4);
            case "dictionary":
                return fromCharset(n, DICT, 8);
            case "alpha":
            default:
                return alpha(n);
        }
    }

    /** Классический a..z, aa..zz счётчик. */
    private static String alpha(long n) {
        StringBuilder sb = new StringBuilder();
        n++; // 1-based, чтобы 0 -> "a"
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('a' + (int) (n % 26)));
            n /= 26;
        }
        return sb.toString();
    }

    /**
     * Имя из заданного набора символов. minLen гарантирует, что имена не будут
     * слишком короткими (иначе легко читаются). Первый символ обязан быть
     * буквой (не цифрой) — требование JVM для начала идентификатора.
     */
    private static String fromCharset(long n, char[] cs, int minLen) {
        StringBuilder sb = new StringBuilder();
        long v = n + 1;
        while (v > 0) {
            sb.append(cs[(int) (v % cs.length)]);
            v /= cs.length;
        }
        // Дополним до minLen тем же паттерном, чтобы длины были равномернее
        while (sb.length() < minLen) {
            sb.append(cs[(int) (n % cs.length)]);
            n++;
        }
        // Первый символ — буква (не '1'/'0')
        char first = sb.charAt(0);
        if (first == '1' || first == '0') {
            sb.setCharAt(0, 'l');
        }
        return sb.toString();
    }
}
