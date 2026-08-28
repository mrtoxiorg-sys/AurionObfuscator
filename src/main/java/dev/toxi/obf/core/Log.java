package dev.toxi.obf.core;

import java.util.function.Consumer;

/**
 * Простой логгер с настраиваемым sink'ом. GUI подменяет sink на вывод
 * в текстовую панель, CLI пишет в stdout.
 */
public final class Log {

    private static volatile boolean verbose = false;
    private static volatile Consumer<String> sink = System.out::println;

    private Log() {}

    public static void setVerbose(boolean v) { verbose = v; }
    public static void setSink(Consumer<String> s) { sink = s; }

    public static void info(String msg) { sink.accept("[*] " + msg); }
    public static void ok(String msg)   { sink.accept("[+] " + msg); }
    public static void warn(String msg) { sink.accept("[!] " + msg); }
    public static void err(String msg)  { sink.accept("[x] " + msg); }

    public static void debug(String msg) {
        if (verbose) sink.accept("    " + msg);
    }
}
