package com.faklify;

import static com.faklify.DTOs.PlaylistItem.continuePlaylistLoad;
import static com.faklify.UI.importSection.Import.createLoadPanel;
import static com.faklify.UI.playSection.Play.createPlayPanel;
import static com.faklify.vlc.Vlc.initVLC;
import static com.faklify.actionsPlayer.ActionsPlayer.updateTrackList;
import static com.faklify.donations.Donation.shouldShowMonthlyDonation;
import static com.faklify.donations.Donation.showDonationDialog;
import static com.faklify.filesCache.FileCache.initCache;
import com.faklify.remoteAccess.RemoteAccess;
import static com.faklify.ytdlp.YtDLP.checkYtDlpSilent;
import static com.faklify.attributesGenerics.Attributes.cleanupExecutor;
import static com.faklify.attributesGenerics.Attributes.currentSessionUrls;
import static com.faklify.attributesGenerics.Attributes.monitorExecutor;
import static com.faklify.attributesGenerics.Attributes.ffmpegDir;
import static com.faklify.attributesGenerics.Attributes.pendingDonationPopup;
import com.faklify.ffmpeg.Ffmpeg;
import static com.faklify.filesCache.FileCache.preLoadSessionData;
import static com.faklify.filesCache.FileCache.startAutoCleanup;
import com.faklify.methodsGenerics.Methods;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.Cleaning.cleanup;
import static com.faklify.services.CloseProgram.stop;
import static com.faklify.ytdlp.YtDLP.executePowerShellUpdate;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

import java.io.*;
import java.util.concurrent.*;
import uk.co.caprica.vlcj.media.MediaRef;
import uk.co.caprica.vlcj.media.TrackType;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;

public class Faklify extends Application implements MediaPlayerEventListener {

    @Override
    public void init() {
        monitorExecutor = Executors.newScheduledThreadPool(1);
        cleanupExecutor = Executors.newScheduledThreadPool(1);

        boolean ytdlpOk = checkYtDlpSilent();

        if (!ytdlpOk) {
            // CASO A: No hay yt-dlp

            // Marcamos la fecha instantaneamente, si esperamos a después del latch.await(),
            // el System.exit(0) del reinicio matará la app antes de guardar el archivo.
            shouldShowMonthlyDonation();

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            Platform.runLater(() -> {
                showDonationDialog();

                // Primero lanzamos la descarga y LUEGO liberamos el latch
                executePowerShellUpdate(latch);
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // El return protege que no siga cargando cosas si no hay yt-dlp
            if (!checkYtDlpSilent()) {
                return;
            }

        } else {
            // CASO B: Hay yt-dlp (o es el reinicio tras instalar)

            // Como ya creamos el archivo en el CASO A antes de cerrar, 
            // ahora devolverá 'false' porque hace menos de 1 minuto que se creó.
            if (shouldShowMonthlyDonation()) {
                pendingDonationPopup = true;
            }
        }

        // Inicialización del resto de la app
        initCache();
        initVLC();
        startAutoCleanup();

        try {
            ffmpegDir = new Ffmpeg().ensureFfmpegAvailable();
        } catch (IOException ex) {
            log("No se pudo cargar ffmpeg.");
        }
        RemoteAccess.startRemoteControlServer();
    }

    public static void main(String[] args) {
        // Configurar propiedades del sistema antes de iniciar
        System.setProperty("vlcj.log", "WARN");  // Reducir logging de vlcj
        System.setProperty("jna.nounpack", "true");  // Evitar unpacking de JNA
        System.setProperty("jna.nosys", "true");  // No cargar desde system path

        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        stage.setTitle("Faklify");

        preLoadSessionData();

        TabPane tabs = new TabPane();

        Tab loadTab = new Tab("📋 Importar");
        loadTab.setContent(createLoadPanel());
        loadTab.setClosable(false);

        Tab playTab = new Tab("🎵 Reproducir");
        playTab.setContent(createPlayPanel());
        playTab.setClosable(false);
        playTab.setDisable(true);

        tabs.getTabs().addAll(loadTab, playTab);

        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == playTab) {
                updateTrackList();
            }
        });

        Scene scene = new Scene(tabs, 950, 750);
        stage.setScene(scene);
        if (!currentSessionUrls.isEmpty()) {
            // Usamos la primera URL para rellenar la lista
            for (String url : currentSessionUrls) {
                continuePlaylistLoad(url);
            }
        }
        stage.show();

        // Ahora que la interfaz existe, restauramos la sesión
        Platform.runLater(() -> {
            if (pendingDonationPopup) {
                showDonationDialog();
                pendingDonationPopup = false; // Resetear para que no repita
            }
        });

        monitorExecutor.scheduleAtFixedRate(Methods::updateStats, 1, 1, TimeUnit.SECONDS);

        stage.setOnCloseRequest(e -> {
            cleanup();
            try {
                stop();
            } catch (Exception ex) {
                System.getLogger(Faklify.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
            }
            Platform.exit();
            System.exit(0);
        });
    }

    @Override
    public void mediaChanged(MediaPlayer mp, MediaRef mr) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void opening(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void buffering(MediaPlayer mp, float f) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void playing(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void paused(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void stopped(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void forward(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void backward(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void finished(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void timeChanged(MediaPlayer mp, long l) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void positionChanged(MediaPlayer mp, float f) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void seekableChanged(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void pausableChanged(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void titleChanged(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void snapshotTaken(MediaPlayer mp, String string) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void lengthChanged(MediaPlayer mp, long l) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void videoOutput(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void scrambledChanged(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void elementaryStreamAdded(MediaPlayer mp, TrackType tt, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void elementaryStreamDeleted(MediaPlayer mp, TrackType tt, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void elementaryStreamSelected(MediaPlayer mp, TrackType tt, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void corked(MediaPlayer mp, boolean bln) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void muted(MediaPlayer mp, boolean bln) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void volumeChanged(MediaPlayer mp, float f) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void audioDeviceChanged(MediaPlayer mp, String string) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void chapterChanged(MediaPlayer mp, int i) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void error(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void mediaPlayerReady(MediaPlayer mp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
}
