package com.faklify.services;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.faklify.DTOs.TrackStatus;
import static com.faklify.DTOs.Video.isVideoFailed;
import static com.faklify.DTOs.Video.isVideoFailedInFile;
import static com.faklify.DTOs.Video.saveFailedVideoToFile;
import static com.faklify.DTOs.Video.showErrorPopupAndRemoveTrack;
import static com.faklify.actionsPlayer.ActionsPlayer.scheduleNextPreloads;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import com.faklify.attributesGenerics.Attributes;
import static com.faklify.attributesGenerics.Attributes.AUDIO_FORMAT;
import static com.faklify.attributesGenerics.Attributes.MIN_FILE_SIZE;
import static com.faklify.attributesGenerics.Attributes.activeDownloads;
import static com.faklify.attributesGenerics.Attributes.cacheDir;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.downloadExecutor;
import static com.faklify.attributesGenerics.Attributes.failedVideos;
import static com.faklify.attributesGenerics.Attributes.ffmpegDir;
import static com.faklify.attributesGenerics.Attributes.trackCache;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.Cleaning.cleanTitle;
import static com.faklify.services.PlayCurrent.playCurrent;
import static com.faklify.ytdlp.YtDLP.checkYtDlpSilent;
import static com.faklify.ytdlp.YtDLP.showYtDlpInstallHelp;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

public class DownloadVideo {
    public static List<PlaylistItem> fetchPlaylistItems(String playlistUrl) throws Exception {
        List<PlaylistItem> items = new ArrayList<>();

        // Usar un separador menos problemático
        String[] cmd = {
            "yt-dlp",
            "--flat-playlist",
            "--print", "%(title)s\t%(id)s\t%(webpage_url)s",
            "--no-warnings",
            "--encoding", "UTF-8",
            playlistUrl
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            String[] parts = line.split("\t");

            if (parts.length >= 3) {
                PlaylistItem item = new PlaylistItem();
                item.title = cleanTitle(parts[0]);
                item.videoId = parts[1].trim();
                item.url = parts[2].trim();

                // Verificar que el videoId sea válido
                if (item.videoId == null || item.videoId.isEmpty()
                        || item.videoId.equals("NA") || item.videoId.startsWith("http")) {
                    log("⚠️ Video ID inválido omitido: " + item.videoId);
                    continue;
                }

                // VERIFICAR MÁS ESTRICTAMENTE SI ESTÁ EN LA LISTA DE FALLIDOS
                if (isVideoFailed(item.videoId)) {
                    log("🚫 OMITIDO (fallido previo en memoria): " + item.title + " [" + item.videoId + "]");
                    continue;
                }

                // VERIFICAR DIRECTAMENTE EN EL ARCHIVO (por si no se cargó en memoria)
                if (isVideoFailedInFile(item.videoId)) {
                    log("🚫 OMITIDO (fallido previo en archivo): " + item.title + " [" + item.videoId + "]");
                    failedVideos.put(item.videoId, Boolean.TRUE); // Añadir a memoria también
                    continue;
                }

                // DETECCIÓN TEMPRANA MEJORADA
                String titleLower = item.title.toLowerCase();
                boolean isSuspicious = titleLower.contains("[deleted video]")
                        || titleLower.contains("[private video]")
                        || titleLower.contains("deleted")
                        || titleLower.contains("private")
                        || titleLower.contains("unavailable")
                        || titleLower.contains("sign in")
                        || titleLower.contains("age restricted")
                        || titleLower.contains("login required")
                        || titleLower.contains("content warning")
                        || titleLower.contains("this video may be inappropriate")
                        || item.title.equals("[Deleted video]")
                        || item.title.equals("[Private video]")
                        || item.title.startsWith("|")
                        || item.title.isEmpty();

                if (isSuspicious) {
                    // Marcarlo inmediatamente como fallido
                    Platform.runLater(() -> {
                        failedVideos.put(item.videoId, Boolean.TRUE);
                        // ⭐ GUARDAR EN ARCHIVO CON RAZÓN ESPECÍFICA
                        String reason = "sospechoso_por_titulo";
                        if (titleLower.contains("age restricted") || titleLower.contains("sign in")) {
                            reason = "restriccion_edad_titulo";
                        } else if (titleLower.contains("private") || titleLower.contains("deleted")) {
                            reason = "video_privado_eliminado";
                        }
                        saveFailedVideoToFile(item.videoId, reason);
                        log("🚫 Detectado video sospechoso en lista: " + item.videoId + " - " + item.title);
                    });

                    // NO agregarlo a la playlist para reproducción
                    continue;
                }

                items.add(item);

                Track existingTrack = trackCache.get(item.videoId);
                if (existingTrack == null) {
                    Track track = new Track();
                    track.title = item.title;

                    Path expectedFile = cacheDir.resolve(item.videoId + "." + AUDIO_FORMAT);
                    if (Files.exists(expectedFile) && Files.size(expectedFile) > MIN_FILE_SIZE) {
                        track.filePath = expectedFile;
                        track.fileSize = Files.size(expectedFile);
                        track.status = TrackStatus.DOWNLOADED;
                        log("📁 Ya en disco: " + item.title);
                    } else {
                        track.status = TrackStatus.NOT_DOWNLOADED;
                    }

                    trackCache.put(item.videoId, track);
                } else {
                    existingTrack.title = item.title;
                }
            } else if (parts.length > 0 && !line.isEmpty()) {
                log("⚠️ Línea con formato inesperado: " + line.substring(0, Math.min(line.length(), 50)) + "...");
            }
        }

        process.waitFor(30, TimeUnit.SECONDS);

        // ⭐ LOG PARA VERIFICAR FILTRADO
        log("✅ Lista filtrada: " + items.size() + " canciones después de excluir fallidas");
        log("📊 Total videos fallidos en memoria: " + failedVideos.size());

        return items;
    }
    
