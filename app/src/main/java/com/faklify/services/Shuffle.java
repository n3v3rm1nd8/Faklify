package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.DTOs.Video.isVideoFailed;
import static com.faklify.actionsPlayer.ActionsPlayer.findReplacementIndices;
import static com.faklify.actionsPlayer.ActionsPlayer.scheduleNextPreloads;
import com.faklify.attributesGenerics.Attributes;
import static com.faklify.attributesGenerics.Attributes.MAX_DOWNLOADS;
import static com.faklify.attributesGenerics.Attributes.PRELOAD_AHEAD;
import static com.faklify.attributesGenerics.Attributes.activeDownloads;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.random;
import static com.faklify.attributesGenerics.Attributes.shuffleBtn;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.shuffleOrder;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.DownloadVideo.downloadTrack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.application.Platform;

public class Shuffle {

    public static void generateShuffleOrder() {
        Attributes.shuffleOrder.clear();

        List<Integer> availableIndices = new ArrayList<>();
        for (int i = 0; i < Attributes.playlist.size(); i++) {
            if (i != Attributes.currentIndex && !isVideoFailed(Attributes.playlist.get(i).videoId)) {
                availableIndices.add(i);
            }
        }

        Collections.shuffle(availableIndices, Attributes.random);

        if (Attributes.currentIndex >= 0 && Attributes.currentIndex < Attributes.playlist.size()) {
            Attributes.shuffleOrder.add(Attributes.currentIndex);
        }

        Attributes.shuffleOrder.addAll(availableIndices);

        log("🔀 Orden aleatorio generado: " + Attributes.shuffleOrder.size() + " canciones");
    }

