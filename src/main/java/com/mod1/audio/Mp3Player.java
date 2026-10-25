package com.mod1.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Lecteur MP3 (JLayer + javax.sound). Tourne dans son propre thread, indépendant du jeu.
 * Retient la position quand on arrête (Stop) et la sauvegarde sur disque.
 */
public final class Mp3Player {
    public static final Mp3Player INSTANCE = new Mp3Player();
    private static final Logger LOG = LoggerFactory.getLogger("mod_1");

    public enum State { STOPPED, PLAYING, PAUSED }

    public enum MediaKey { PLAY_PAUSE, STOP, NEXT, PREVIOUS }

    private String playlistName = "";
    private List<Path> tracks = List.of();
    private int index = -1;
    private long resumeMs = 0;
    private volatile int volumePct = 80;
    private State state = State.STOPPED;
    private Worker worker;
    private boolean touched;          // quelque chose a été lancé pendant cette session
    private int consecutiveErrors;

    private Mp3Player() {}

    // ------------------------------------------------------------------ lecture d'état

    public synchronized State state() { return state; }

    public synchronized int index() { return index; }

    public synchronized String playlistName() { return playlistName; }

    public int volumePct() { return volumePct; }

    public synchronized boolean isCurrent(String playlist, int trackIndex) {
        return index == trackIndex && playlistName.equals(playlist);
    }

    public synchronized long positionMs() {
        return worker != null ? worker.heardMs() : resumeMs;
    }

    public synchronized String currentLabel() {
        if (index < 0 || index >= tracks.size()) return "";
        return Library.displayName(tracks.get(index));
    }

    // ------------------------------------------------------------------ commandes

    /** Lance une piste d'une playlist (depuis le début). */
    public synchronized void startPlaylist(String name, List<Path> list, int idx) {
        if (list.isEmpty()) return;
        playlistName = name;
        tracks = List.copyOf(list);
        consecutiveErrors = 0;
        playIndex(idx, 0);
    }

    /** Lecture <-> Pause ; si arrêté, reprend là où on s'était arrêté. */
    public synchronized void togglePause() {
        switch (state) {
            case PLAYING -> {
                worker.setPaused(true);
                resumeMs = worker.heardMs();
                state = State.PAUSED;
                saveState();
            }
            case PAUSED -> {
                worker.setPaused(false);
                state = State.PLAYING;
            }
            case STOPPED -> {
                if (index >= 0 && !tracks.isEmpty()) {
                    consecutiveErrors = 0;
                    playIndex(index, resumeMs);
                }
            }
        }
    }

    /** Arrête la lecture mais retient la position. */
    public synchronized void stop() {
        if (worker == null) return;
        resumeMs = worker.heardMs();
        cancelWorker();
        state = State.STOPPED;
        saveState();
    }

    public synchronized void next() {
        if (tracks.isEmpty()) return;
        consecutiveErrors = 0;
        playIndex(index + 1, 0);
    }

    public synchronized void previous() {
        if (tracks.isEmpty()) return;
        consecutiveErrors = 0;
        playIndex(positionMs() > 3000 ? index : index - 1, 0);
    }

    public synchronized void adjustVolume(int delta) {
        volumePct = Math.max(0, Math.min(100, volumePct + delta));
        saveState();
    }

    /** Touches média : ignorées tant que rien n'a été lancé pendant cette session. */
    public synchronized void onMediaKey(MediaKey key) {
        if (!touched) return;
        switch (key) {
            case PLAY_PAUSE -> togglePause();
            case STOP -> stop();
            case NEXT -> next();
            case PREVIOUS -> previous();
        }
    }

    public synchronized void shutdown() {
        if (worker != null) {
            resumeMs = worker.heardMs();
            cancelWorker();
            state = State.STOPPED;
        }
        saveState();
    }

    // ------------------------------------------------------------------ interne

    private void playIndex(int idx, long fromMs) {
        cancelWorker();
        if (tracks.isEmpty()) {
            state = State.STOPPED;
            return;
        }
        index = Math.floorMod(idx, tracks.size());
        resumeMs = fromMs;
        touched = true;
        Worker w = new Worker(tracks.get(index), fromMs);
        worker = w;
        state = State.PLAYING;
        w.start();
        saveState();
    }