    public static void downloadTrack(PlaylistItem item, int index, boolean playWhenDone) {
        final int trackIndex = index;
        final String videoId = item.videoId;
        final String trackTitle = item.title;

        // Verificar yt-dlp antes de descargar
        if (!checkYtDlpSilent()) {
            log("❌ No se puede descargar: yt-dlp no disponible");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Descarga no disponible");
                alert.setHeaderText("yt-dlp no encontrado");
                alert.setContentText("No se puede descargar '" + item.title + "'\n\n"
                        + "Instala yt-dlp para habilitar descargas.");

                ButtonType helpBtn = new ButtonType("Ver instrucciones");
                ButtonType okBtn = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
                alert.getButtonTypes().setAll(helpBtn, okBtn);

                alert.showAndWait().ifPresent(response -> {
                    if (response == helpBtn) {
                        showYtDlpInstallHelp();
                    }
                });
            });
            return;
        }

        if (isVideoFailed(videoId)) {
            log("❌ Canción marcada como fallida: " + trackTitle);
            return;
        }

        Track track = Attributes.trackCache.get(videoId);
        if (track == null) {
            track = new Track();
            track.title = trackTitle;
            track.status = TrackStatus.NOT_DOWNLOADED;
            Attributes.trackCache.put(videoId, track);
        }

        final Track finalTrack = track;

        if (finalTrack.isFileValid()) {
            log("✅ Ya descargado y válido: " + trackTitle);
            if (playWhenDone) {
                Platform.runLater(() -> {
                    if (Attributes.currentIndex != trackIndex) {
                        Attributes.currentIndex = trackIndex;
                    }
                    playCurrent();
                });
            }
            return;
        }

        // Verificar ANTES de cambiar estado
        if (finalTrack.status == TrackStatus.DOWNLOADING) {
            log("⚠️ Ya se está descargando: " + trackTitle);
            return;
        }

        Path expectedFile = Attributes.cacheDir.resolve(videoId + "." + Attributes.AUDIO_FORMAT);
        if (Files.exists(expectedFile)) {
            try {
                long fileSize = Files.size(expectedFile);
                if (fileSize > Attributes.MIN_FILE_SIZE) {
                    finalTrack.filePath = expectedFile;
                    finalTrack.fileSize = fileSize;
                    finalTrack.status = TrackStatus.DOWNLOADED;

                    Platform.runLater(() -> {
                        log("📁 Encontrado en disco: " + trackTitle);
                        updateTrackList();
                        if (playWhenDone && currentIndex == trackIndex) {
                            playCurrent();
                        }
                    });
                    return;
                } else {
                    Files.deleteIfExists(expectedFile);
                }
            } catch (IOException e) {
                // Continuar con la descarga
            }
        }

        // Iniciar la descarga en un hilo con retraso
        downloadExecutor.submit(() -> {
            try {
                // Retraso para dar tiempo a las verificaciones
                Thread.sleep(100);

                // Verificar una última vez antes de iniciar (con sincronización)
                synchronized (finalTrack) {
                    if (finalTrack.status == TrackStatus.DOWNLOADING) {
                        log("⚠️ Descarga ya iniciada para: " + trackTitle);
                        return;
                    }
                    finalTrack.status = TrackStatus.DOWNLOADING;
                }

                finalTrack.downloadAttempts++;
                finalTrack.lastDownloadAttempt = System.currentTimeMillis();
                activeDownloads.incrementAndGet();

                log("⬇️ Iniciando descarga [" + (trackIndex + 1) + "]: " + trackTitle);

                // Codigo de descarga
                Path outputFile = cacheDir.resolve(videoId + "." + AUDIO_FORMAT);
                List<String> errorLines = new ArrayList<>();

                String[] cmd = {
                    "yt-dlp",
                    "-x", "--audio-format", AUDIO_FORMAT,
                    "--audio-quality", "192K",
                    "--ffmpeg-location", ffmpegDir.toString(),
                    "-o", outputFile.toString(),
                    "--no-warnings",
                    item.url
                };

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);

                Process process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("error") || line.toLowerCase().contains("unavailable")
                            || line.toLowerCase().contains("private") || line.toLowerCase().contains("not available")
                            || line.toLowerCase().contains("sign") || line.toLowerCase().contains("blocked")
                            || line.toLowerCase().contains("deleted")
                            || line.toLowerCase().contains("removed")) {
                        errorLines.add(line);
                        System.err.println("yt-dlp error: " + line);
                    }
                }

                boolean finished = process.waitFor(120, TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0 && Files.exists(outputFile)) {
                    long fileSize = Files.size(outputFile);
                    if (fileSize > MIN_FILE_SIZE) {
                        finalTrack.filePath = outputFile;
                        finalTrack.fileSize = fileSize;
                        finalTrack.status = TrackStatus.DOWNLOADED;

                        Platform.runLater(() -> {
                            log("✅ Descargado [" + (trackIndex + 1) + "]: " + trackTitle
                                    + String.format(" (%.1f MB)", fileSize / (1024.0 * 1024.0)));

                            updateTrackList();

                            if (playWhenDone && currentIndex == trackIndex) {
                                playCurrent();
                            }
                        });

                        if (playWhenDone) {
                            scheduleNextPreloads(trackIndex);
                        }

                    } else {
                        Files.deleteIfExists(outputFile);
                        throw new Exception("Archivo corrupto (solo " + fileSize + " bytes)");
                    }

                } else {
                    StringBuilder errorMsg = new StringBuilder();
                    if (!errorLines.isEmpty()) {
                        for (String err : errorLines) {
                            errorMsg.append(err).append(" ");
                        }
                    } else {
                        errorMsg.append("Descarga falló o timeout (exit code: ")
                                .append(process.isAlive() ? "timeout" : process.exitValue())
                                .append(")");
                    }

                    throw new Exception(errorMsg.toString());
                }

            } catch (Exception e) {
                // MANEJO MEJORADO DE ERRORES CON POPUP
                Platform.runLater(() -> {
                    String errorMessage = e.getMessage();
                    log("❌ Error descargando [" + (trackIndex + 1) + "]: " + trackTitle
                            + " - " + errorMessage);

                    boolean isPermanentError = errorMessage.contains("Video unavailable")
                            || errorMessage.contains("Private video")
                            || errorMessage.contains("This video is not available")
                            || errorMessage.contains("deleted")
                            || errorMessage.contains("removed")
                            || errorMessage.contains("blocked")
                            || errorMessage.contains("unavailable")
                            || errorMessage.contains("Sign in")
                            || errorMessage.contains("age verification")
                            || errorMessage.contains("login required")
                            || errorMessage.contains("This video may be inappropriate");

                    if (isPermanentError) {
                        // MOSTRAR POPUP AL USUARIO
                        showErrorPopupAndRemoveTrack(item, trackIndex, errorMessage);
                    } else {
                        finalTrack.status = TrackStatus.NOT_DOWNLOADED;
                    }
                });
                finalTrack.status = TrackStatus.NOT_DOWNLOADED;

            } finally {
                int remaining = activeDownloads.decrementAndGet();
                log("📊 Descarga finalizada. Descargas activas restantes: " + remaining);
            }
        });
    }
    
    public static boolean isDownloadInProgress() {
        // Verificar en todos los tracks
        for (Track track : Attributes.trackCache.values()) {
            if (track.status == TrackStatus.DOWNLOADING) {
                log("⏳ Hay descarga en progreso: " + track.title);
                return true;
            }
        }
        return false;
    }
}
