package com.faklify.filesCache;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.DTOs.Video.extractVideoId;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import static com.faklify.attributesGenerics.Attributes.AUDIO_FORMAT;
import static com.faklify.attributesGenerics.Attributes.CACHE_DIR;
import static com.faklify.attributesGenerics.Attributes.CLEANUP_DAYS;
import static com.faklify.attributesGenerics.Attributes.CLEANUP_INTERVAL_HOURS;
import static com.faklify.attributesGenerics.Attributes.videoIdToRestore;
import static com.faklify.attributesGenerics.Attributes.durationToRestore;
import static com.faklify.attributesGenerics.Attributes.FAILED_VIDEOS_FILE;
import static com.faklify.attributesGenerics.Attributes.MAX_CACHE_AGE_DAYS;
import static com.faklify.attributesGenerics.Attributes.MAX_CACHE_SIZE_MB;
import static com.faklify.attributesGenerics.Attributes.MIN_FILE_SIZE;
import static com.faklify.attributesGenerics.Attributes.MIN_FREE_SPACE_MB;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.attributesGenerics.Attributes.RECENT_PLAY_HOURS;
import static com.faklify.attributesGenerics.Attributes.SESSION_FILE;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.cleanupExecutor;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.failedVideos;
import static com.faklify.attributesGenerics.Attributes.lastPlayedTimes;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.attributesGenerics.Attributes.vlcInternalDir;
import static com.faklify.methodsGenerics.Methods.log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.application.Platform;

public class FileCache {
    public static void initCache() {
        try {
            cacheDir = Paths.get(System.getProperty("user.home"), CACHE_DIR);
            Files.createDirectories(cacheDir);
            log("📁 Caché en: " + cacheDir);

            // LIMPIAR DUPLICADOS DEL ARCHIVO AL INICIAR
            cleanupFailedVideosFile();

            // CARGAR VIDEOS FALLIDOS GUARDADOS
            loadFailedVideosFromFile();

            scanExistingCache();

        } catch (IOException e) {
            log("❌ Error caché: " + e.getMessage());
        }
    }
    
    public static void preLoadSessionData() {
        if (!SESSION_FILE.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(SESSION_FILE.toPath());
            if (lines.isEmpty()) {
                return;
            }

            // Extraer ID y Duración
            String[] parts = lines.get(0).split("\\|");
            videoIdToRestore = parts[0];
            durationToRestore = (parts.length > 1) ? parts[1] : "00:00";

            // Guardar las URLs para procesarlas luego
            for (int i = 1; i < lines.size(); i++) {
                currentSessionUrls.add(lines.get(i));
            }
        } catch (IOException e) {
            log("⚠️ Error en pre-carga: " + e.getMessage());
        }
    }
    
