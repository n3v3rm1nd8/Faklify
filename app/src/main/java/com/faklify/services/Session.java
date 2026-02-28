package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.attributesGenerics.Attributes.SESSION_FILE;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.methodsGenerics.Methods.log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

public class Session {
    private static void saveCurrentSession() {
        try {
            String vId = "NONE";
            String duration = "00:00";

            // Usamos el currentIndex que ya manejas en toda tu app
            synchronized (PLAYLIST_LOCK) {
                if (currentIndex >= 0 && currentIndex < playlist.size()) {
                    PlaylistItem currentItem = playlist.get(currentIndex);
                    vId = currentItem.videoId;
                }
            }

            // Extraer duración del label actual
            String currentLabelText = timeLabel.getText();
            if (currentLabelText != null && currentLabelText.contains("/")) {
                String[] parts = currentLabelText.split("/");
                duration = parts[parts.length - 1].trim(); // Cogemos siempre lo último
            }

            // Construir el contenido del archivo
            StringBuilder sb = new StringBuilder();
            sb.append(vId).append("|").append(duration).append("\n");

            for (String url : currentSessionUrls) {
                sb.append(url).append("\n");
            }

            Files.writeString(SESSION_FILE.toPath(), sb.toString().trim());

        } catch (Exception e) {
            log("⚠️ Error al guardar sesión: " + e.getMessage());
        }
    }
    
    // Método para refrescar la lista de playlists
    public static void refreshSavedPlaylistsList(ListView<String> listView, Label titleLabel) {
        // Cargar los nombres de playlists
        List<String> playlistNames = loadSavedPlaylistNames();

        // Actualizar la interfaz en el hilo de JavaFX
        Platform.runLater(() -> {
            // Limpiar la lista actual
            listView.getItems().clear();

            // Añadir todos los nombres
            for (String name : playlistNames) {
                listView.getItems().add(name);
            }

            // Actualizar el título con el contador
            titleLabel.setText("📂 Playlists (" + playlistNames.size() + ")");
        });
    }
    
    // Cargar nombres de playlists
    private static List<String> loadSavedPlaylistNames() {
        List<String> names = new ArrayList<>();
        try {
            Path playlistsFile = cacheDir.resolve("saved_playlists.dat");
            if (Files.exists(playlistsFile)) {
                List<String> lines = Files.readAllLines(playlistsFile);

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        // Extraer el nombre (todo antes del " - ")
                        int separatorIndex = trimmed.indexOf(" - ");
                        if (separatorIndex != -1) {
                            String name = trimmed.substring(0, separatorIndex).trim();
                            names.add(name);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log("⚠️ Error al cargar playlists: " + e.getMessage());
        }
        return names;
    }
}
