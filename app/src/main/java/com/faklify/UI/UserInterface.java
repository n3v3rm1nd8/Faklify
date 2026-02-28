package com.faklify.UI;

import com.faklify.attributesGenerics.Attributes;
import static com.faklify.attributesGenerics.Attributes.cacheInfoLabel;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.lastUiUpdateTime;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.playProgress;
import static com.faklify.attributesGenerics.Attributes.seekSlider;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.methodsGenerics.Methods.formatTime;
import javafx.application.Platform;

public class UserInterface {
    
    public static void updateTime(long currentTime) {
        // Solo actualizamos la UI cada 500ms, no cada vez que VLC mande el evento (ahorra CPU)
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateTime < 500) {
            return;
        }
        lastUiUpdateTime = now;

        Platform.runLater(() -> {
            if (mediaPlayer == null || !isPlaying) {
                return;
            }
            long totalTime = mediaPlayer.status().length();
            if (totalTime > 0) {
                double progress = (double) currentTime / totalTime;
                playProgress.setProgress(progress);
                if (!seekSlider.isValueChanging()) {
                    seekSlider.setValue(progress * 100);
                }
                timeLabel.setText(formatTime(currentTime) + " / " + formatTime(totalTime));
            }
        });
    }

    public static void updateStats() {
        Platform.runLater(() -> {
            int downloaded = 0;
            int downloading = 0;

            for (Track track : trackCache.values()) {
                if (track.isFileValid()) {
                    downloaded++;
                }
                if (track.status == TrackStatus.DOWNLOADING) {
                    downloading++;
                }
            }

            cacheInfoLabel.setText(String.format(
                    "Canciones (%d):",
                    playlist.size()
            ));
        });
    }

    public static void updateUI() {
        Platform.runLater(() -> {
            boolean hasSelection = Attributes.currentIndex >= 0 && Attributes.currentIndex
                    < Attributes.playlist.size();

            // Habilitar/Deshabilitar Play/Pause según si hay algo seleccionado
            Attributes.playPauseBtn.setDisable(!hasSelection);

            // Los botones Next y Prev solo se deshabilitan si la lista tiene 0 o 1 canción.
            // Si hay 2 o más, siempre puedes ir hacia adelante o hacia atrás (aunque sea la misma).
            boolean canNavigate = Attributes.playlist.size() > 1;

            Attributes.nextBtn.setDisable(!canNavigate);
            Attributes.prevBtn.setDisable(!canNavigate);

            // Actualizar el texto y estilo del botón Play/Pause
            if (Attributes.isPlaying) {
                Attributes.playPauseBtn.setText("⏸");
                Attributes.playPauseBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");
            } else {
                Attributes.playPauseBtn.setText("▶");
                Attributes.playPauseBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");
            }
        });
    }
}
