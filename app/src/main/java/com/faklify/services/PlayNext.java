package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import static com.faklify.DTOs.Video.isVideoFailed;
import static com.faklify.services.PlayCurrent.playCurrent;
import static com.faklify.UI.focus.Focus.aplicarFocoYScroll;
import com.faklify.attributesGenerics.Attributes;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.methodsGenerics.Methods.showDownloadInProgressAlert;
import static com.faklify.services.DownloadVideo.downloadTrack;
import static com.faklify.services.DownloadVideo.isDownloadInProgress;
import static com.faklify.services.Shuffle.generateShuffleOrder;
import static com.faklify.services.Shuffle.getNextShuffleIndex;

public class PlayNext {
    public static void playNext() {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        int calculoIndice; // Variable temporal para el cálculo
        if (Attributes.shuffleMode) {
            calculoIndice = getNextShuffleIndex();

            // Si el modo aleatorio se agota, reiniciamos el orden
            if (calculoIndice == -1) {
                log("🔀 Reiniciando ciclo aleatorio...");
                generateShuffleOrder();
                calculoIndice = getNextShuffleIndex();
            }
        } else {
            // MODO NORMAL:
            if (Attributes.currentIndex < Attributes.playlist.size() - 1) {
                calculoIndice = Attributes.currentIndex + 1;
            } else {
                // Si llegamos al final, volvemos al inicio (Loop de lista)
                log("🔁 Fin de la lista alcanzado. Volviendo al inicio...");
                calculoIndice = 0;
            }
        }

        // Creamos la variable FINAL para que el Lambda pueda usarla
        final int nextIndexFinal = calculoIndice;

        if (nextIndexFinal >= 0 && nextIndexFinal < Attributes.playlist.size()) {
            PlaylistItem item = Attributes.playlist.get(nextIndexFinal);

            if (isVideoFailed(item.videoId)) {
                log("⚠️ Siguiente canción está marcada como fallida, saltando...");
                Attributes.currentIndex = nextIndexFinal;
                playNext();
                return;
            }

            // Actualización de estado y UI
            Attributes.currentIndex = nextIndexFinal;
            aplicarFocoYScroll(Attributes.currentIndex);

            Track track = Attributes.trackCache.get(item.videoId);
            Attributes.savedPlaybackTime = 0;

            if (track != null && track.isFileValid()) {
                playCurrent();
            } else {
                // Ahora pasamos la variable FINAL al executor
                Attributes.downloadExecutor.submit(() -> {
                    try {
                        Thread.sleep(100);
                        // Usamos la constante aquí
                        downloadTrack(item, nextIndexFinal, true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } else {
            log("🏁 Fin de la lista (Lista vacía)");
        }
    }
}
