package com.faklify.ytdlp;

import com.faklify.DTOs.PlaylistItem;
import static com.faklify.attributesGenerics.Attributes.factory;
import static com.faklify.UI.UserInterface.updateUI;
import static com.faklify.attributesGenerics.Attributes.PLAYLIST_LOCK;
import static com.faklify.services.PlayNext.playNext;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.currentIndex;
import static com.faklify.attributesGenerics.Attributes.isPlaying;
import static com.faklify.attributesGenerics.Attributes.lastPlayedTimes;
import static com.faklify.attributesGenerics.Attributes.lastUiUpdateTime;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.playProgress;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.seekSlider;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import com.faklify.interfaces.DirectKernel32;
import static com.faklify.methodsGenerics.Methods.formatTime;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.methodsGenerics.Methods.showAlert;
import static com.faklify.methodsGenerics.Methods.showRestartAlert;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

public class YtDLP {

    private void initVLC() {
        try {
            // Configuración de JNA para modo portable
            System.setProperty("jna.nounpack", "true");
            System.setProperty("jna.nosys", "true");

            String appPath = System.getProperty("user.dir");
            File vlcFolder = new File(appPath, "vlc_runtime");
            String finalVlcPath = vlcFolder.getAbsolutePath();

            // FORZAR RUTA EN WINDOWS (Usando nuestra interfaz DirectKernel32)
            if (com.sun.jna.Platform.isWindows()) {
                log("🔗 Enlazando DLLs de Windows en: " + finalVlcPath);

                DirectKernel32.INSTANCE.SetDllDirectoryW(finalVlcPath);
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", finalVlcPath);
            }

            System.setProperty("jna.library.path", finalVlcPath);
            System.setProperty("VLC_PLUGIN_PATH", finalVlcPath + File.separator + "plugins");

            // Inicialización de VLCJ
            String[] vlcArgs = {
                "--no-video",
                "--plugin-path=" + finalVlcPath + File.separator + "plugins",
                "--network-caching=1500",
                "--quiet"
            };

            factory = new MediaPlayerFactory(vlcArgs);
            mediaPlayer = factory.mediaPlayers().newMediaPlayer();

            if (mediaPlayer != null) {
                mediaPlayer.audio().setVolume(50);
                setupVlcEvents();
                log("✅ ¡SÍ! VLC ha despertado.");
            }

        } catch (Throwable t) {
            log("❌ Error en el motor: " + t.getMessage());
            if (t.getCause() != null) {
                log("🔍 Causa: " + t.getCause().toString());
            }
            t.printStackTrace();
        }
    }

