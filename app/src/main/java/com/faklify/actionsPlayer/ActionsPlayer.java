package com.faklify.actionsPlayer;

import static com.faklify.attributesGenerics.Attributes.AUDIO_FORMAT;
import static com.faklify.attributesGenerics.Attributes.MAX_DOWNLOADS;
import static com.faklify.attributesGenerics.Attributes.MIN_FILE_SIZE;
import static com.faklify.attributesGenerics.Attributes.PRELOAD_AHEAD;
import static com.faklify.attributesGenerics.Attributes.activeDownloads;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.programmaticSelection;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.random;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.attributesGenerics.Attributes.trackListView;
import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.DTOs.Video.isVideoFailed;
import com.faklify.Faklify;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.filesCache.FileCache.saveCurrentSession;
import static com.faklify.methodsGenerics.Methods.showDownloadInProgressAlert;
import static com.faklify.services.DownloadVideo.downloadTrack;
import static com.faklify.services.DownloadVideo.isDownloadInProgress;
import static com.faklify.services.PlayCurrent.playCurrent;
import static com.faklify.services.Shuffle.getNextShuffleIndices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.application.Platform;

public class ActionsPlayer {

    public static void updateTrackList() {
        Platform.runLater(() -> {
            if (trackListView == null) {
                return;
            }
            programmaticSelection = true;

            trackListView.getItems().clear();
            for (int i = 0; i < playlist.size(); i++) {
                PlaylistItem item = playlist.get(i);
                Track track = trackCache.get(item.videoId);

                String prefix;
                if (track == null) {
                    prefix = "❓ ";
                } else if (track.isFileValid()) {
                    prefix = "✅ ";
                } else if (track.status == TrackStatus.DOWNLOADING) {
                    prefix = "⏳ ";
                } else {
                    prefix = "❌ ";
                }

                String title = (track != null && track.title != null) ? track.title : item.title;

                if (i == currentIndex) {
                    if (shuffleMode) {
                        title = "🎵 " + title + " 🔀";
                    } else {
                        title = "🎵 " + title;
                    }
                }

                trackListView.getItems().add(prefix + title);
            }

            if (currentIndex >= 0 && currentIndex < trackListView.getItems().size()) {
                trackListView.getSelectionModel().select(currentIndex);
            }

            programmaticSelection = false;
        });
    }

    public static void handleTrackSelection(int index) {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            // Opcional: Re-seleccionar el currentIndex anterior para que el foco no se mueva visualmente si cancelamos
            return;
        }

        if (index == currentIndex && mediaPlayer != null && mediaPlayer.status().isPlaying()) {
            log("ℹ️ Ya reproduciendo esta canción");
            return;
        }

        final int selectedIndex = index;
        PlaylistItem item = playlist.get(selectedIndex);

        if (isVideoFailed(item.videoId)) {
            log("❌ Esta canción fue marcada como fallida anteriormente");
            return;
        }

        log("🎯 Seleccionada: " + item.title + " (índice " + selectedIndex + ")");

        Track track = trackCache.get(item.videoId);

        synchronized (PLAYLIST_LOCK) {
            currentIndex = selectedIndex;
        }

        // Guardar sesión para que si cerramos la app tras hacer click, se acuerde
        saveCurrentSession();

