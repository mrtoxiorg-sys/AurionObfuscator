package dev.toxi.obf.core;

/**
 * Не-классовый ресурс входного jar (json, png, ogg, txt и т.п.).
 * Такие ресурсы копируются в выход как есть, но некоторые (fabric.mod.json,
 * *.mixins.json) могут быть переписаны при переименовании классов.
 */
public final class JarResource {
    public final String path;
    public byte[] data;

    public JarResource(String path, byte[] data) {
        this.path = path;
        this.data = data;
    }
}