    private void cancelWorker() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
    }

    private synchronized void onTrackEnded(Worker w, boolean ok) {
        if (w != worker) return;               // annulé ou remplacé entre-temps
        consecutiveErrors = ok ? 0 : consecutiveErrors + 1;
        if (consecutiveErrors >= Math.max(1, tracks.size())) {
            worker = null;
            state = State.STOPPED;
            resumeMs = 0;
            saveState();
            return;
        }
        playIndex(index + 1, 0);
    }

    private synchronized void checkpoint(Worker w) {
        if (w == worker && state == State.PLAYING) saveState();
    }

    // ------------------------------------------------------------------ sauvegarde

    public synchronized void loadState() {
        Path f = Library.stateFile();
        if (!Files.isRegularFile(f)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        try {
            volumePct = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("volume", "80"))));
        } catch (NumberFormatException ignored) {
            // garde la valeur par défaut
        }
        String pl = p.getProperty("playlist", "");
        String tr = p.getProperty("track", "");
        if (pl.isEmpty() || tr.isEmpty()) return;
        Path dir = Library.root().resolve(pl);
        if (!Files.isDirectory(dir)) return;
        List<Path> list = Library.tracks(dir);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getFileName().toString().equals(tr)) {
                playlistName = pl;
                tracks = list;
                index = i;
                try {
                    resumeMs = Math.max(0, Long.parseLong(p.getProperty("positionMs", "0")));
                } catch (NumberFormatException ignored) {
                    resumeMs = 0;
                }
                break;
            }
        }
    }

    private void saveState() {
        try {
            Properties p = new Properties();
            p.setProperty("playlist", playlistName);
            if (index >= 0 && index < tracks.size()) {
                p.setProperty("track", tracks.get(index).getFileName().toString());
            }
            p.setProperty("positionMs", Long.toString(positionMs()));
            p.setProperty("volume", Integer.toString(volumePct));
            Path f = Library.stateFile();
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "mod_1");
            }
        } catch (IOException e) {
            LOG.warn("mod_1 : impossible de sauvegarder l'état", e);
        }
    }

    // ------------------------------------------------------------------ thread audio

    private final class Worker extends Thread {
        private final Path file;
        private final long startMs;
        private volatile boolean cancelled;
        private volatile boolean paused;
        private volatile SourceDataLine line;
        private volatile int rate;
        private int channels;

        Worker(Path file, long startMs) {
            super("mod_1-audio");
            setDaemon(true);
            this.file = file;
            this.startMs = startMs;
        }

        /** Position réellement entendue (et pas seulement décodée). */
        long heardMs() {
            SourceDataLine l = line;
            int r = rate;
            if (l == null || r == 0) return startMs;
            try {
                return startMs + l.getLongFramePosition() * 1000L / r;
            } catch (RuntimeException e) {
                return startMs;
            }
        }

        void setPaused(boolean p) {
            paused = p;
            SourceDataLine l = line;
            if (l != null) {
                try {
                    if (p) l.stop(); else l.start();
                } catch (RuntimeException ignored) {
                    // ligne déjà fermée
                }
            }
        }

        void cancel() {
            cancelled = true;
            SourceDataLine l = line;
            if (l != null) {
                try {
                    l.stop();
                    l.flush();
                } catch (RuntimeException ignored) {
                    // ligne déjà fermée
                }
            }
        }

        @Override
        public void run() {
            boolean ok = false;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
                Bitstream bs = new Bitstream(in);
                Decoder decoder = new Decoder();

                // Reprise : on saute jusqu'à startMs (en décodant les ~300 dernières ms pour éviter un "clic").
                double pos = 0;
                double warm = Math.max(0, startMs - 300);
                Header h = bs.readFrame();
                while (h != null && pos < startMs && !cancelled) {
                    float ms = h.ms_per_frame();
                    if (pos + ms > warm) decoder.decodeFrame(h, bs);
                    pos += ms;
                    bs.closeFrame();
                    h = bs.readFrame();
                }

                long framesWritten = 0;
                int sinceSave = 0;
                while (h != null && !cancelled) {
                    SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bs);
                    if (line == null) openLine(decoder);

                    while (paused && !cancelled) Thread.sleep(20);
                    if (cancelled) break;

                    short[] samples = sb.getBuffer();
                    int n = sb.getBufferLength();
                    float vol = volumePct / 100f;
                    float gain = vol * vol;                 // courbe plus naturelle qu'un gain linéaire
                    byte[] out = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        int v = Math.round(samples[i] * gain);
                        short s16 = (short) Math.max(-32768, Math.min(32767, v));
                        out[2 * i] = (byte) s16;
                        out[2 * i + 1] = (byte) (s16 >> 8);
                    }
                    line.write(out, 0, out.length);
                    framesWritten += n / channels;

                    if (++sinceSave >= 400) {               // ~10 s : sauvegarde de sécurité
                        sinceSave = 0;
                        checkpoint(this);
                    }
                    bs.closeFrame();
                    h = bs.readFrame();
                }

                // Fin du fichier : on laisse finir ce qui est dans le tampon audio.
                if (!cancelled && line != null) {
                    int waited = 0;
                    while (!cancelled && line.getLongFramePosition() < framesWritten && waited < 3000) {
                        Thread.sleep(15);
                        if (!paused) waited += 15;
                    }
                }
                bs.close();
                ok = true;
            } catch (Exception e) {
                if (!cancelled) LOG.warn("mod_1 : lecture impossible : {}", file, e);
            } finally {
                closeLine();
            }
            if (!cancelled) onTrackEnded(this, ok);
        }

        private void openLine(Decoder decoder) throws Exception {
            rate = decoder.getOutputFrequency();
            channels = decoder.getOutputChannels();
            AudioFormat fmt = new AudioFormat(rate, 16, channels, true, false);
            SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
            l.open(fmt, Math.max(4096, rate * channels * 2 / 4));   // ~250 ms de tampon
            line = l;
            if (!paused) l.start();
        }

        private void closeLine() {
            SourceDataLine l = line;
            if (l != null) {
                try {
                    l.stop();
                    l.flush();
                    l.close();
                } catch (RuntimeException ignored) {
                    // déjà fermée
                }
            }
        }
    }
}