    public static void executePowerShellUpdate(java.util.concurrent.CountDownLatch latch) {
        Platform.runLater(() -> {
            // Configuración de la alerta de progreso
            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
            progressAlert.setTitle("Instalador yt-dlp");
            progressAlert.setHeaderText("Instalando dependencias del sistema...");

            ProgressBar progressBar = new ProgressBar(-1);
            progressBar.setPrefWidth(300);

            VBox content = new VBox(10);
            content.getChildren().addAll(
                    new Label("Se ha abierto una ventana de PowerShell."),
                    new Label("1. Acepta los permisos de administrador.\n2. Espera a que la ventana azul se cierre sola."),
                    progressBar
            );

            progressAlert.getDialogPane().setContent(content);
            progressAlert.show();

            // Hilo secundario para no bloquear la interfaz
            new Thread(() -> {
                File tempScript = null;
                boolean exito = false; // Variable de control

                try {
                    String installDir = "C:\\yt-dlp";
                    String finalPath = installDir + "\\yt-dlp.exe";

                    tempScript = File.createTempFile("install_ytdlp", ".ps1");

                    String scriptContent = "$ErrorActionPreference = 'Stop'; "
                            + "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; "
                            + "Write-Host '--- Cargando recursos ---' -ForegroundColor Cyan; "
                            + "if (!(Test-Path '" + installDir + "')) { "
                            + "  New-Item -ItemType Directory -Path '" + installDir + "' -Force | Out-Null; "
                            + "}; "
                            + "Invoke-WebRequest -Uri 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe' -OutFile '" + finalPath + "'; "
                            + "$p = [Environment]::GetEnvironmentVariable('Path','Machine'); "
                            + "if ($p -notlike '*" + installDir + "*') { "
                            + "  [Environment]::SetEnvironmentVariable('Path',\"$p;" + installDir + "\",'Machine'); "
                            + "}; "
                            + "Write-Host 'Recursos cargados exitosamente.' -ForegroundColor Green; "
                            + "Start-Sleep -Seconds 2;";

                    Files.writeString(tempScript.toPath(), scriptContent);

                    ProcessBuilder pb = new ProcessBuilder(
                            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                            "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','" + tempScript.getAbsolutePath() + "' -Wait"
                    );

                    Process process = pb.start();
                    process.waitFor(); // Java espera aquí el cierre de la ventana azul

                    // Verificación de integridad
                    File file = new File(finalPath);
                    exito = file.exists() && file.length() > 5000000;

                    final boolean resultadoFinal = exito;

                    Platform.runLater(() -> {
                        progressAlert.close();
                        if (resultadoFinal) {
                            showRestartAlert();
                        } else {
                            // Informamos y liberamos el latch para que la app abra.
                            showAlert("Error de Instalación", "No se detectó el archivo. Se abrirá la aplicación.");
                            if (latch != null) {
                                latch.countDown();
                            }
                        }
                    });

                } catch (Exception e) {
                    System.err.println("[ERROR] " + e.getMessage());
                    Platform.runLater(() -> {
                        progressAlert.close();
                        showAlert("Fallo Crítico", "Error: " + e.getMessage());
                        // Liberamos el latch para no dejar la app congelada.
                        if (latch != null) {
                            latch.countDown();
                        }
                    });
                } finally {
                    if (tempScript != null && tempScript.exists()) {
                        tempScript.delete();
                    }
                    // Quitamos el countDown de aquí para controlarlo arriba
                    // según si hubo éxito o no.
                }
            }).start();
        });
    }

    private void setupVlcEvents() {
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = true;
                    updateUI();

                    synchronized (PLAYLIST_LOCK) {
                        if (currentIndex >= 0 && currentIndex < playlist.size()) {
                            PlaylistItem item = playlist.get(currentIndex);
                            lastPlayedTimes.put(item.videoId, System.currentTimeMillis());
                        }
                    }
                });
            }

            @Override
            public void paused(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = false;
                    updateUI();
                });
            }

            @Override
            public void stopped(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = false;
                    savedPlaybackTime = 0;
                    updateUI();
                });
            }

            @Override
            public void finished(MediaPlayer mp) {
                Platform.runLater(() -> {
                    log("✅ Canción terminada");
                    savedPlaybackTime = 0;
                    playNext();
                });
            }

            @Override
            public void timeChanged(MediaPlayer mp, long newTime) {
                Platform.runLater(() -> updateTime(newTime));
            }

            @Override
            public void error(MediaPlayer mp) {
                Platform.runLater(() -> {
                    log("❌ Error reproducción");
                    savedPlaybackTime = 0;
                    playNext();
                });
            }
        });
    }

    private void updateTime(long currentTime) {
        // Solo actualizamos la UI cada 500ms, no cada vez que VLC mande el evento (ahorra CPU)
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateTime < 500) {
            return;
        }
        lastUiUpdateTime = now;

        Platform.runLater(() -> {
            if (mediaPlayer == null || !isPlaying) {
                return;
            }
            long totalTime = mediaPlayer.status().length();
            if (totalTime > 0) {
                double progress = (double) currentTime / totalTime;
                playProgress.setProgress(progress);
                if (!seekSlider.isValueChanging()) {
                    seekSlider.setValue(progress * 100);
                }
                timeLabel.setText(formatTime(currentTime) + " / " + formatTime(totalTime));
            }
        });
    }

    public static boolean checkYtDlpSilent() {
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void showYtDlpInstallHelp() {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Actualizar yt-dlp");
            alert.setHeaderText("¿Actualizar yt-dlp?");
            alert.setContentText("Se descargará la última versión usando PowerShell con permisos de administrador.");

            ButtonType updateButton = new ButtonType("Actualizar", ButtonBar.ButtonData.YES);
            ButtonType cancelButton = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(updateButton, cancelButton);

            alert.showAndWait().ifPresent(response -> {
                if (response == updateButton) {
                    executePowerShellUpdate(null);
                }
            });
        });
    }
}
