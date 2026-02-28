package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import static com.faklify.DTOs.Video.saveFailedVideoToFile;
import static com.faklify.UI.UserInterface.updateStats;
import static com.faklify.UI.UserInterface.updateUI;
import static com.faklify.actionsPlayer.ActionsPlayer.scheduleNextPreloadsWithReplacement;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.attributesGenerics.Attributes.SESSION_FILE;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.cleanupExecutor;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.factory;
import static com.faklify.attributesGenerics.Attributes.failedVideos;
import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.monitorExecutor;
import static com.faklify.attributesGenerics.Attributes.nowPlayingLabel;
import static com.faklify.attributesGenerics.Attributes.playPauseBtn;
import static com.faklify.attributesGenerics.Attributes.playProgress;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.seekSlider;
import static com.faklify.attributesGenerics.Attributes.shuffleBtn;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.shuffleOrder;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.attributesGenerics.Attributes.trackListView;
import static com.faklify.filesCache.FileCache.cleanupVlcTempFiles;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.methodsGenerics.Methods.showAlert;
import static com.faklify.methodsGenerics.Methods.showDownloadInProgressAlert;
import static com.faklify.services.DownloadVideo.isDownloadInProgress;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

public class Cleaning {
    
    /**
     * Elimina una canción fallida y limpia archivos asociados (BÚSQUEDA POR
     * VIDEOID)
     */
    public static void removeFailedVideoWithCleanup(String videoId, String errorReason, int originalTrackIndex) {
        synchronized (PLAYLIST_LOCK) {
            // Marcar como fallido primero
            failedVideos.put(videoId, Boolean.TRUE);

            // GUARDAR EN ARCHIVO PERSISTENTE
            saveFailedVideoToFile(videoId, errorReason);

            // Buscar por videoId en lugar de confiar en el índice
            boolean found = false;
            int actualIndex = -1;
            final PlaylistItem[] itemToRemoveWrapper = new PlaylistItem[1];

            // Primero, encontrar el ítem y su índice actual
            for (int i = 0; i < playlist.size(); i++) {
                PlaylistItem item = playlist.get(i);
                if (item.videoId.equals(videoId)) {
                    itemToRemoveWrapper[0] = item;
                    actualIndex = i;
                    found = true;
                    break;
                }
            }

            if (!found || itemToRemoveWrapper[0] == null) {
                log("⚠️ No se encontró la canción con ID: " + videoId + " en la playlist");
                return;
            }

            // Ahora eliminar usando el índice actual encontrado
            playlist.remove(actualIndex);

            // Ajustar currentIndex si es necesario
            if (actualIndex <= currentIndex && currentIndex > 0) {
                currentIndex--;
            }

            // Limpiar del cache
            trackCache.remove(videoId);

            log("🗑️ ELIMINADA CANCIÓN [" + (actualIndex + 1) + "]: " + itemToRemoveWrapper[0].title
                    + " - Razón: " + errorReason);

            // LIMPIAR ARCHIVOS EN CACHÉ
            cleanupFailedVideoFiles(videoId);

            Platform.runLater(() -> {
                updateTrackList();
                updateUI();
                updateStats();

                // Mostrar mensaje en la interfaz
                nowPlayingLabel.setText("Canción eliminada: " + itemToRemoveWrapper[0].title);
            });

            // Después de eliminar una canción fallida, intentar precargar una de reemplazo
            if (!playlist.isEmpty() && currentIndex >= 0 && currentIndex < playlist.size()) {
                log("🔄 Buscando reemplazo para precarga después de eliminar canción fallida");
                scheduleNextPreloadsWithReplacement(currentIndex, 1);
            }
        }
    }
    
