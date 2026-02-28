package com.faklify.UI.playSection;

import static com.faklify.UI.createBtn.Btn.createControlBtn;
import static com.faklify.services.PlayNext.playNext;
import static com.faklify.services.PlayPrev.playPrev;
import static com.faklify.actionsPlayer.ActionsPlayer.handleTrackSelection;
import static com.faklify.attributesGenerics.Attributes.cacheInfoLabel;
import static com.faklify.attributesGenerics.Attributes.mediaPlayer;
import static com.faklify.attributesGenerics.Attributes.nextBtn;
import static com.faklify.attributesGenerics.Attributes.nowPlayingLabel;
import static com.faklify.attributesGenerics.Attributes.playPauseBtn;
import static com.faklify.attributesGenerics.Attributes.playProgress;
import static com.faklify.attributesGenerics.Attributes.playlist;
import static com.faklify.attributesGenerics.Attributes.prevBtn;
import static com.faklify.attributesGenerics.Attributes.programmaticSelection;
import static com.faklify.attributesGenerics.Attributes.savedPlaybackTime;
import static com.faklify.attributesGenerics.Attributes.seekSlider;
import static com.faklify.attributesGenerics.Attributes.shuffleBtn;
import static com.faklify.attributesGenerics.Attributes.timeLabel;
import static com.faklify.attributesGenerics.Attributes.trackListView;
import static com.faklify.attributesGenerics.Attributes.volumeSlider;
import static com.faklify.services.Cleaning.clearPlaylist;
import static com.faklify.services.PlayCurrent.togglePlayPause;
import static com.faklify.services.Shuffle.toggleShuffleMode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class Play {
    public static VBox createPlayPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        panel.setStyle("-fx-background-color: #f5f5f5;");

        nowPlayingLabel = new Label("Selecciona una canción de la lista");
        nowPlayingLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        playProgress = new ProgressBar(0);
        playProgress.setPrefWidth(600);
        playProgress.setPrefHeight(10);

        seekSlider = new Slider(0, 100, 0);
        seekSlider.setPrefWidth(600);
        seekSlider.setPrefHeight(20);
        seekSlider.setStyle("-fx-control-inner-background: #3498db;");

        seekSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (seekSlider.isValueChanging() || seekSlider.isPressed()) {
                if (mediaPlayer != null && mediaPlayer.status().length() > 0) {
                    long newTime = (long) (mediaPlayer.status().length() * newVal.doubleValue() / 100.0);
                    mediaPlayer.controls().setTime(newTime);
                    savedPlaybackTime = newTime;
                }
            }
        });

        timeLabel = new Label("00:00 / 00:00");

        // CONTROLES DE REPRODUCCIÓN
        HBox mainControls = new HBox(20);
        mainControls.setAlignment(Pos.CENTER);

        prevBtn = createControlBtn("⏮", "#3498db");
        playPauseBtn = createControlBtn("▶", "#27ae60");
        nextBtn = createControlBtn("⏭", "#3498db");

        prevBtn.setOnAction(e -> playPrev());
        playPauseBtn.setOnAction(e -> togglePlayPause());
        nextBtn.setOnAction(e -> playNext());

        mainControls.getChildren().addAll(prevBtn, playPauseBtn, nextBtn);

        // FILA DE VOLUMEN Y ALEATORIO
        HBox secondaryControls = new HBox(15);
        secondaryControls.setAlignment(Pos.CENTER);

        Label volumeLabel = new Label("🔊");
        volumeLabel.setStyle("-fx-font-size: 16px;");

        volumeSlider = new Slider(0, 100, 50);
        volumeSlider.setPrefWidth(200);
        volumeSlider.setStyle("-fx-control-inner-background: #2ecc71;");
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaPlayer != null) {
                mediaPlayer.audio().setVolume(newVal.intValue());
            }
        });

        // Movido aquí para no estorbar el centro
        shuffleBtn = createControlBtn("🔀 OFF", "#95a5a6");
        shuffleBtn.setOnAction(e -> toggleShuffleMode());

        secondaryControls.getChildren().addAll(volumeLabel, volumeSlider, shuffleBtn);

        // RESTO DE LA INTERFAZ
        cacheInfoLabel = new Label("Descargas activas: 0 | En caché: 0");
        cacheInfoLabel.setStyle("-fx-font-weight: bold;");

        Button clearListBtnSmall = new Button("Limpiar lista");
        clearListBtnSmall.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 5;");
        clearListBtnSmall.setOnAction(e -> {
            if (playlist == null || playlist.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Lista vacía");
                alert.setHeaderText(null);
                alert.setContentText("No hay canciones en la lista para eliminar.");
                alert.showAndWait();
            } else {
                clearPlaylist();
            }
        });

        HBox listFooter = new HBox();
        listFooter.setAlignment(Pos.CENTER_LEFT);
        listFooter.getChildren().add(clearListBtnSmall);

        trackListView = new ListView<>();
        trackListView.setPrefHeight(250);
        trackListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
            if (!programmaticSelection && newIdx.intValue() >= 0 && newIdx.intValue() < playlist.size()) {
                handleTrackSelection(newIdx.intValue());
            }
        });

        panel.getChildren().addAll(
                nowPlayingLabel,
                seekSlider,
                timeLabel,
                mainControls,
                secondaryControls,
                new Separator(),
                cacheInfoLabel,
                trackListView,
                listFooter
        );

        return panel;
    }
}
