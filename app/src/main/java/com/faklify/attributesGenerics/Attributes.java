package com.faklify.attributesGenerics;

import com.faklify.DTOs.PlaylistItem;
import com.faklify.DTOs.Track;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

public class Attributes {
    // CONSTANTES SOBRE LA REPRODUCCIÓN
    public static Slider volumeSlider;
    public static Label nowPlayingLabel;
    public static final List<PlaylistItem> playlist = Collections.synchronizedList(new ArrayList<>());
    public static MediaPlayer mediaPlayer;
    public static volatile long savedPlaybackTime = 0;
    public static volatile boolean isPlaying = false;
    public static volatile int currentIndex = -1;
    public static Button playPauseBtn, nextBtn, prevBtn, shuffleBtn;
    public static final Map<String, Track> trackCache = new ConcurrentHashMap<>();
    public static volatile boolean showingDownloadAlert = false;
    public static AtomicInteger activeDownloads = new AtomicInteger(0);
    public static volatile boolean shuffleMode = false;
    public static final List<Integer> shuffleOrder = Collections.synchronizedList(new ArrayList<>());
    public static final Random random = new Random();
    public static final Map<String, Boolean> failedVideos = new ConcurrentHashMap<>();
    public static ListView<String> trackListView;
    public static final int MAX_DOWNLOADS = 2;
    public static ExecutorService downloadExecutor = new ThreadPoolExecutor(
            MAX_DOWNLOADS, MAX_DOWNLOADS, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500), // Límite de 500 tareas en espera
            new ThreadPoolExecutor.CallerRunsPolicy() // Si se llena, el hilo que llama ayuda (evita crash)
    );
    public static Path cacheDir;
    public static final int PRELOAD_AHEAD = 2;
    public static Label timeLabel;
    public static Slider seekSlider;
    public static MediaPlayerFactory factory;
    public static final Map<String, Long> lastPlayedTimes = new ConcurrentHashMap<>();
    public static final Set<String> currentSessionUrls = new LinkedHashSet<>();
    public static ScheduledExecutorService monitorExecutor;
    public static TextField urlField;
    public static Button loadBtn;
    public static Label loadStatus;
    public static String videoIdToRestore = null;
    public static String durationToRestore = "00:00";
    
    // CONSTANTES RELACIANADAS CON LA VISTA
    public static boolean programmaticSelection = false;
    public static Label cacheInfoLabel;
    public static long lastUiUpdateTime = 0;
    public static ProgressBar playProgress;
    public static final Object PLAYLIST_LOCK = new Object();
    
    // CONSTANTES SOBRE EL SERVIDOR REMOTO
    public static boolean serverStarted = false;
    public static HttpServer remoteServer;
    public static ExecutorService serverExecutor;
    
    // MODO DEBBUG PARA MOSTRAR "SOUTS"
    public static final boolean DEBUG_MODE = false;
    
    // CONSTANTES SOBRE LOS ARCHIVOS LOCALES
    public static final long MIN_FILE_SIZE = 102400L; // 100KB mínimo
    public static final String AUDIO_FORMAT = "opus";
    public static final String FAILED_VIDEOS_FILE = "failed_videos.dat";
    public static Path ffmpegDir = null;
    public static final String CACHE_DIR = ".yt_smart_cache";
    public static final File SESSION_FILE = new File(System.getProperty("user.home") + File.separator
            + CACHE_DIR, "session.dat");
    public static final long CLEANUP_INTERVAL_HOURS = 6;
    public static ScheduledExecutorService cleanupExecutor;
    public static final long MAX_CACHE_SIZE_MB = 500;          
    public static final long CLEANUP_DAYS = 7;                
    public static final long MAX_CACHE_AGE_DAYS = 30;          
    public static final long RECENT_PLAY_HOURS = 24;
    public static final long MIN_FREE_SPACE_MB = 1024;
    public static Path vlcInternalDir = null;
    
    // CONSTANTE SOBRE FFMPEG
    public static Path ffmpegTempDir;
    
    // CONSTANTE SOBRE DONACIONES
    public static boolean pendingDonationPopup = false;
}
