package com.faklify.vlc;

import com.faklify.DTOs.PlaylistItem;
import static com.faklify.UI.UserInterface.updateUI;
import static com.faklify.services.PlayNext.playNext;
import static com.faklify.UI.UserInterface.updateTime;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.factory;
import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.lastPlayedTimes;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import com.faklify.interfaces.DirectKernel32;
import static com.faklify.methodsGenerics.Methods.log;
import java.io.File;
import javafx.application.Platform;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

public class Vlc {
    public static void initVLC() {
        try {
            // Configuración de JNA para modo portable
            System.setProperty("jna.nounpack", "true");
            System.setProperty("jna.nosys", "true");

            String appPath = System.getProperty("user.dir");
            File vlcFolder = new File(appPath, "vlc_runtime");
            String finalVlcPath = vlcFolder.getAbsolutePath();

            // FORZAR RUTA EN WINDOWS (Usando nuestra interfaz DirectKernel32)
            if (com.sun.jna.Platform.isWindows()) {
                log("🔗 Enlazando DLLs de Windows en: " + finalVlcPath);

                DirectKernel32.INSTANCE.SetDllDirectoryW(finalVlcPath);
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", finalVlcPath);
            }

            System.setProperty("jna.library.path", finalVlcPath);
            System.setProperty("VLC_PLUGIN_PATH", finalVlcPath + File.separator + "plugins");

            // Inicialización de VLCJ
            String[] vlcArgs = {
                "--no-video",
                "--plugin-path=" + finalVlcPath + File.separator + "plugins",
                "--network-caching=1500",
                "--quiet"
            };

            factory = new MediaPlayerFactory(vlcArgs);
            mediaPlayer = factory.mediaPlayers().newMediaPlayer();

            if (mediaPlayer != null) {
                mediaPlayer.audio().setVolume(50);
                setupVlcEvents();
                log("✅ ¡SÍ! VLC ha despertado.");
            }

        } catch (Throwable t) {
            log("❌ Error en el motor: " + t.getMessage());
            if (t.getCause() != null) {
                log("🔍 Causa: " + t.getCause().toString());
            }
            t.printStackTrace();
        }
    }
    
    private static void setupVlcEvents() {
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = true;
                    updateUI();

                    synchronized (PLAYLIST_LOCK) {
                        if (currentIndex >= 0 && currentIndex < playlist.size()) {
                            PlaylistItem item = playlist.get(currentIndex);
                            lastPlayedTimes.put(item.videoId, System.currentTimeMillis());
                        }
                    }
                });
            }

            @Override
            public void paused(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = false;
                    updateUI();
                });
            }

            @Override
            public void stopped(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = false;
                    savedPlaybackTime = 0;
                    updateUI();
                });
            }

            @Override
            public void finished(MediaPlayer mp) {
                Platform.runLater(() -> {
                    log("✅ Canción terminada");
                    savedPlaybackTime = 0;
                    playNext();
                });
            }

            @Override
            public void timeChanged(MediaPlayer mp, long newTime) {
                Platform.runLater(() -> updateTime(newTime));
            }

            @Override
            public void error(MediaPlayer mp) {
                Platform.runLater(() -> {
                    log("❌ Error reproducción");
                    savedPlaybackTime = 0;
                    playNext();
                });
            }
        });
    }
}