    public static int getNextShuffleIndex() {
        if (Attributes.shuffleOrder.isEmpty() || !Attributes.shuffleMode) {
            return -1;
        }

        int currentPos = -1;
        for (int i = 0; i < Attributes.shuffleOrder.size(); i++) {
            if (Attributes.shuffleOrder.get(i) == Attributes.currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos == -1 || currentPos >= Attributes.shuffleOrder.size() - 1) {
            generateShuffleOrder();

            if (Attributes.shuffleOrder.size() > 1) {
                return Attributes.shuffleOrder.get(1);
            } else {
                return -1;
            }
        }

        // Buscar siguiente canción que no sea fallida
        for (int i = currentPos + 1; i < Attributes.shuffleOrder.size(); i++) {
            int candidateIndex = Attributes.shuffleOrder.get(i);
            if (candidateIndex < Attributes.playlist.size()
                    && !isVideoFailed(Attributes.playlist.get(candidateIndex).videoId)) {
                return candidateIndex;
            }
        }

        // Si no hay más canciones no fallidas, regenerar orden
        generateShuffleOrder();
        if (Attributes.shuffleOrder.size() > 1) {
            return Attributes.shuffleOrder.get(1);
        }

        return -1;
    }
    
    public static List<Integer> getNextShuffleIndices(int count) {
        List<Integer> result = new ArrayList<>();

        if (!shuffleMode || shuffleOrder.isEmpty() || count <= 0) {
            return result;
        }

        int currentPos = -1;
        for (int i = 0; i < shuffleOrder.size(); i++) {
            if (shuffleOrder.get(i) == currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos == -1) {
            return result;
        }

        // Tomar las siguientes 'count' canciones que no sean fallidas
        int found = 0;
        for (int i = currentPos + 1; i < shuffleOrder.size() && found < count; i++) {
            int candidateIndex = shuffleOrder.get(i);
            if (candidateIndex < playlist.size() && !isVideoFailed(playlist.get(candidateIndex).videoId)) {
                result.add(candidateIndex);
                found++;
            }
        }

        // Si no encontramos suficientes, buscar reemplazos
        if (found < count) {
            List<Integer> replacements = findReplacementIndices(result, count - found, true);
            result.addAll(replacements);
        }

        return result;
    }
    
    public static int getPrevShuffleIndex() {
        if (shuffleOrder.isEmpty() || !shuffleMode) {
            return -1;
        }

        int currentPos = -1;
        for (int i = 0; i < shuffleOrder.size(); i++) {
            if (shuffleOrder.get(i) == currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos <= 0) {
            List<Integer> availableIndices = new ArrayList<>();
            for (int i = 0; i < playlist.size(); i++) {
                if (i != currentIndex && !isVideoFailed(playlist.get(i).videoId)) {
                    availableIndices.add(i);
                }
            }

            if (!availableIndices.isEmpty()) {
                return availableIndices.get(random.nextInt(availableIndices.size()));
            } else {
                return -1;
            }
        }

        // Buscar canción anterior que no sea fallida
        for (int i = currentPos - 1; i >= 0; i--) {
            int candidateIndex = shuffleOrder.get(i);
            if (candidateIndex < playlist.size() && !isVideoFailed(playlist.get(candidateIndex).videoId)) {
                return candidateIndex;
            }
        }

        return -1;
    }
    
    private static void immediateShufflePreload() {
        downloadExecutor.submit(() -> {
            try {
                List<Integer> preloadIndices = new ArrayList<>();

                if (shuffleOrder.size() > 1) {
                    int startPos = shuffleOrder.indexOf(currentIndex);
                    if (startPos != -1) {
                        for (int i = 1; i <= PRELOAD_AHEAD; i++) {
                            int nextPos = startPos + i;
                            if (nextPos < shuffleOrder.size()) {
                                preloadIndices.add(shuffleOrder.get(nextPos));
                            }
                        }
                    }
                }

                // Si no hay suficientes en el orden actual, buscar reemplazos
                if (preloadIndices.size() < PRELOAD_AHEAD) {
                    List<Integer> replacementIndices = findReplacementIndices(preloadIndices, PRELOAD_AHEAD - preloadIndices.size(), true);
                    preloadIndices.addAll(replacementIndices);
                }

                int preloads = 0;
                for (int preloadIndex : preloadIndices) {
                    if (preloadIndex >= 0 && preloadIndex < playlist.size()) {
                        PlaylistItem item = playlist.get(preloadIndex);

                        if (isVideoFailed(item.videoId)) {
                            log("⚠️ Canción aleatoria marcada como fallida, buscando reemplazo...");
                            continue;
                        }

                        Track track = trackCache.get(item.videoId);

                        if (track != null && !track.isFileValid()
                                && track.status != TrackStatus.DOWNLOADING
                                && activeDownloads.get() < MAX_DOWNLOADS) {

                            downloadTrack(item, preloadIndex, false);
                            preloads++;
                            Thread.sleep(300);
                        }
                    }
                }

                if (preloads > 0) {
                    log("⚡ Precarga inmediata: " + preloads + " canciones aleatorias");
                } else {
                    log("ℹ️ Las canciones aleatorias ya están en caché");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("⚠️ Error en precarga inmediata: " + e.getMessage());
            }
        });
    }
    
    public static void toggleShuffleMode() {
        shuffleMode = !shuffleMode;

        if (shuffleMode) {
            generateShuffleOrder();
            log("🔀 Modo aleatorio ACTIVADO");
            Platform.runLater(() -> {
                shuffleBtn.setText("🔀 ON");
                shuffleBtn.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white; -fx-font-weight: bold;");
            });

            if (currentIndex >= 0 && playlist.size() > 1) {
                log("🔄 Precargando inmediatamente 2 canciones aleatorias...");
                immediateShufflePreload();
            }

        } else {
            shuffleOrder.clear();
            log("🔀 Modo aleatorio DESACTIVADO");
            Platform.runLater(() -> {
                shuffleBtn.setText("🔀 OFF");
                shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");
            });

            if (currentIndex >= 0) {
                scheduleNextPreloads(currentIndex);
            }
        }
    }
}
