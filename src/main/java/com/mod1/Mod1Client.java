package com.mod1;

import com.mod1.audio.MediaKeys;
import com.mod1.audio.Mp3Player;
import com.mod1.ui.PlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mod_1 : lecteur MP3 totalement invisible tant qu'on n'appuie pas sur Ctrl+Maj+Alt+P.
 * Aucune touche n'est enregistrée dans le menu des contrôles, aucun HUD, aucun message.
 */
public final class Mod1Client implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("mod_1");
    private static final int CHORD = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT;

    private boolean hooked;

    @Override
    public void onInitializeClient() {
        Mp3Player.INSTANCE.loadState();
        MediaKeys.start();

        // On accroche le clavier au premier tick (la fenêtre est alors sûrement créée).
        ClientTickEvents.START_CLIENT_TICK.register(this::hookKeyboard);

        // Rafraîchit l'interface (temps écoulé, bouton Pause/Lire) quand elle est ouverte.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            PlayerScreen s = PlayerScreen.current();
            if (s != null) s.refresh();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            MediaKeys.stop();
            Mp3Player.INSTANCE.shutdown();
        });
    }

    private void hookKeyboard(Minecraft mc) {
        if (hooked) return;
        long handle = mc.getWindow().handle();
        if (handle == 0L) return;
        hooked = true;

        // On s'intercale devant le gestionnaire de clavier de Minecraft :
        // le raccourci est "avalé" (la touche P ne déclenche donc rien d'autre),
        // tout le reste est transmis tel quel à Minecraft.
        final GLFWKeyCallbackI[] previous = new GLFWKeyCallbackI[1];
        previous[0] = GLFW.glfwSetKeyCallback(handle, (window, key, scancode, action, mods) -> {
            if (key == GLFW.GLFW_KEY_P && (mods & CHORD) == CHORD) {
                if (action == GLFW.GLFW_PRESS) {
                    mc.execute(() -> toggleScreen(mc));
                }
                return;
            }
            GLFWKeyCallbackI prev = previous[0];
            if (prev != null) prev.invoke(window, key, scancode, action, mods);
        });
        LOG.info("mod_1 prêt.");
    }

    private static void toggleScreen(Minecraft mc) {
        PlayerScreen open = PlayerScreen.current();
        if (open != null) {
            open.onClose();
        } else {
            mc.setScreenAndShow(new PlayerScreen());
        }
    }
}
