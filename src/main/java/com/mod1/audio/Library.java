package com.mod1.audio;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Playlists = sous-dossiers de  .minecraft/mod_1/playlists/  contenant des .mp3 */
public final class Library {
    public record Playlist(String name, Path dir, int trackCount) {}

    private Library() {}

    public static Path baseDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mod_1");
    }

    public static Path root() {
        return baseDir().resolve("playlists");
    }

    public static Path stateFile() {
        return baseDir().resolve("state.properties");
    }

    public static List<Playlist> scan() {
        List<Playlist> out = new ArrayList<>();
        try {
            Files.createDirectories(root());
            try (Stream<Path> s = Files.list(root())) {
                s.filter(Files::isDirectory)
                        .forEach(d -> out.add(new Playlist(d.getFileName().toString(), d, tracks(d).size())));
            }
        } catch (IOException ignored) {
            // dossier illisible : on renvoie ce qu'on a
        }
        out.sort(Comparator.comparing(Playlist::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public static List<Path> tracks(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String displayName(Path file) {
        String n = file.getFileName().toString();
        return n.toLowerCase(Locale.ROOT).endsWith(".mp3") ? n.substring(0, n.length() - 4) : n;
    }
}
