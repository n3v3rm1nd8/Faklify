package com.faklify.UI.importSection;

import com.faklify.DTOs.PlaylistItem;
import static com.faklify.DTOs.PlaylistItem.getUrlForPlaylistName;
import static com.faklify.DTOs.PlaylistItem.loadPlaylistNamesOnly;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.loadBtn;
import static com.faklify.attributesGenerics.Attributes.loadStatus;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.urlField;
import static com.faklify.donations.Donation.showDonationDialog;
import static com.faklify.filesCache.FileCache.deletePlaylistFromFile;
import static com.faklify.filesCache.FileCache.saveCurrentSession;
import static com.faklify.filesCache.FileCache.savePlaylistToFile;
import static com.faklify.methodsGenerics.Methods.isValidYouTubeUrl;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.CloseProgram.stop;
import static com.faklify.services.DownloadVideo.fetchPlaylistItems;
import static com.faklify.services.Session.refreshSavedPlaylistsList;
import static com.faklify.ytdlp.YtDLP.showYtDlpInstallHelp;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class Import {
    public static VBox createLoadPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        // Volvemos al fondo claro original
        panel.setStyle("-fx-background-color: #f8f9fa;");

        // Campo oculto para que la lógica de cambio de pestaña no explote
        urlField = new TextField();
        urlField.setVisible(false);
        urlField.setManaged(false);

        Label title = new Label("IMPORTA TUS PLAYLISTS FAVORITAS");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label desc = new Label("La URL de la playlist debe ser parecida a la siguiente "
                + "'https://www.youtube.com/watch?list=PLJgGrtkGfdGkkkUTG'");
        desc.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 12px;");

        // AGREGAR Y VER URLs
        HBox urlsSection = new HBox(15);
        urlsSection.setAlignment(Pos.TOP_LEFT);

        // Panel izquierdo: Agregar nueva URL
        VBox addPanel = new VBox(10);
        addPanel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 15; -fx-border-radius: 5;");
        addPanel.setPrefWidth(350);

        Label addTitle = new Label("➕ Agregar Nueva Playlist");
        addTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label nameLabel = new Label("Nombre:");
        TextField nameField = new TextField();
        nameField.setPromptText("Ej: Hip Hop");

        Label urlLabel = new Label("URL:");
        TextField urlSaveField = new TextField();
        urlSaveField.setPromptText("Pega URL de YouTube aquí...");

        Button saveBtn = new Button("💾 Guardar Playlist");
        saveBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-font-weight: bold;");
        saveBtn.setMaxWidth(Double.MAX_VALUE);

        Label savedTitle = new Label("📂 Playlists Guardadas");
        savedTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        ListView<String> savedPlaylistsList = new ListView<>();
        savedPlaylistsList.setPrefHeight(150);
        savedPlaylistsList.setPlaceholder(new Label("No hay playlists guardadas."));
        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String url = urlSaveField.getText().trim();
            if (!name.isEmpty() && !url.isEmpty() && isValidYouTubeUrl(url)) {
                savePlaylistToFile(name, url);
                nameField.clear();
                urlSaveField.clear();
                refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);
                loadStatus.setText("Playlist añadida correctamente");
            }
        });

        addPanel.getChildren().addAll(addTitle, nameLabel, nameField, urlLabel, urlSaveField, saveBtn);

        // Panel derecho: Playlists guardadas
        VBox savedPanel = new VBox(10);
        savedPanel.setStyle("-fx-background-color: #ffffff; -fx-padding: 15; -fx-border-color: #bdc3c7; -fx-border-width: 1; -fx-border-radius: 5;");
        savedPanel.setPrefWidth(450);

        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);

        // EL BOTÓN DE OBTENER LISTA
        loadBtn = new Button("📥 Importar");
        loadBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;");
        loadBtn.setOnAction(e -> {
            String selected = savedPlaylistsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                String url = getUrlForPlaylistName(selected);
                urlField.setText(url); // Seteamos el campo oculto
                loadPlaylistNamesOnly();
            }
        });

        Button deleteBtn = new Button("Eliminar");
        deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        deleteBtn.setOnAction(e -> {
            String selected = savedPlaylistsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                // Obtener la URL de la playlist que vamos a borrar
                String urlToDelete = getUrlForPlaylistName(selected);

                if (urlToDelete != null) {
                    log("🗑️ Eliminando rastro de: " + selected);

                    // Obtener las IDs de los videos de esa playlist para quitarlos de la reproducción
                    // Ejecutamos esto en un hilo aparte si es una lista muy grande para no congelar la UI
                    new Thread(() -> {
                        try {
                            // Obtenemos los items que pertenecen a esa URL específica
                            List<PlaylistItem> itemsToRemove = fetchPlaylistItems(urlToDelete);
                            Set<String> idsToRemove = itemsToRemove.stream()
                                    .map(item -> item.videoId)
                                    .collect(Collectors.toSet());

                            Platform.runLater(() -> {
                                // Limpiar de la lista de reproducción actual (pestaña Reproducir)
                                playlist.removeIf(item -> idsToRemove.contains(item.videoId));
                                loadStatus.setText("Playlist eliminada correctamente");

                                // Si la canción que suena está en la lista borrada, paramos o saltamos
                                if (currentIndex >= 0 && currentIndex < playlist.size()) {
                                    // Si el índice actual ya no es válido o la lista cambió mucho:
                                    updateTrackList();
                                } else if (playlist.isEmpty()) {
                                    stop();// Método para parar música si te quedas sin canciones
                                    currentIndex = -1;
                                    updateTrackList();
                                } else {
                                    updateTrackList();
                                }

                                log("✅ Canciones de '" + selected + "' eliminadas de la cola.");
                            });
                        } catch (Exception ex) {
                            log("⚠️ Nota: No se pudo limpiar la cola de reproducción: " + ex.getMessage());
                        }
                    }).start();

                    // Borrar de la sesión activa para que no resucite al reiniciar
                    if (currentSessionUrls.remove(urlToDelete)) {
                        saveCurrentSession();
                    }
                }

                // Borrar del archivo de biblioteca (nombres.txt / urls.txt)
                deletePlaylistFromFile(selected);

                // Refrescar la lista visual de la derecha
                refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);
            }
        });

        actionButtons.getChildren().addAll(loadBtn, deleteBtn);
        savedPanel.getChildren().addAll(savedTitle, savedPlaylistsList, actionButtons);
        urlsSection.getChildren().addAll(addPanel, savedPanel);

        loadStatus = new Label("Listo para importar");
        loadStatus.setStyle("-fx-text-fill: #7f8c8d;");

        // Botones de pie
        HBox helpRow = new HBox(10);
        helpRow.setAlignment(Pos.CENTER_LEFT);
        Button ytdlpHelpBtn = new Button("Actualizar");
        ytdlpHelpBtn.setStyle("-fx-background-color: #6610f2; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
        ytdlpHelpBtn.setOnAction(e -> showYtDlpInstallHelp());

        Button donateBtn = new Button("☕ Apoyar Proyecto");
        donateBtn.setStyle("-fx-background-color: #f1c40f; -fx-text-fill: #2c3e50; -fx-font-weight: bold; -fx-font-size: 12px;");
        donateBtn.setOnAction(e -> showDonationDialog());
        helpRow.getChildren().addAll(ytdlpHelpBtn, donateBtn);

        // Organizar todo con los Separadores nativos que tenías
        panel.getChildren().addAll(
                title, desc, new Separator(),
                urlsSection,
                new Separator(),
                loadStatus,
                new Separator(),
                helpRow,
                urlField // El campo invisible al final
        );

        // Refrescar lista al inicio
        refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);

        return panel;
    }
}