    // Método auxiliar para limpiar títulos
    public static String cleanTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "Sin título";
        }

        // Eliminar caracteres de control y caracteres problemáticos
        title = title.replaceAll("[\\x00-\\x1F\\x7F]", "") // Caracteres de control
                .replaceAll("^[\\|\\t\\s]+", "") // Eliminar pipes, tabs y espacios al inicio
                .replaceAll("[\\|\\t\\s]+$", "") // Eliminar pipes, tabs y espacios al final
                .trim();

        // Si después de limpiar está vacío
        if (title.isEmpty()) {
            return "Sin título";
        }

        return title;
    }
    
    public static void cleanup() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            if (factory != null) {
                factory.release();
            }
            downloadExecutor.shutdownNow();
            monitorExecutor.shutdownNow();
            cleanupExecutor.shutdownNow();

            // Limpiar archivos temporales de VLC si es necesario
            cleanupVlcTempFiles();

        } catch (Exception e) {
            log("⚠️ Error durante cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Elimina archivos asociados a un video fallido
     */
    public static void cleanupFailedVideoFiles(String videoId) {
        try {
            // Buscar y eliminar cualquier archivo que empiece con el videoId
            Files.list(cacheDir)
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith(videoId + ".") || filename.contains(videoId);
                    })
                    .forEach(path -> {
                        try {
                            boolean deleted = Files.deleteIfExists(path);
                            if (deleted) {
                                log("🗑️ Eliminado archivo de caché: " + path.getFileName());
                            }
                        } catch (IOException e) {
                            log("⚠️ No se pudo eliminar archivo " + path.getFileName() + ": " + e.getMessage());
                        }
                    });

            // También eliminar archivos temporales de yt-dlp/ffmpeg
            Files.list(cacheDir)
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.contains(videoId)
                                && (filename.endsWith(".part") || filename.endsWith(".ytdl")
                                || filename.endsWith(".temp") || filename.contains("tmp"));
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });

        } catch (IOException e) {
            log("⚠️ Error limpiando archivos de video fallido: " + e.getMessage());
        }
    }
    
    public static void cleanupFailedVideosOnLoad() {
        synchronized (PLAYLIST_LOCK) {
            Iterator<PlaylistItem> iterator = playlist.iterator();
            while (iterator.hasNext()) {
                PlaylistItem item = iterator.next();
                if (failedVideos.containsKey(item.videoId)) {
                    iterator.remove();
                    trackCache.remove(item.videoId);
                    log("🗑️ Removida canción fallida previa: " + item.title);
                }
            }

            if (currentIndex >= playlist.size()) {
                currentIndex = playlist.size() - 1;
            }
        }
    }
    
    /**
     * Limpia la lista de reproducción actual sin borrar archivos guardados
     */
    public static void clearPlaylist() {
        // Verificar si hay descargas en progreso
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        if (playlist == null || playlist.isEmpty()) {
            showAlert("Lista vacía", "No hay canciones en la cola de reproducción para limpiar.");
            return;
        }

        // Pedir confirmación al usuario
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Limpiar cola de reproducción");
        alert.setHeaderText("¿Estás seguro de que quieres limpiar la cola de reproducción?");
        alert.setContentText("Esta acción:\n"
                + "• Limpiará todas las canciones de la lista actual\n"
                + "• Detendrá la reproducción\n");

        // Personalizar los botones
        ButtonType yesButton = new ButtonType("Sí, limpiar", ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType("Cancelar", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(yesButton, noButton);

        // Mostrar y esperar respuesta
        alert.showAndWait().ifPresent(response -> {
            if (response == yesButton) {
                performPlaylistClearing();
            }
        });
    }
    
    /**
     * Realiza la limpieza de la lista de reproducción
     */
    private static void performPlaylistClearing() {
        log("Limpiando lista de reproducción...");

        currentSessionUrls.clear();
        if (SESSION_FILE.exists()) {
            SESSION_FILE.delete();
        }

        // Detener la reproducción si está activa
        if (mediaPlayer != null && mediaPlayer.status().isPlaying()) {
            mediaPlayer.controls().stop();
        }

        // Resetear variables de reproducción
        savedPlaybackTime = 0;
        isPlaying = false;
        currentIndex = -1;

        // Limpiar listas
        synchronized (PLAYLIST_LOCK) {
            playlist.clear();
            shuffleOrder.clear();
            failedVideos.clear(); // Opcional: también limpiar videos fallidos
        }

        // Resetear modo aleatorio
        shuffleMode = false;

        // Actualizar la interfaz
        Platform.runLater(() -> {
            // Actualizar etiquetas
            nowPlayingLabel.setText("Lista limpiada - Selecciona nueva playlist");
            playProgress.setProgress(0);
            seekSlider.setValue(0);
            timeLabel.setText("00:00 / 00:00");

            // Actualizar botones
            playPauseBtn.setText("▶");
            playPauseBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
            shuffleBtn.setText("🔀 OFF");
            shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");

            // Limpiar lista visual
            trackListView.getItems().clear();

            // Actualizar estadísticas
            updateStats();
            updateUI();
        });

        log("✅ Lista limpiada exitosamente");
    }
}
