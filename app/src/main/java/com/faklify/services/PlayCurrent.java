package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.DTOs.Video.isVideoFailed;
import com.faklify.UI.UserInterface;
import static com.faklify.UI.UserInterface.updateUI;
import static com.faklify.actionsPlayer.ActionsPlayer.scheduleNextPreloads;
import com.faklify.attributesGenerics.Attributes;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.nowPlayingLabel;
import static com.faklify.attributesGenerics.Attributes.playPauseBtn;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.programmaticSelection;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.seekSlider;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.attributesGenerics.Attributes.trackListView;
import static com.faklify.filesCache.FileCache.saveCurrentSession;
import static com.faklify.methodsGenerics.Methods.formatTime;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.DownloadVideo.downloadTrack;
import static com.faklify.services.PlayNext.playNext;
import static com.faklify.vlc.Vlc.initVLC;
import javafx.application.Platform;

public class PlayCurrent {
    public static void playCurrent() {
        // Antes de guardar la sesión, verificamos si ya hay algo cargado en el reproductor.
        // Si mediaPlayer no es null y tiene un medio cargado (seekable), 
        // significa que estamos cambiando de canción, no abriendo la app.
        boolean esCambioDeCancionInterno = (mediaPlayer != null && mediaPlayer.status().isSeekable());

        saveCurrentSession();

        // Si es un cambio manual dentro de la app, reseteamos el tiempo guardado 
        // para que no "contamine" a la nueva canción.
        if (esCambioDeCancionInterno) {
            savedPlaybackTime = 0;
        }

        if (mediaPlayer == null) {
            log("❌ Error: El reproductor VLC no se pudo inicializar. Revisa los logs de inicio.");
            initVLC();
            if (mediaPlayer == null) {
                return;
            }
        }

        synchronized (PLAYLIST_LOCK) {
            if (currentIndex < 0 || currentIndex >= playlist.size()) {
                log("⚠️ Índice inválido: " + currentIndex);
                return;
            }
        }

        PlaylistItem item;
        synchronized (PLAYLIST_LOCK) {
            item = playlist.get(currentIndex);
        }

        if (isVideoFailed(item.videoId)) {
            log("❌ No se puede reproducir: canción marcada como fallida");
            playNext();
            return;
        }

        Track track = trackCache.get(item.videoId);

        if (track == null || !track.isFileValid()) {
            log("❌ No se puede reproducir: archivo no válido para: " + item.title);
            if (track == null || track.status != TrackStatus.DOWNLOADING) {
                log("⬇️ Iniciando descarga para reproducción: " + item.title);
                downloadTrack(item, currentIndex, true);
            }
            return;
        }

        try {
            String currentMediaPath = null;
            if (mediaPlayer.status().isPlaying() || mediaPlayer.status().isSeekable()) {
                try {
                    currentMediaPath = mediaPlayer.media().info().mrl();
                } catch (Exception e) {
                    // Silencioso
                }
            }

            String newMediaPath = track.filePath.toUri().toString();

            if (currentMediaPath == null || !currentMediaPath.equals(newMediaPath)
                    || !mediaPlayer.status().isPlaying()) {

                log("▶️ Iniciando reproducción: " + track.title);

                // Detenemos cualquier rastro de la canción anterior
                mediaPlayer.controls().stop();

                if (savedPlaybackTime > 0) {
                    // Este bloque SOLO se ejecutará cuando se restaura la sesión al abrir la App
                    mediaPlayer.media().start(newMediaPath);
                    mediaPlayer.controls().setTime(savedPlaybackTime);
                    log("↪️ Restaurando sesión previa en: " + formatTime(savedPlaybackTime));

                    // Limpiamos para que la siguiente canción ya no use este tiempo
                    savedPlaybackTime = 0;
                } else {
                    // Comportamiento estándar: limpieza total y empezar desde el principio
                    mediaPlayer.controls().setTime(0);
                    mediaPlayer.controls().setPosition(0.0f);
                    mediaPlayer.media().play(newMediaPath);
                }

                Platform.runLater(() -> {
                    nowPlayingLabel.setText("▶ " + track.title + (shuffleMode ? " 🔀" : ""));
                    playPauseBtn.setText("⏸");
                    playPauseBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");

                    // Reset visual inmediato de la barra y el tiempo
                    seekSlider.setValue(0);
                    timeLabel.setText("00:00");

                    if (!programmaticSelection && currentIndex >= 0
                            && currentIndex < trackListView.getItems().size()) {
                        trackListView.getSelectionModel().select(currentIndex);
                    }

                    updateUI();
                });

                scheduleNextPreloads(currentIndex);

            } else {
                log("ℹ️ Ya se está reproduciendo: " + track.title);
                Platform.runLater(() -> {
                    nowPlayingLabel.setText("▶ " + track.title + (shuffleMode ? " 🔀" : ""));
                    updateUI();
                });
            }

        } catch (Exception e) {
            log("❌ Error en playCurrent: " + e.getMessage());
            if (track != null) {
                track.status = TrackStatus.NOT_DOWNLOADED;
                log("⚠️ Archivo marcado como inválido, reintentando descarga...");
                downloadTrack(item, currentIndex, true);
            }
        }
    }
    
    public static void togglePlayPause() {
        if (playlist.isEmpty()) {
            return;
        }

        if (mediaPlayer.status().isPlaying()) {
            // Si está sonando, pausamos y guardamos el tiempo por seguridad
            savedPlaybackTime = mediaPlayer.status().time();
            mediaPlayer.controls().pause();
            isPlaying = false;
            log("⏸ Pausado en: " + (Attributes.savedPlaybackTime / 1000) + "s");
        } else {
            // Si NO está sonando, primero vemos si hay un medio ya cargado
            if (Attributes.mediaPlayer.status().isPlayable()) {
                // Si el medio ya existe en el motor de VLC, solo damos 'play'
                Attributes.mediaPlayer.controls().play();
                Attributes.isPlaying = true;
                log("▶ Reanudando desde: " + (Attributes.mediaPlayer.status().time() / 1000) + "s");
            } else {
                // Si no había nada cargado (primera vez o cambio de canción), usamos playCurrent
                playCurrent();
            }
        }
        UserInterface.updateUI();
    }
}
