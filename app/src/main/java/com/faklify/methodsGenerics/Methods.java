package com.faklify.methodsGenerics;

import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import com.faklify.attributesGenerics.Attributes;
import static com.faklify.attributesGenerics.Attributes.DEBUG_MODE;
import static com.faklify.attributesGenerics.Attributes.cacheInfoLabel;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

public class Methods {
    public static void log(String message) {
        if (DEBUG_MODE) {
            System.out.println("[LOG] " + message);
        }
    }
    
    public static void showDownloadInProgressAlert() {
        // Evitar mostrar múltiples alerts
        if (Attributes.showingDownloadAlert) {
            return;
        }

        Attributes.showingDownloadAlert = true;

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Descarga en Progreso");
            alert.setHeaderText("⏳ Espera un momento");
            alert.setContentText("Hay canción(es) cargando.\n"
                    + "Por favor espera a que terminen antes de cambiar de canción.");

            // Estilo del alert
            DialogPane dialogPane = alert.getDialogPane();
            dialogPane.setStyle("-fx-background-color: #f8f9fa;");

            // Configurar el botón OK
            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(okButton);

            // Mostrar y esperar
            alert.showAndWait();

            // Resetear la bandera
            Attributes.showingDownloadAlert = false;

            log("⚠️ Intento de cambiar canción durante descarga - Descargas activas: "
                    + Attributes.activeDownloads.get());
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
    
    public static boolean isValidYouTubeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String urlLower = url.toLowerCase();
        return urlLower.contains("youtube.com") || urlLower.contains("youtu.be");
    }
    
    public static void showRestartAlert() {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("¡Instalación Exitosa!");
            alert.setHeaderText("yt-dlp ya está configurado en el sistema");
            alert.setContentText("Para que la aplicación pueda detectar el nuevo componente, "
                    + "es necesario cerrar esta ventana y volver a abrir el programa "
                    + "manualmente.\n\n"
                    + "La aplicación se cerrará al pulsar Aceptar.");

            alert.showAndWait().ifPresent(response -> {
                // Cerramos la aplicación completamente
                Platform.exit();
                System.exit(0);
            });
        });
    }
    
    public static void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    public static String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
}
