package com.faklify.services;

import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.playProgress;
import static com.faklify.attributesGenerics.Attributes.remoteServer;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.serverExecutor;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.filesCache.FileCache.saveCurrentSession;
import javafx.application.Platform;

public class CloseProgram {

    public static void stop() {

        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        
        // Detener el servidor HTTP inmediatamente
        if (remoteServer != null) {
            try {
                remoteServer.stop(1);
            } catch (Exception e) {
            }
        }

        if (mediaPlayer != null) {
            try {
                //mediaPlayer.events().removeMediaPlayerEventListener(this);
                if (mediaPlayer.status().isPlaying()) {
                    mediaPlayer.controls().stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Throwable t) {
            }
        }

        // Reset de variables
        savedPlaybackTime = 0;
        isPlaying = false;

        // 2. Tu limpieza de UI (Mantenida por coherencia de código)
        Platform.runLater(() -> {
            if (playProgress != null) {
                playProgress.setProgress(0);
            }
            if (timeLabel != null) {
                timeLabel.setText("00:00 / 00:00");
            }
        });

        // Guardar sesión
        saveCurrentSession();

        // 3. El "tiro de gracia" para liberar el puerto 8080 sí o sí
        System.exit(0);
    }
}
