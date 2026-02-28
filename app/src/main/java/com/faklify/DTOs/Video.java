package com.faklify.DTOs;

import static com.faklify.attributesGenerics.Attributes.FAILED_VIDEOS_FILE;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.failedVideos;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.Cleaning.removeFailedVideoWithCleanup;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.application.Platform;

public class Video {
    
    public static void showErrorPopupAndRemoveTrack(PlaylistItem item, int trackIndex, String errorMessage) {
        // Determinar el tipo de error para el reporte interno (opcional pero útil para logs)
        String errorType;
        if (errorMessage.contains("Sign in") || errorMessage.contains("age verification")) {
            errorType = "restriccion_edad";
        } else if (errorMessage.contains("Private video") || errorMessage.contains("unavailable")) {
            errorType = "video_privado_eliminado";
        } else if (errorMessage.contains("blocked") || errorMessage.contains("removed")) {
            errorType = "bloqueado_derechos_autor";
        } else {
            errorType = "error_desconocido";
        }

        log("⚠️ Error detectado en: " + item.title + " [" + errorType + "]");
        log("🗑️ Eliminando automáticamente de la lista para evitar errores futuros...");

        // Ejecutar la eliminación en el hilo de la UI para que la lista visual se actualice
        Platform.runLater(() -> {
            try {
                // Llamamos directamente al método de limpieza permanente
                removeFailedVideoWithCleanup(item.videoId, errorType, trackIndex);
            } catch (Exception e) {
                log("❌ Error al intentar auto-eliminar: " + e.getMessage());
            }
        });
    }
    
    public static String extractVideoId(Path file) {
        String filename = file.getFileName().toString();
        return filename.substring(0, filename.lastIndexOf('.'));
    }
    
    public static boolean isVideoFailed(String videoId) {
        return failedVideos.containsKey(videoId);
    }
    
    /**
     * Verifica si un video está marcado como fallido en el archivo
     */
    public static boolean isVideoFailedInFile(String videoId) {
        Path failedFile = cacheDir.resolve(FAILED_VIDEOS_FILE);

        if (!Files.exists(failedFile)) {
            return false;
        }

        try {
            // Búsqueda rápida en el archivo
            return Files.lines(failedFile)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("\\|"))
                    .filter(parts -> parts.length >= 1)
                    .anyMatch(parts -> parts[0].trim().equals(videoId));

        } catch (IOException e) {
            log("⚠️ Error verificando video fallido en archivo: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Guarda un video fallido en el archivo persistente (evita duplicados)
     */
    public static void saveFailedVideoToFile(String videoId, String reason) {
        Path failedFile = cacheDir.resolve(FAILED_VIDEOS_FILE);

        try {
            // Crear la línea con timestamp para mejor trazabilidad
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date());

            String newLine = videoId + "|" + reason + "|" + timestamp;

            // Verificar si el archivo existe
            if (Files.exists(failedFile)) {
                // Leer todas las líneas existentes
                List<String> existingLines = Files.readAllLines(failedFile);
                Set<String> existingVideoIds = new HashSet<>();
                List<String> updatedLines = new ArrayList<>();

                // Procesar líneas existentes
                for (String line : existingLines) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 1) {
                            String existingVideoId = parts[0].trim();
                            existingVideoIds.add(existingVideoId);

                            // Mantener la línea si no es el videoId que estamos añadiendo
                            if (!existingVideoId.equals(videoId)) {
                                updatedLines.add(line);
                            } else {
                                log("ℹ️ Video ID ya existe en archivo, actualizando: " + videoId);
                            }
                        }
                    } else {
                        // Mantener comentarios y líneas vacías
                        updatedLines.add(line);
                    }
                }

                // Si el videoId no existe, añadirlo
                if (!existingVideoIds.contains(videoId)) {
                    updatedLines.add(newLine);
                } else {
                    // Si ya existe, añadir nueva entrada (mantener histórico)
                    updatedLines.add(newLine);
                    log("📝 Manteniendo entrada histórica para: " + videoId);
                }

                // Reescribir el archivo completo
                try (BufferedWriter writer = Files.newBufferedWriter(failedFile)) {
                    for (String line : updatedLines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }

            } else {
                // Crear archivo nuevo si no existe
                try (BufferedWriter writer = Files.newBufferedWriter(failedFile,
                        StandardOpenOption.CREATE)) {
                    writer.write(newLine);
                    writer.newLine();
                }
            }

            log("💾 Video fallido procesado en archivo: " + videoId + " - " + reason);

        } catch (IOException e) {
            log("⚠️ Error guardando video fallido: " + e.getMessage());
        }
    }
}
