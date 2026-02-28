package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import static com.faklify.DTOs.Video.isVideoFailed;
import static com.faklify.UI.focus.Focus.aplicarFocoYScroll;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.methodsGenerics.Methods.showDownloadInProgressAlert;
import static com.faklify.services.DownloadVideo.downloadTrack;
import static com.faklify.services.DownloadVideo.isDownloadInProgress;
import static com.faklify.services.PlayCurrent.playCurrent;
import static com.faklify.services.Shuffle.getPrevShuffleIndex;

public class PlayPrev {
    public static void playPrev() {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        int calculoIndice;
        if (shuffleMode) {
            calculoIndice = getPrevShuffleIndex();

            // Si el modo aleatorio no tiene una anterior (inicio del shuffle), 
            // podrías dejarlo ahí o saltar al final del orden aleatorio. 
            // Normalmente en shuffle se vuelve al último del historial.
            if (calculoIndice == -1) {
                log("ℹ️ Inicio del historial aleatorio.");
                return;
            }
        } else {
            // MODO NORMAL:
            if (currentIndex > 0) {
                calculoIndice = currentIndex - 1;
            } else {
                // Si estamos en la primera, saltamos a la última canción
                log("🔁 Volviendo al final de la lista...");
                calculoIndice = playlist.size() - 1;
            }
        }

        // Declaramos la variable FINAL para el lambda
        final int prevIndexFinal = calculoIndice;

        if (prevIndexFinal >= 0 && prevIndexFinal < playlist.size()) {
            PlaylistItem item = playlist.get(prevIndexFinal);

            if (isVideoFailed(item.videoId)) {
                log("⚠️ Canción anterior está marcada como fallida, saltando...");
                currentIndex = prevIndexFinal;
                playPrev();
                return;
            }

            // Foco y Scroll al nuevo índice
            currentIndex = prevIndexFinal;
            aplicarFocoYScroll(currentIndex);

            Track track = trackCache.get(item.videoId);
            savedPlaybackTime = 0; // Reset de tiempo para evitar el error anterior

            if (track != null && track.isFileValid()) {
                playCurrent();
            } else {
                downloadExecutor.submit(() -> {
                    try {
                        Thread.sleep(100);
                        // Usamos la variable constante aquí
                        downloadTrack(item, prevIndexFinal, true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }
}