        if (track != null && track.isFileValid()) {
            playCurrent();
        } else {
            downloadExecutor.submit(() -> {
                try {
                    Thread.sleep(100);
                    downloadTrack(item, selectedIndex, true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    public static void updateTracksWithCacheInfo() {
        for (PlaylistItem item : playlist) {
            Track track = trackCache.get(item.videoId);
            if (track != null) {
                if (track.status == TrackStatus.DOWNLOADED) {
                    if (!track.isFileValid()) {
                        track.status = TrackStatus.NOT_DOWNLOADED;
                        track.filePath = null;
                    }
                } else {
                    try {
                        Path expectedFile = cacheDir.resolve(item.videoId + "." + AUDIO_FORMAT);
                        if (Files.exists(expectedFile) && Files.size(expectedFile) > MIN_FILE_SIZE) {
                            track.filePath = expectedFile;
                            track.fileSize = Files.size(expectedFile);
                            track.status = TrackStatus.DOWNLOADED;
                        }
                    } catch (IOException ex) {
                        System.getLogger(Faklify.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                    }
                }
            }
        }
    }

    public static void scheduleNextPreloads(int currentIdx) {
        scheduleNextPreloadsWithReplacement(currentIdx, 0);
    }

    public static void scheduleNextPreloadsWithReplacement(int currentIdx, int extraReplacements) {
        downloadExecutor.submit(() -> {
            try {
                final int idx = currentIdx;

                int preloads = 0;
                List<Integer> indicesToPreload = new ArrayList<>();
                List<Integer> failedDuringPreload = new ArrayList<>();

                if (shuffleMode) {
                    indicesToPreload = getNextShuffleIndices(PRELOAD_AHEAD + extraReplacements);
                } else {
                    // Tomar las siguientes canciones en orden
                    for (int i = 1; i <= PRELOAD_AHEAD + extraReplacements; i++) {
                        int nextIdx = idx + i;
                        if (nextIdx < playlist.size()) {
                            indicesToPreload.add(nextIdx);
                        }
                    }
                }

                for (int nextIdx : indicesToPreload) {
                    if (nextIdx < playlist.size()) {
                        PlaylistItem nextItem = playlist.get(nextIdx);

                        // Verificar que no sea un video fallido
                        if (isVideoFailed(nextItem.videoId)) {
                            log("⚠️ Canción para precarga marcada como fallida, buscando reemplazo...");
                            failedDuringPreload.add(nextIdx);
                            continue;
                        }

                        Track nextTrack = trackCache.get(nextItem.videoId);

                        synchronized (PLAYLIST_LOCK) {
                            if (currentIndex != idx) {
                                log("⚠️ Cancelar precarga: usuario cambió de canción");
                                return;
                            }

                            if (nextIdx == currentIndex) {
                                continue;
                            }
                        }

                        if (nextTrack != null && !nextTrack.isFileValid()
                                && nextTrack.status != TrackStatus.DOWNLOADING
                                && activeDownloads.get() < MAX_DOWNLOADS) {

                            downloadTrack(nextItem, nextIdx, false);
                            preloads++;
                            Thread.sleep(300);
                        }
                    }
                }

                // Si hubo canciones fallidas durante la precarga, buscar reemplazos
                if (!failedDuringPreload.isEmpty()) {
                    log("🔄 Buscando " + failedDuringPreload.size() + " reemplazos para canciones fallidas");
                    List<Integer> replacementIndices = findReplacementIndices(indicesToPreload, failedDuringPreload.size(), shuffleMode);

                    for (int replacementIdx : replacementIndices) {
                        if (replacementIdx < playlist.size()) {
                            PlaylistItem replacementItem = playlist.get(replacementIdx);

                            if (isVideoFailed(replacementItem.videoId)) {
                                continue;
                            }

                            Track replacementTrack = trackCache.get(replacementItem.videoId);

                            synchronized (PLAYLIST_LOCK) {
                                if (currentIndex != idx) {
                                    log("⚠️ Cancelar precarga de reemplazo: usuario cambió de canción");
                                    return;
                                }
                            }

                            if (replacementTrack != null && !replacementTrack.isFileValid()
                                    && replacementTrack.status != TrackStatus.DOWNLOADING
                                    && activeDownloads.get() < MAX_DOWNLOADS) {

                                downloadTrack(replacementItem, replacementIdx, false);
                                preloads++;
                                Thread.sleep(300);
                            }
                        }
                    }
                }

                if (preloads > 0) {
                    String mode = shuffleMode ? "aleatorias" : "siguientes";
                    if (failedDuringPreload.size() > 0) {
                        log("🔄 Precargando " + preloads + " canciones " + mode + " (incluyendo " + failedDuringPreload.size() + " reemplazos)");
                    } else {
                        log("🔄 Precargando " + preloads + " canciones " + mode);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // Método para encontrar índices de reemplazo cuando una canción es fallida
    public static List<Integer> findReplacementIndices(List<Integer> currentIndices, int countNeeded, boolean shuffleMode) {
        List<Integer> replacements = new ArrayList<>();

        if (countNeeded <= 0) {
            return replacements;
        }

        List<Integer> allPossibleIndices = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            // No incluir: índice actual, ya incluidos, o videos fallidos
            if (i != currentIndex
                    && !currentIndices.contains(i)
                    && !isVideoFailed(playlist.get(i).videoId)) {
                allPossibleIndices.add(i);
            }
        }

        if (shuffleMode) {
            // En modo aleatorio, mezclar aleatoriamente
            Collections.shuffle(allPossibleIndices, random);
        } else {
            // En modo normal, tomar los siguientes en orden
            Collections.sort(allPossibleIndices);
        }

        // Tomar los primeros 'countNeeded' disponibles
        for (int i = 0; i < Math.min(countNeeded, allPossibleIndices.size()); i++) {
            replacements.add(allPossibleIndices.get(i));
        }

        return replacements;
    }
}
