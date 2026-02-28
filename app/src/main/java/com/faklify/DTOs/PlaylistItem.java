package com.faklify.DTOs;

import static com.faklify.UI.UserInterface.updateUI;
import static com.faklify.UI.focus.Focus.aplicarFocoYScroll;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTracksWithCacheInfo;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.durationToRestore;
import static com.faklify.attributesGenerics.Attributes.failedVideos;
import static com.faklify.attributesGenerics.Attributes.loadBtn;
import static com.faklify.attributesGenerics.Attributes.loadStatus;
import static com.faklify.attributesGenerics.Attributes.nowPlayingLabel;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.shuffleBtn;
import static com.faklify.attributesGenerics.Attributes.shuffleMode;
import static com.faklify.attributesGenerics.Attributes.shuffleOrder;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.attributesGenerics.Attributes.urlField;
import static com.faklify.attributesGenerics.Attributes.videoIdToRestore;
import static com.faklify.filesCache.FileCache.saveCurrentSession;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.methodsGenerics.Methods.showAlert;
import static com.faklify.services.Cleaning.cleanupFailedVideosOnLoad;
import static com.faklify.services.DownloadVideo.fetchPlaylistItems;
import static com.faklify.ytdlp.YtDLP.checkYtDlpSilent;
import static com.faklify.ytdlp.YtDLP.showYtDlpInstallHelp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TabPane;

public class PlaylistItem {

    public String title;
    public String videoId;
    public String url;
    
    // Obtener URL para un nombre de playlist
    public static String getUrlForPlaylistName(String playlistName) {
        try {
            Path playlistsFile = cacheDir.resolve("saved_playlists.dat");
            if (Files.exists(playlistsFile)) {
                List<String> lines = Files.readAllLines(playlistsFile);

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        int separatorIndex = trimmed.indexOf(" - ");
                        if (separatorIndex != -1) {
                            String name = trimmed.substring(0, separatorIndex).trim();
                            if (name.equals(playlistName)) {
                                // Extraer la URL (todo después del " - ")
                                String url = trimmed.substring(separatorIndex + 3).trim();
                                return url;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log("⚠️ Error al buscar URL: " + e.getMessage());
        }
        return null;
    }
    
    public static void loadPlaylistNamesOnly() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            showAlert("URL vacía", "Ingresa URL de playlist");
            return;
        }

        // Verificar yt-dlp antes de continuar
        if (!checkYtDlpSilent()) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("yt-dlp no encontrado");
                alert.setHeaderText("No se puede descargar sin yt-dlp");
                alert.setContentText("yt-dlp es necesario para obtener la lista de canciones.\n\n"
                        + "¿Quieres ver instrucciones para instalarlo?");

                ButtonType installBtn = new ButtonType("Sí, ver instrucciones");
                ButtonType continueBtn = new ButtonType("Continuar de todos modos");
                ButtonType cancelBtn = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);

                alert.getButtonTypes().setAll(installBtn, continueBtn, cancelBtn);

                alert.showAndWait().ifPresent(response -> {
                    if (response == installBtn) {
                        showYtDlpInstallHelp();
                    } else if (response == continueBtn) {
                        currentSessionUrls.add(url);
                        saveCurrentSession(); // Guardamos el estado actual
                        // Continuar con el método original
                        continuePlaylistLoad(url);
                    }
                    // Si cancela, no hacer nada
                });
            });
            return;
        }

        currentSessionUrls.add(url);
        saveCurrentSession(); // Guardamos el estado actual
        // Si yt-dlp está disponible, cargar normalmente
        continuePlaylistLoad(url);
    }
    
    /**
     * Continúa con la carga de playlist (separado para reutilización)
     */
    public static void continuePlaylistLoad(String url) {
        // Estado inicial de la UI de carga
        if (loadBtn == null) {
            return;
        }
        loadBtn.setDisable(true);
        loadStatus.setText("Obteniendo lista...");

        // Si la lista está vacía, nos aseguramos de que el índice sea inicial
        if (playlist.isEmpty()) {
            currentIndex = -1;
        }

        shuffleOrder.clear();
        shuffleMode = false;
        failedVideos.clear();

        log("📋 Iniciando descarga de metadatos de la playlist...");

        downloadExecutor.submit(() -> {
            try {
                // Descarga de ítems (proceso pesado fuera del hilo de UI)
                List<PlaylistItem> newItems = fetchPlaylistItems(url);

                Platform.runLater(() -> {
                    // FILTRO ANTI-DUPLICADOS
                    Set<String> existingIds = playlist.stream()
                            .map(item -> item.videoId)
                            .collect(Collectors.toSet());

                    List<PlaylistItem> uniqueItems = newItems.stream()
                            .filter(item -> !existingIds.contains(item.videoId))
                            .collect(Collectors.toList());

                    int duplicatesCount = newItems.size() - uniqueItems.size();

                    if (uniqueItems.isEmpty() && !newItems.isEmpty()) {
                        log("⚠️ Playlist duplicada.");
                        showAlert("Playlist duplicada", "Esta playlist ya la cargaste anteriormente.");
                    } else {
                        // Añadir nuevos elementos y refrescar la vista
                        playlist.addAll(uniqueItems);
                        updateTrackList();

                        // Si estamos cargando una playlist adicional (no es restauración)
                        // mantenemos el foco en la canción que ya estaba seleccionada
                        if (videoIdToRestore == null && currentIndex >= 0) {
                            aplicarFocoYScroll(currentIndex);
                        }

                        log("✅ Añadidas " + uniqueItems.size() + " canciones nuevas");
                        loadStatus.setText("✅ Lista cargada: " + uniqueItems.size() + " nuevas");
                    }

                    cleanupFailedVideosOnLoad();

                    // CAMBIO AUTOMÁTICO DE PESTAÑA
                    TabPane tabs = (TabPane) urlField.getScene().lookup(".tab-pane");
                    if (tabs != null) {
                        tabs.getTabs().get(1).setDisable(false);
                        tabs.getSelectionModel().select(1);
                    }

                    updateTracksWithCacheInfo();

                    // RESTAURACIÓN DE SESIÓN (Solo ocurre al iniciar la App)
                    if (videoIdToRestore != null && !videoIdToRestore.equals("NONE")) {
                        for (int i = 0; i < playlist.size(); i++) {
                            PlaylistItem item = playlist.get(i);
                            if (item.videoId.equals(videoIdToRestore)) {
                                currentIndex = i;

                                // Actualizamos etiquetas visuales
                                nowPlayingLabel.setText("▶ " + item.title);
                                timeLabel.setText("00:00 / " + durationToRestore);

                                // Aplicamos el foco y scroll a la canción restaurada
                                aplicarFocoYScroll(currentIndex);

                                updateUI();
                                videoIdToRestore = null; // Limpiamos la bandera
                                break;
                            }
                        }
                    }

                    // Reset visual del botón Shuffle
                    shuffleBtn.setText("🔀 OFF");
                    shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    log("❌ Error: " + e.getMessage());
                    loadStatus.setText("Error cargando lista");

                    if (e.getMessage() != null && e.getMessage().contains("yt-dlp")) {
                        showYtDlpInstallHelp();
                    }
                });
            } finally {
                Platform.runLater(() -> loadBtn.setDisable(false));
            }
        });
    }
}