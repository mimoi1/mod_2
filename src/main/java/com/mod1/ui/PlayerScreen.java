package com.mod1.ui;

import com.mod1.audio.Library;
import com.mod1.audio.Mp3Player;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Interface du lecteur : 1) choix de la playlist, 2) choix de la piste, contrôles en bas.
 * Se ferme avec Echap ou en refaisant Ctrl+Maj+Alt+P.
 */
public final class PlayerScreen extends Screen {
    private static PlayerScreen current;

    public static PlayerScreen current() {
        return current;
    }

    private final Mp3Player player = Mp3Player.INSTANCE;

    private List<Library.Playlist> playlists = List.of();
    private Library.Playlist selected;          // null = on affiche la liste des playlists
    private List<Path> tracks = List.of();
    private int page;
    private int shownIndex = -2;

    private StringWidget status;
    private Button playPause;

    public PlayerScreen() {
        super(Component.literal("mod_1"));
    }

    @Override
    protected void init() {
        current = this;
        if (selected == null) playlists = Library.scan();
        shownIndex = player.index();

        final int bw = Math.min(300, this.width - 40);
        final int bx = (this.width - bw) / 2;
        final int listTop = 34;
        final int statusY = this.height - 74;
        final int transportY = this.height - 52;
        final int navY = this.height - 28;
        final int rows = Math.max(2, (statusY - 8 - listTop) / 22);

        final int total = selected == null ? playlists.size() : tracks.size();
        final int pages = Math.max(1, (total + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));

        String header = selected == null ? "Choisis une playlist" : "Playlist : " + shorten(selected.name(), 30);
        if (pages > 1) header += "  (" + (page + 1) + "/" + pages + ")";
        addRenderableWidget(label(listTopHeaderY(), header));

        if (selected == null && total == 0) {
            addRenderableWidget(label(listTop, "Aucune playlist trouvée."));
            addRenderableWidget(label(listTop + 14, "Crée un dossier par playlist (avec des .mp3 dedans) ici :"));
            addRenderableWidget(label(listTop + 28, shortenStart(Library.root().toString(), 70)));
        } else if (selected != null && total == 0) {
            addRenderableWidget(label(listTop, "Aucun fichier .mp3 dans cette playlist."));
        }

        final int start = page * rows;
        final int end = Math.min(total, start + rows);
        for (int i = start; i < end; i++) {
            final int y = listTop + (i - start) * 22;
            final int idx = i;
            if (selected == null) {
                final Library.Playlist p = playlists.get(i);
                String mark = p.name().equals(player.playlistName()) ? "* " : "";
                String text = mark + shorten(p.name(), 34) + "  (" + p.trackCount() + ")";
                addRenderableWidget(Button.builder(Component.literal(text), b -> {
                    selected = p;
                    tracks = Library.tracks(p.dir());
                    page = 0;
                    rebuildWidgets();
                }).bounds(bx, y, bw, 20).build());
            } else {
                final Path file = tracks.get(i);
                String mark = player.isCurrent(selected.name(), i) ? "> " : "";
                String text = mark + shorten(Library.displayName(file), 44);
                addRenderableWidget(Button.builder(Component.literal(text), b -> {
                    player.startPlaylist(selected.name(), tracks, idx);
                    rebuildWidgets();
                }).bounds(bx, y, bw, 20).build());
            }
        }

        // Ligne d'état
        status = addRenderableWidget(label(statusY, ""));

        // Contrôles : précédent / lecture-pause / stop / suivant / volume - / volume +
        final int tw = 48;
        final int gap = 4;
        final int tx = (this.width - (6 * tw + 5 * gap)) / 2;
        addRenderableWidget(Button.builder(Component.literal("<<"), b -> {
            player.previous();
            refresh();
        }).bounds(tx, transportY, tw, 20).build());
        playPause = addRenderableWidget(Button.builder(Component.literal("Lire"), b -> {
            player.togglePause();
            refresh();
        }).bounds(tx + (tw + gap), transportY, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
            player.stop();
            refresh();
        }).bounds(tx + 2 * (tw + gap), transportY, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">>"), b -> {
            player.next();
            refresh();
        }).bounds(tx + 3 * (tw + gap), transportY, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Vol -"), b -> {
            player.adjustVolume(-10);
            refresh();
        }).bounds(tx + 4 * (tw + gap), transportY, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Vol +"), b -> {
            player.adjustVolume(10);
            refresh();
        }).bounds(tx + 5 * (tw + gap), transportY, tw, 20).build());

        // Navigation : page précédente / (Actualiser | Playlists) / page suivante / Fermer
        final int nx = (this.width - 252) / 2;
        Button prevPage = Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(nx, navY, 30, 20).build();
        prevPage.active = page > 0;
        addRenderableWidget(prevPage);

        if (selected == null) {
            addRenderableWidget(Button.builder(Component.literal("Actualiser"), b -> rebuildWidgets())
                    .bounds(nx + 34, navY, 90, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Playlists"), b -> {
                selected = null;
                tracks = List.of();
                page = 0;
                rebuildWidgets();
            }).bounds(nx + 34, navY, 90, 20).build());
        }

        Button nextPage = Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(nx + 128, navY, 30, 20).build();
        nextPage.active = page < pages - 1;
        addRenderableWidget(nextPage);

        addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose())
                .bounds(nx + 162, navY, 90, 20).build());

        refresh();
    }

    @Override
    public void removed() {
        if (current == this) current = null;
    }

    /** Appelé à chaque tick client tant que l'écran est ouvert. */
    public void refresh() {
        int idx = player.index();
        if (idx != shownIndex) {            // la piste a changé (passage automatique) : on remet les marqueurs à jour
            rebuildWidgets();
            return;
        }
        if (status == null || playPause == null) return;
        String text = statusText();
        int w = this.font.width(text);
        status.setWidth(Math.max(1, w));
        status.setX((this.width - w) / 2);
        status.setMessage(Component.literal(text));
        playPause.setMessage(Component.literal(player.state() == Mp3Player.State.PLAYING ? "Pause" : "Lire"));
    }

    // ------------------------------------------------------------------ utilitaires

    private int listTopHeaderY() {
        return 14;
    }

    private StringWidget label(int y, String text) {
        int w = this.font.width(text);
        return new StringWidget((this.width - w) / 2, y, Math.max(1, w), 10, Component.literal(text), this.font);
    }

    private String statusText() {
        String name = player.currentLabel();
        if (name.isEmpty()) return "Aucune lecture  -  Vol " + player.volumePct() + "%";
        String prefix = switch (player.state()) {
            case PLAYING -> "Lecture";
            case PAUSED -> "En pause";
            case STOPPED -> "Arrêté";
        };
        return prefix + " : " + shorten(name, 36) + " [" + fmt(player.positionMs()) + "]  -  Vol " + player.volumePct() + "%";
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 3)) + "...";
    }

    private static String shortenStart(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - (max - 3));
    }
}