    // Eliminar playlist por nombre
    public static void deletePlaylistFromFile(String playlistName) {
        try {
            Path playlistsFile = cacheDir.resolve("saved_playlists.dat");
            if (Files.exists(playlistsFile)) {
                List<String> lines = Files.readAllLines(playlistsFile);
                List<String> newLines = new ArrayList<>();

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        int separatorIndex = trimmed.indexOf(" - ");
                        if (separatorIndex != -1) {
                            String name = trimmed.substring(0, separatorIndex).trim();
                            // Solo guardar si NO es la playlist a eliminar
                            if (!name.equals(playlistName)) {
                                newLines.add(trimmed);
                            }
                        }
                    }
                }

                // Reescribir el archivo
                try (BufferedWriter writer = Files.newBufferedWriter(playlistsFile)) {
                    for (String line : newLines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
        } catch (IOException e) {
            log("⚠️ Error al eliminar playlist: " + e.getMessage());
        }
    }
    
    // Guardar playlist con nombre
    public static void savePlaylistToFile(String name, String url) {
        try {
            Path playlistsFile = cacheDir.resolve("saved_playlists.dat");
            try (BufferedWriter writer = Files.newBufferedWriter(playlistsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                // Formato: [NOMBRE] - [URL]
                writer.write(name + " - " + url);
                writer.newLine();
            }
        } catch (IOException e) {
            log("⚠️ No se pudo guardar la playlist: " + e.getMessage());
        }
    }
    
    // Limpiar archivos temporales de VLC
    public static void cleanupVlcTempFiles() {
        try {
            if (vlcInternalDir != null && Files.exists(vlcInternalDir)) {
                // No eliminamos el directorio completo, solo archivos temporales que VLC pueda crear
                Files.list(vlcInternalDir)
                        .filter(path -> {
                            String filename = path.getFileName().toString();
                            return filename.endsWith(".log")
                                    || filename.endsWith(".tmp")
                                    || filename.startsWith("vlc-cache-");
                        })
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                log("🗑️ Eliminado archivo temporal VLC: " + path.getFileName());
                            } catch (IOException e) {
                                // Ignorar
                            }
                        });
            }
        } catch (IOException e) {
            // Ignorar errores de limpieza
        }
    }
    
    public static void saveCurrentSession() {
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
    
    public static void startAutoCleanup() {
        log("🔧 Iniciando limpieza automática (cada " + CLEANUP_INTERVAL_HOURS + " horas)");

        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanCacheIfNeeded();
            } catch (Exception e) {
                log("⚠️ Error en limpieza automática: " + e.getMessage());
            }
        }, 0, CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS);
    }
    
    private static void cleanCacheIfNeeded() {
        try {
            List<Path> allAudioFiles;
            try {
                allAudioFiles = Files.list(cacheDir)
                        .filter(path -> path.toString().endsWith("." + AUDIO_FORMAT))
                        .sorted((p1, p2) -> {
                            try {
                                return Long.compare(
                                        Files.getLastModifiedTime(p1).toMillis(),
                                        Files.getLastModifiedTime(p2).toMillis()
                                );
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .collect(Collectors.toList());
            } catch (IOException e) {
                log("❌ Error listando archivos de caché: " + e.getMessage());
                return;
            }

            long currentCacheSizeBytes = 0;
            long currentCacheSizeMB = 0;
            List<Path> filesToKeep = new ArrayList<>();
            List<Path> filesToDelete = new ArrayList<>();

            File cacheFile = cacheDir.toFile();
            long freeSpaceMB = cacheFile.getFreeSpace() / (1024 * 1024);

            boolean lowDiskSpace = freeSpaceMB < MIN_FREE_SPACE_MB;

            if (lowDiskSpace) {
                log("⚠️ Poco espacio en disco: " + freeSpaceMB + "MB libres (mínimo: " + MIN_FREE_SPACE_MB + "MB)");
            }

            for (Path file : allAudioFiles) {
                try {
                    long fileSizeBytes = Files.size(file);
                    long fileSizeMB = fileSizeBytes / (1024 * 1024);
                    long fileAgeDays = getFileAgeDays(file);
                    String videoId = extractVideoId(file);

                    // Eliminar si tiene más de 30 días
                    if (fileAgeDays >= MAX_CACHE_AGE_DAYS) {
                        filesToDelete.add(file);
                        log("⏰ Archivo de " + fileAgeDays + " días marcado para eliminación (límite: " + MAX_CACHE_AGE_DAYS + " días): " + file.getFileName());
                        continue; // Saltar al siguiente archivo
                    }

                    if (mustKeepFile(file, videoId)) {
                        filesToKeep.add(file);
                        currentCacheSizeBytes += fileSizeBytes;
                        currentCacheSizeMB += fileSizeMB;

                    } else if (canKeepFile(file, videoId, currentCacheSizeMB, fileAgeDays, lowDiskSpace)) {
                        filesToKeep.add(file);
                        currentCacheSizeBytes += fileSizeBytes;
                        currentCacheSizeMB += fileSizeMB;

                    } else {
                        filesToDelete.add(file);
                    }

                } catch (IOException e) {
                    filesToDelete.add(file);
                }
            }

            int deletedCount = 0;
            int deletedByAgeCount = 0;
            for (Path file : filesToDelete) {
                if (deleteFileSafely(file)) {
                    deletedCount++;

                    // Verificar si fue eliminado por edad
                    try {
                        long fileAgeDays = getFileAgeDays(file);
                        if (fileAgeDays >= MAX_CACHE_AGE_DAYS) {
                            deletedByAgeCount++;
                        }
                    } catch (IOException e) {
                        // Ignorar si no podemos obtener la edad
                    }

                    String videoId = extractVideoId(file);
                    Track track = trackCache.get(videoId);
                    if (track != null && track.filePath != null
                            && track.filePath.equals(file)) {
                        track.status = TrackStatus.NOT_DOWNLOADED;
                        track.filePath = null;
                    }
                }
            }

            if (deletedCount > 0) {
                String ageMessage = "";
                if (deletedByAgeCount > 0) {
                    ageMessage = " | Por antigüedad (" + MAX_CACHE_AGE_DAYS + "+ días): " + deletedByAgeCount;
                }

                log("🗑️ Limpieza completada: " + deletedCount + " archivos eliminados" + ageMessage
                        + " | Caché actual: " + currentCacheSizeMB + "MB/" + MAX_CACHE_SIZE_MB + "MB"
                        + " | Espacio libre: " + freeSpaceMB + "MB");

                Platform.runLater(() -> updateTrackList());
            }

        } catch (Exception e) {
            log("❌ Error en limpieza: " + e.getMessage());
        }
    }
    
    private static boolean mustKeepFile(Path file, String videoId) throws IOException {
        if (isCurrentlyPlaying(videoId)) {
            return true;
        }

        if (isInCurrentPlaylist(videoId)) {
            return true;
        }

        if (isRecentlyPlayed(videoId)) {
            return true;
        }

        return false;
    }
    
    private static boolean isInCurrentPlaylist(String videoId) {
        synchronized (PLAYLIST_LOCK) {
            return playlist.stream()
                    .anyMatch(item -> item.videoId.equals(videoId));
        }
    }

    private static boolean isRecentlyPlayed(String videoId) {
        Long lastPlayed = lastPlayedTimes.get(videoId);
        if (lastPlayed == null) {
            return false;
        }

        long hoursSincePlayed = (System.currentTimeMillis() - lastPlayed)
                / (1000 * 60 * 60);

        return hoursSincePlayed < RECENT_PLAY_HOURS;
    }
    
    private static boolean isCurrentlyPlaying(String videoId) {
        synchronized (PLAYLIST_LOCK) {
            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistItem currentItem = playlist.get(currentIndex);
                return currentItem.videoId.equals(videoId);
            }
        }
        return false;
    }
    
    private static boolean canKeepFile(Path file, String videoId, long currentCacheSizeMB,
            long fileAgeDays, boolean lowDiskSpace) throws IOException {

        long fileSizeMB = Files.size(file) / (1024 * 1024);

        if ((currentCacheSizeMB + fileSizeMB) > MAX_CACHE_SIZE_MB) {
            return false;
        }

        if (lowDiskSpace && fileAgeDays > 1) {
            return false;
        }

        if (fileAgeDays < CLEANUP_DAYS) {
            return true;
        }

        return false;
    }
    
    private static boolean deleteFileSafely(Path file) {
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log("🗑️ Eliminado: " + file.getFileName());
            }
            return deleted;
        } catch (IOException e) {
            log("⚠️ No se pudo eliminar " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
    
    private static long getFileAgeDays(Path file) throws IOException {
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastModified) / (1000 * 60 * 60 * 24);
    }
    
    /**
     * Limpia duplicados del archivo de videos fallidos
     */
    private static void cleanupFailedVideosFile() {
        Path failedFile = cacheDir.resolve(FAILED_VIDEOS_FILE);

        if (!Files.exists(failedFile)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(failedFile);
            Set<String> uniqueIds = new LinkedHashSet<>(); // Mantener orden
            List<String> cleanedLines = new ArrayList<>();

            // Procesar en orden inverso para mantener la entrada más reciente
            Collections.reverse(lines);

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    // Mantener comentarios y líneas vacías al principio
                    cleanedLines.add(0, line);
                    continue;
                }

                String[] parts = line.split("\\|");
                if (parts.length >= 1) {
                    String videoId = parts[0].trim();
                    if (!uniqueIds.contains(videoId)) {
                        uniqueIds.add(videoId);
                        // Mantener esta línea (la más reciente para este videoId)
                        cleanedLines.add(0, line);
                    }
                }
            }

            // Volver a ordenar correctamente
            Collections.reverse(cleanedLines);

            // Reescribir el archivo
            try (BufferedWriter writer = Files.newBufferedWriter(failedFile)) {
                for (String line : cleanedLines) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            log("Archivo de videos fallidos limpiado: " + lines.size() + " → " + cleanedLines.size() + " líneas");

        } catch (IOException e) {
            log("⚠️ Error limpiando archivo de videos fallidos: " + e.getMessage());
        }
    }
    
    private static void scanExistingCache() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*" + AUDIO_FORMAT)) {
            for (Path path : stream) {
                String filename = path.getFileName().toString();
                String videoId = filename.substring(0, filename.lastIndexOf('.'));

                if (Files.size(path) > MIN_FILE_SIZE) {
                    Track track = new Track();
                    track.filePath = path;
                    track.status = TrackStatus.DOWNLOADED;
                    trackCache.put(videoId, track);
                } else {
                    Files.deleteIfExists(path);
                }
            }
            log("✅ Caché escaneada: " + trackCache.size() + " archivos.");
        } catch (Exception e) {
            log("⚠️ Error escaneando caché: " + e.getMessage());
        }
    }
    
    /**
     * Carga la lista de videos fallidos desde el archivo (IDs únicos)
     */
    private static void loadFailedVideosFromFile() {
        Path failedFile = cacheDir.resolve(FAILED_VIDEOS_FILE);

        if (!Files.exists(failedFile)) {
            log("ℹ️ No hay archivo de videos fallidos previos");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(failedFile);
            Set<String> uniqueIds = new HashSet<>(); // Para evitar duplicados en memoria

            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 1) {
                        String videoId = parts[0].trim();
                        if (!videoId.isEmpty() && !uniqueIds.contains(videoId)) {
                            failedVideos.put(videoId, Boolean.TRUE);
                            uniqueIds.add(videoId);

                            if (parts.length >= 2) {
                                String reason = parts[1].trim();
                                log("📋 Video fallido cargado: " + videoId + " - " + reason);
                            }
                        }
                    }
                }
            }

            log("✅ Cargados " + uniqueIds.size() + " videos fallidos únicos de archivo");

        } catch (IOException e) {
            log("⚠️ Error cargando videos fallidos: " + e.getMessage());
        }
    }
}
