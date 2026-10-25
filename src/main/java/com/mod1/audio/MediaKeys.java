package com.mod1.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Touches média du clavier (Play/Pause, Stop, Suivant, Précédent).
 * GLFW (donc Minecraft) ne les voit pas : on interroge Windows directement (user32.GetAsyncKeyState).
 * Fonctionne même quand la fenêtre Minecraft n'est pas au premier plan. Windows uniquement.
 */
public final class MediaKeys {
    private static final Logger LOG = LoggerFactory.getLogger("mod_1");

    private static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    private static final int VK_MEDIA_PREV_TRACK = 0xB1;
    private static final int VK_MEDIA_STOP = 0xB2;
    private static final int VK_MEDIA_PLAY_PAUSE = 0xB3;

    private static volatile boolean running;

    private MediaKeys() {}

    public static void start() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            LOG.info("mod_1 : touches média globales disponibles sur Windows uniquement.");
            return;
        }
        final MethodHandle getAsyncKeyState;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            getAsyncKeyState = linker.downcallHandle(
                    user32.find("GetAsyncKeyState").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));
        } catch (Throwable t) {
            LOG.warn("mod_1 : touches média indisponibles", t);
            return;
        }
        running = true;
        Thread thread = new Thread(() -> poll(getAsyncKeyState), "mod_1-mediakeys");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running = false;
    }

    private static void poll(MethodHandle getAsyncKeyState) {
        final int[] vks = {VK_MEDIA_PREV_TRACK, VK_MEDIA_PLAY_PAUSE, VK_MEDIA_STOP, VK_MEDIA_NEXT_TRACK};
        final Mp3Player.MediaKey[] actions = {
                Mp3Player.MediaKey.PREVIOUS, Mp3Player.MediaKey.PLAY_PAUSE,
                Mp3Player.MediaKey.STOP, Mp3Player.MediaKey.NEXT};
        final boolean[] down = new boolean[vks.length];

        while (running) {
            try {
                for (int i = 0; i < vks.length; i++) {
                    short state = (short) getAsyncKeyState.invokeExact(vks[i]);
                    boolean now = (state & 0x8000) != 0;
                    if (now && !down[i]) {
                        Mp3Player.INSTANCE.onMediaKey(actions[i]);
                    }
                    down[i] = now;
                }
                Thread.sleep(12);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.warn("mod_1 : arrêt de l'écoute des touches média", t);
                return;
            }
        }
    }
}
