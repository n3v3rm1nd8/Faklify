package com.faklify;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import uk.co.caprica.vlcj.media.MediaRef;
import uk.co.caprica.vlcj.media.TrackType;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;

public class Faklify extends Application implements MediaPlayerEventListener {

    private MediaPlayerFactory factory;
    
    private static final String CACHE_DIR = ".yt_smart_cache";
    private static final File SESSION_FILE = new File(System.getProperty("user.home") + File.separator
            + CACHE_DIR, "session.dat");
    
    // Lista para rastrear las URLs cargadas actualmente en la sesión
    private final Set<String> currentSessionUrls = new LinkedHashSet<>();
    private static final String FAILED_VIDEOS_FILE = "failed_videos.dat";
    private static final String AUDIO_FORMAT = "opus";
    private static final int PRELOAD_AHEAD = 2;
    private static final int MAX_DOWNLOADS = 2;
    private static final long MIN_FILE_SIZE = 102400L; // 100KB mínimo

    // Configuración de limpieza automática
    private static final long MAX_CACHE_SIZE_MB = 500;          // Máximo 500MB
    private static final long CLEANUP_DAYS = 7;                 // Archivos de +7 días son "viejos"
    private static final long MAX_CACHE_AGE_DAYS = 30;          // ⭐ NUEVO: Eliminar TODO después de 30 días
    private static final long RECENT_PLAY_HOURS = 24;          // "Reciente" = últimas 24h
    private static final long CLEANUP_INTERVAL_HOURS = 6;       // Revisar cada 6 horas
    private static final long MIN_FREE_SPACE_MB = 1024;         // Dejar siempre 1GB libre

    private MediaPlayer mediaPlayer;
    private Path cacheDir;
    private Path ffmpegTempDir;
    private Path ffmpegDir = null;
    private Path vlcInternalDir = null;

    private final List<PlaylistItem> playlist = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Track> trackCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPlayedTimes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failedVideos = new ConcurrentHashMap<>();
    private volatile int currentIndex = -1;
    private boolean programmaticSelection = false;

    // Para control de reproducción
    private volatile long savedPlaybackTime = 0;
    private volatile boolean isPlaying = false;

    // Para modo aleatorio
    private volatile boolean shuffleMode = false;
    private final List<Integer> shuffleOrder = Collections.synchronizedList(new ArrayList<>());
    private final Random random = new Random();

    private AtomicInteger activeDownloads = new AtomicInteger(0);
    private ExecutorService downloadExecutor = new ThreadPoolExecutor(
            MAX_DOWNLOADS, MAX_DOWNLOADS, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500), // Límite de 500 tareas en espera
            new ThreadPoolExecutor.CallerRunsPolicy() // Si se llena, el hilo que llama ayuda (evita crash)
    );
    private ScheduledExecutorService monitorExecutor;
    private ScheduledExecutorService cleanupExecutor;

    // Para control de descargas activas
    private volatile boolean showingDownloadAlert = false;

    private String videoIdToRestore = null;
    private String durationToRestore = "00:00"; // Nueva variable global temporal

    private boolean pendingDonationPopup = false;
    
    private TextField urlField;
    private Button loadBtn;
    private Label loadStatus;
    private long lastUiUpdateTime = 0;

    private Label nowPlayingLabel;
    private ProgressBar playProgress;
    private Slider seekSlider;
    private Slider volumeSlider;
    private Label timeLabel;
    private Button playPauseBtn, nextBtn, prevBtn, shuffleBtn;
    private ListView<String> trackListView;
    private Label cacheInfoLabel;

    private static final boolean DEBUG_MODE = false;
    
    
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
                this.pendingDonationPopup = true;
            }
        }

        // Inicialización del resto de la app
        initCache();
        initVLC();
        startAutoCleanup();

        try {
            ffmpegDir = ensureFfmpegAvailable();
        } catch (IOException ex) {
            log("No se pudo cargar ffmpeg.");
        }
    }

    // Método auxiliar para abrir la URL en el navegador predeterminado
    private void abrirNavegador(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (Exception ex) {
            log("No se pudo abrir el navegador: " + ex.getMessage());
        }
    }

    private void saveCurrentSession() {
        try {
            String vId = "NONE";
            String duration = "00:00";

            // Usamos el currentIndex que ya manejas en toda tu app
            synchronized (this) {
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

    private void initCache() {
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

    /**
     * Carga la lista de videos fallidos desde el archivo (IDs únicos)
     */
    private void loadFailedVideosFromFile() {
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

    /**
     * Guarda un video fallido en el archivo persistente (evita duplicados)
     */
    private void saveFailedVideoToFile(String videoId, String reason) {
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

    private void scanExistingCache() {
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

            this.factory = new MediaPlayerFactory(vlcArgs);
            this.mediaPlayer = factory.mediaPlayers().newMediaPlayer();

            if (this.mediaPlayer != null) {
                this.mediaPlayer.audio().setVolume(50);
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

    private void setupVlcEvents() {
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mp) {
                Platform.runLater(() -> {
                    isPlaying = true;
                    updateUI();

                    synchronized (Faklify.this) {
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

    private boolean isVideoFailed(String videoId) {
        return failedVideos.containsKey(videoId);
    }

    private void cleanupFailedVideosOnLoad() {
        synchronized (this) {
            Iterator<PlaylistItem> iterator = playlist.iterator();
            while (iterator.hasNext()) {
                PlaylistItem item = iterator.next();
                if (failedVideos.containsKey(item.videoId)) {
                    iterator.remove();
                    trackCache.remove(item.videoId);
                    log("🗑️ Removida canción fallida previa: " + item.title);
                }
            }

            if (currentIndex >= playlist.size()) {
                currentIndex = playlist.size() - 1;
            }
        }
    }
    
    private void toggleShuffleMode() {
        shuffleMode = !shuffleMode;

        if (shuffleMode) {
            generateShuffleOrder();
            log("🔀 Modo aleatorio ACTIVADO");
            Platform.runLater(() -> {
                shuffleBtn.setText("🔀 ON");
                shuffleBtn.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white; -fx-font-weight: bold;");
            });

            if (currentIndex >= 0 && playlist.size() > 1) {
                log("🔄 Precargando inmediatamente 2 canciones aleatorias...");
                immediateShufflePreload();
            }

        } else {
            shuffleOrder.clear();
            log("🔀 Modo aleatorio DESACTIVADO");
            Platform.runLater(() -> {
                shuffleBtn.setText("🔀 OFF");
                shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");
            });

            if (currentIndex >= 0) {
                scheduleNextPreloads(currentIndex);
            }
        }
    }

    private void generateShuffleOrder() {
        shuffleOrder.clear();

        List<Integer> availableIndices = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            if (i != currentIndex && !isVideoFailed(playlist.get(i).videoId)) {
                availableIndices.add(i);
            }
        }

        Collections.shuffle(availableIndices, random);

        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            shuffleOrder.add(currentIndex);
        }

        shuffleOrder.addAll(availableIndices);

        log("🔀 Orden aleatorio generado: " + shuffleOrder.size() + " canciones");
    }

    private void immediateShufflePreload() {
        downloadExecutor.submit(() -> {
            try {
                List<Integer> preloadIndices = new ArrayList<>();

                if (shuffleOrder.size() > 1) {
                    int startPos = shuffleOrder.indexOf(currentIndex);
                    if (startPos != -1) {
                        for (int i = 1; i <= PRELOAD_AHEAD; i++) {
                            int nextPos = startPos + i;
                            if (nextPos < shuffleOrder.size()) {
                                preloadIndices.add(shuffleOrder.get(nextPos));
                            }
                        }
                    }
                }

                // Si no hay suficientes en el orden actual, buscar reemplazos
                if (preloadIndices.size() < PRELOAD_AHEAD) {
                    List<Integer> replacementIndices = findReplacementIndices(preloadIndices, PRELOAD_AHEAD - preloadIndices.size(), true);
                    preloadIndices.addAll(replacementIndices);
                }

                int preloads = 0;
                for (int preloadIndex : preloadIndices) {
                    if (preloadIndex >= 0 && preloadIndex < playlist.size()) {
                        PlaylistItem item = playlist.get(preloadIndex);

                        if (isVideoFailed(item.videoId)) {
                            log("⚠️ Canción aleatoria marcada como fallida, buscando reemplazo...");
                            continue;
                        }

                        Track track = trackCache.get(item.videoId);

                        if (track != null && !track.isFileValid()
                                && track.status != TrackStatus.DOWNLOADING
                                && activeDownloads.get() < MAX_DOWNLOADS) {

                            downloadTrack(item, preloadIndex, false);
                            preloads++;
                            Thread.sleep(300);
                        }
                    }
                }

                if (preloads > 0) {
                    log("⚡ Precarga inmediata: " + preloads + " canciones aleatorias");
                } else {
                    log("ℹ️ Las canciones aleatorias ya están en caché");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("⚠️ Error en precarga inmediata: " + e.getMessage());
            }
        });
    }

    // Método para encontrar índices de reemplazo cuando una canción es fallida
    private List<Integer> findReplacementIndices(List<Integer> currentIndices, int countNeeded, boolean shuffleMode) {
        List<Integer> replacements = new ArrayList<>();

        if (countNeeded <= 0) {
            return replacements;
        }

        List<Integer> allPossibleIndices = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            // No incluir: índice actual, ya incluidos, o videos fallidos
            if (i != currentIndex
                    && !currentIndices.contains(i)
                    && !isVideoFailed(playlist.get(i).videoId)) {
                allPossibleIndices.add(i);
            }
        }

        if (shuffleMode) {
            // En modo aleatorio, mezclar aleatoriamente
            Collections.shuffle(allPossibleIndices, random);
        } else {
            // En modo normal, tomar los siguientes en orden
            Collections.sort(allPossibleIndices);
        }

        // Tomar los primeros 'countNeeded' disponibles
        for (int i = 0; i < Math.min(countNeeded, allPossibleIndices.size()); i++) {
            replacements.add(allPossibleIndices.get(i));
        }

        return replacements;
    }

    private int getNextShuffleIndex() {
        if (shuffleOrder.isEmpty() || !shuffleMode) {
            return -1;
        }

        int currentPos = -1;
        for (int i = 0; i < shuffleOrder.size(); i++) {
            if (shuffleOrder.get(i) == currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos == -1 || currentPos >= shuffleOrder.size() - 1) {
            generateShuffleOrder();

            if (shuffleOrder.size() > 1) {
                return shuffleOrder.get(1);
            } else {
                return -1;
            }
        }

        // Buscar siguiente canción que no sea fallida
        for (int i = currentPos + 1; i < shuffleOrder.size(); i++) {
            int candidateIndex = shuffleOrder.get(i);
            if (candidateIndex < playlist.size() && !isVideoFailed(playlist.get(candidateIndex).videoId)) {
                return candidateIndex;
            }
        }

        // Si no hay más canciones no fallidas, regenerar orden
        generateShuffleOrder();
        if (shuffleOrder.size() > 1) {
            return shuffleOrder.get(1);
        }

        return -1;
    }

    private int getPrevShuffleIndex() {
        if (shuffleOrder.isEmpty() || !shuffleMode) {
            return -1;
        }

        int currentPos = -1;
        for (int i = 0; i < shuffleOrder.size(); i++) {
            if (shuffleOrder.get(i) == currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos <= 0) {
            List<Integer> availableIndices = new ArrayList<>();
            for (int i = 0; i < playlist.size(); i++) {
                if (i != currentIndex && !isVideoFailed(playlist.get(i).videoId)) {
                    availableIndices.add(i);
                }
            }

            if (!availableIndices.isEmpty()) {
                return availableIndices.get(random.nextInt(availableIndices.size()));
            } else {
                return -1;
            }
        }

        // Buscar canción anterior que no sea fallida
        for (int i = currentPos - 1; i >= 0; i--) {
            int candidateIndex = shuffleOrder.get(i);
            if (candidateIndex < playlist.size() && !isVideoFailed(playlist.get(candidateIndex).videoId)) {
                return candidateIndex;
            }
        }

        return -1;
    }

    private List<Integer> getNextShuffleIndices(int count) {
        List<Integer> result = new ArrayList<>();

        if (!shuffleMode || shuffleOrder.isEmpty() || count <= 0) {
            return result;
        }

        int currentPos = -1;
        for (int i = 0; i < shuffleOrder.size(); i++) {
            if (shuffleOrder.get(i) == currentIndex) {
                currentPos = i;
                break;
            }
        }

        if (currentPos == -1) {
            return result;
        }

        // Tomar las siguientes 'count' canciones que no sean fallidas
        int found = 0;
        for (int i = currentPos + 1; i < shuffleOrder.size() && found < count; i++) {
            int candidateIndex = shuffleOrder.get(i);
            if (candidateIndex < playlist.size() && !isVideoFailed(playlist.get(candidateIndex).videoId)) {
                result.add(candidateIndex);
                found++;
            }
        }

        // Si no encontramos suficientes, buscar reemplazos
        if (found < count) {
            List<Integer> replacements = findReplacementIndices(result, count - found, true);
            result.addAll(replacements);
        }

        return result;
    }
    
    private void startAutoCleanup() {
        log("🔧 Iniciando limpieza automática (cada " + CLEANUP_INTERVAL_HOURS + " horas)");

        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanCacheIfNeeded();
            } catch (Exception e) {
                log("⚠️ Error en limpieza automática: " + e.getMessage());
            }
        }, 0, CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    private void cleanCacheIfNeeded() {
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

    private void logCacheStatus() {
        try {
            List<Path> files = Files.list(cacheDir)
                    .filter(p -> p.toString().endsWith("." + AUDIO_FORMAT))
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

            log("=== ESTADO DE CACHÉ ===");
            log("Total archivos: " + files.size());

            int filesOver30Days = 0;
            int filesOver7Days = 0;
            int filesRecent = 0;

            long totalSizeMB = 0;
            for (Path file : files) {
                try {
                    long sizeMB = Files.size(file) / (1024 * 1024);
                    long ageDays = getFileAgeDays(file);
                    String videoId = extractVideoId(file);

                    String status = "Reciente";
                    if (ageDays >= MAX_CACHE_AGE_DAYS) {
                        status = "🔴 ELIMINAR (30+ días)";
                        filesOver30Days++;
                    } else if (ageDays >= CLEANUP_DAYS && !isRecentlyPlayed(videoId)) {
                        status = "Eliminar pronto (7+ días)";
                        filesOver7Days++;
                    } else {
                        filesRecent++;
                    }

                    log(String.format("• %s: %dMB, %d días - %s",
                            file.getFileName(), sizeMB, ageDays, status));

                    totalSizeMB += sizeMB;
                } catch (IOException e) {
                    log("• " + file.getFileName() + ": ERROR al leer");
                }
            }

            File cacheFile = cacheDir.toFile();
            long freeSpaceMB = cacheFile.getFreeSpace() / (1024 * 1024);

            log(String.format("Total: %dMB/%dMB (%.0f%%)",
                    totalSizeMB, MAX_CACHE_SIZE_MB,
                    (totalSizeMB * 100.0 / MAX_CACHE_SIZE_MB)));
            log("Resumen por edad:");
            log("  • Recientes (<7 días): " + filesRecent + " archivos");
            log("  • Viejos (7-29 días): " + filesOver7Days + " archivos");
            log("  • Muy viejos (30+ días): " + filesOver30Days + " archivos - SERÁN ELIMINADOS");
            log("Espacio libre en disco: " + freeSpaceMB + "MB");
            log("Límite de edad: " + MAX_CACHE_AGE_DAYS + " días");

        } catch (IOException e) {
            log("Error monitoreando caché: " + e.getMessage());
        }
    }

    private boolean isCurrentlyPlaying(String videoId) {
        synchronized (this) {
            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistItem currentItem = playlist.get(currentIndex);
                return currentItem.videoId.equals(videoId);
            }
        }
        return false;
    }

    private boolean mustKeepFile(Path file, String videoId) throws IOException {
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

    private boolean canKeepFile(Path file, String videoId, long currentCacheSizeMB,
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

    private long getFileAgeDays(Path file) throws IOException {
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastModified) / (1000 * 60 * 60 * 24);
    }

    private String extractVideoId(Path file) {
        String filename = file.getFileName().toString();
        return filename.substring(0, filename.lastIndexOf('.'));
    }

    private boolean isInCurrentPlaylist(String videoId) {
        synchronized (this) {
            return playlist.stream()
                    .anyMatch(item -> item.videoId.equals(videoId));
        }
    }

    private boolean isRecentlyPlayed(String videoId) {
        Long lastPlayed = lastPlayedTimes.get(videoId);
        if (lastPlayed == null) {
            return false;
        }

        long hoursSincePlayed = (System.currentTimeMillis() - lastPlayed)
                / (1000 * 60 * 60);

        return hoursSincePlayed < RECENT_PLAY_HOURS;
    }

    private boolean deleteFileSafely(Path file) {
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

    public void manualCleanup() {
        log("Iniciando limpieza manual...");
        cleanCacheIfNeeded();
        logCacheStatus();
    }

    private void preLoadSessionData() {
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
    
    @Override
    public void start(Stage stage) {
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

        monitorExecutor.scheduleAtFixedRate(this::updateStats, 1, 1, TimeUnit.SECONDS);

        stage.setOnCloseRequest(e -> {
            cleanup();
            Platform.exit();
        });
    }
    
    private VBox createLoadPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        // Volvemos al fondo claro original
        panel.setStyle("-fx-background-color: #f8f9fa;");

        // Campo oculto para que la lógica de cambio de pestaña no explote
        urlField = new TextField();
        urlField.setVisible(false);
        urlField.setManaged(false);

        Label title = new Label("IMPORTA TUS PLAYLISTS FAVORITAS");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label desc = new Label("La URL de la playlist debe ser parecida a la siguiente "
                + "'https://www.youtube.com/watch?list=PLJgGrtkGfdGkkkUTG'");
        desc.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 12px;");

        // AGREGAR Y VER URLs
        HBox urlsSection = new HBox(15);
        urlsSection.setAlignment(Pos.TOP_LEFT);

        // Panel izquierdo: Agregar nueva URL
        VBox addPanel = new VBox(10);
        addPanel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 15; -fx-border-radius: 5;");
        addPanel.setPrefWidth(350);

        Label addTitle = new Label("➕ Agregar Nueva Playlist");
        addTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label nameLabel = new Label("Nombre:");
        TextField nameField = new TextField();
        nameField.setPromptText("Ej: Hip Hop");

        Label urlLabel = new Label("URL:");
        TextField urlSaveField = new TextField();
        urlSaveField.setPromptText("Pega URL de YouTube aquí...");

        Button saveBtn = new Button("💾 Guardar Playlist");
        saveBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-font-weight: bold;");
        saveBtn.setMaxWidth(Double.MAX_VALUE);

        Label savedTitle = new Label("📂 Playlists Guardadas");
        savedTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        ListView<String> savedPlaylistsList = new ListView<>();
        savedPlaylistsList.setPrefHeight(150);
        savedPlaylistsList.setPlaceholder(new Label("No hay playlists guardadas."));
        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String url = urlSaveField.getText().trim();
            if (!name.isEmpty() && !url.isEmpty() && isValidYouTubeUrl(url)) {
                savePlaylistToFile(name, url);
                nameField.clear();
                urlSaveField.clear();
                refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);
                loadStatus.setText("Playlist añadida correctamente");
            }
        });

        addPanel.getChildren().addAll(addTitle, nameLabel, nameField, urlLabel, urlSaveField, saveBtn);

        // Panel derecho: Playlists guardadas
        VBox savedPanel = new VBox(10);
        savedPanel.setStyle("-fx-background-color: #ffffff; -fx-padding: 15; -fx-border-color: #bdc3c7; -fx-border-width: 1; -fx-border-radius: 5;");
        savedPanel.setPrefWidth(450);

        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);

        // EL BOTÓN DE OBTENER LISTA
        loadBtn = new Button("📥 Importar");
        loadBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;");
        loadBtn.setOnAction(e -> {
            String selected = savedPlaylistsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                String url = getUrlForPlaylistName(selected);
                urlField.setText(url); // Seteamos el campo oculto
                loadPlaylistNamesOnly();
            }
        });

        Button deleteBtn = new Button("Eliminar");
        deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        deleteBtn.setOnAction(e -> {
            String selected = savedPlaylistsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                // Obtener la URL de la playlist que vamos a borrar
                String urlToDelete = getUrlForPlaylistName(selected);

                if (urlToDelete != null) {
                    log("🗑️ Eliminando rastro de: " + selected);

                    // Obtener las IDs de los videos de esa playlist para quitarlos de la reproducción
                    // Ejecutamos esto en un hilo aparte si es una lista muy grande para no congelar la UI
                    new Thread(() -> {
                        try {
                            // Obtenemos los items que pertenecen a esa URL específica
                            List<PlaylistItem> itemsToRemove = fetchPlaylistItems(urlToDelete);
                            Set<String> idsToRemove = itemsToRemove.stream()
                                    .map(item -> item.videoId)
                                    .collect(Collectors.toSet());

                            Platform.runLater(() -> {
                                // Limpiar de la lista de reproducción actual (pestaña Reproducir)
                                playlist.removeIf(item -> idsToRemove.contains(item.videoId));
                                loadStatus.setText("Playlist eliminada correctamente");

                                // Si la canción que suena está en la lista borrada, paramos o saltamos
                                if (currentIndex >= 0 && currentIndex < playlist.size()) {
                                    // Si el índice actual ya no es válido o la lista cambió mucho:
                                    updateTrackList();
                                } else if (playlist.isEmpty()) {
                                    stop(); // Método para parar música si te quedas sin canciones
                                    currentIndex = -1;
                                    updateTrackList();
                                } else {
                                    updateTrackList();
                                }

                                log("✅ Canciones de '" + selected + "' eliminadas de la cola.");
                            });
                        } catch (Exception ex) {
                            log("⚠️ Nota: No se pudo limpiar la cola de reproducción: " + ex.getMessage());
                        }
                    }).start();

                    // Borrar de la sesión activa para que no resucite al reiniciar
                    if (currentSessionUrls.remove(urlToDelete)) {
                        saveCurrentSession();
                    }
                }

                // Borrar del archivo de biblioteca (nombres.txt / urls.txt)
                deletePlaylistFromFile(selected);

                // Refrescar la lista visual de la derecha
                refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);
            }
        });

        actionButtons.getChildren().addAll(loadBtn, deleteBtn);
        savedPanel.getChildren().addAll(savedTitle, savedPlaylistsList, actionButtons);
        urlsSection.getChildren().addAll(addPanel, savedPanel);

        loadStatus = new Label("Listo para importar");
        loadStatus.setStyle("-fx-text-fill: #7f8c8d;");

        // Botones de pie
        HBox helpRow = new HBox(10);
        helpRow.setAlignment(Pos.CENTER_LEFT);
        Button ytdlpHelpBtn = new Button("Actualizar");
        ytdlpHelpBtn.setStyle("-fx-background-color: #6610f2; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
        ytdlpHelpBtn.setOnAction(e -> showYtDlpInstallHelp());

        Button donateBtn = new Button("☕ Apoyar Proyecto");
        donateBtn.setStyle("-fx-background-color: #f1c40f; -fx-text-fill: #2c3e50; -fx-font-weight: bold; -fx-font-size: 12px;");
        donateBtn.setOnAction(e -> showDonationDialog());
        helpRow.getChildren().addAll(ytdlpHelpBtn, donateBtn);

        // Organizar todo con los Separadores nativos que tenías
        panel.getChildren().addAll(
                title, desc, new Separator(),
                urlsSection,
                new Separator(),
                loadStatus,
                new Separator(),
                helpRow,
                urlField // El campo invisible al final
        );

        // Refrescar lista al inicio
        refreshSavedPlaylistsList(savedPlaylistsList, savedTitle);

        return panel;
    }

    private void showDonationDialog() {
        Alert donationAlert = new Alert(Alert.AlertType.CONFIRMATION);
        donationAlert.setTitle("INFO - Donación");
        donationAlert.setHeaderText("Aportación voluntaria");

        // Asignamos ButtonData.CANCEL_CLOSE al botón Cerrar
        // Esto vincula la "X" de la ventana a este botón
        ButtonType btnApoyar = new ButtonType("Apoyar");
        ButtonType btnCerrar = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);

        donationAlert.getButtonTypes().setAll(btnApoyar, btnCerrar);
        
        String urlPaypal = "https://paypal.me/nevermind399";
        Text texto = new Text("Gracias a ti, esta app seguirá creciendo y mejorando.\n"
                + "Si valoras mi trabajo, puedes realizar la donación que\n"
                + "quieras dandole al botón de 'Apoyar'.");

        TextFlow flow = new TextFlow(texto);
        donationAlert.getDialogPane().setContent(flow);

        // Para que no se alejen los botones
        // Forzamos a que la ButtonBar no use el orden del sistema
        ButtonBar buttonBar = (ButtonBar) donationAlert.getDialogPane().lookup(".button-bar");
        if (buttonBar != null) {
            buttonBar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
        }

        donationAlert.showAndWait().ifPresent(response -> {
            if (response == btnApoyar) {
                abrirNavegador(urlPaypal);
            }
            // Si pulsas la "X" o el botón Cerrar, simplemente sale del ifPresent y continúa
        });
    }

    private boolean shouldShowMonthlyDonation() {
        // Usamos tu constante para localizar la carpeta
        File cacheDir = new File(System.getProperty("user.home") + File.separator + CACHE_DIR);

        // Nos aseguramos de que la carpeta exista (aunque ya la crees en otro lado)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        File dateFile = new File(cacheDir, "last_check.dat");
        long currentTime = System.currentTimeMillis();
        long thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000;

        try {
            if (dateFile.exists()) {
                // Leemos el timestamp guardado
                String content = Files.readString(dateFile.toPath()).trim();
                long lastShown = Long.parseLong(content);

                // Si no ha pasado el tiempo suficiente, retornamos false
                if (currentTime - lastShown < thirtyDaysInMillis) {
                    return false;
                }
            }

            // Si el archivo no existe o ya pasó el mes:
            // Actualizamos el archivo con el tiempo actual y retornamos true
            Files.writeString(dateFile.toPath(), String.valueOf(currentTime));
            return true;

        } catch (Exception e) {
            // En caso de error de lectura/escritura, mejor mostrarlo para no fallar
            return true;
        }
    }
    
// Guardar playlist con nombre
    private void savePlaylistToFile(String name, String url) {
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

// Cargar nombres de playlists
    private List<String> loadSavedPlaylistNames() {
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

// Obtener URL para un nombre de playlist
    private String getUrlForPlaylistName(String playlistName) {
        try {
            Path playlistsFile = cacheDir.resolve("saved_playlists.dat");
            if (Files.exists(playlistsFile)) {
                List<String> lines = Files.readAllLines(playlistsFile);

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        int separatorIndex = trimmed.indexOf(" - ");
                        if (separatorIndex != -1) {
                            String name = trimmed.substring(0, separatorIndex).trim();
                            if (name.equals(playlistName)) {
                                // Extraer la URL (todo después del " - ")
                                String url = trimmed.substring(separatorIndex + 3).trim();
                                return url;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log("⚠️ Error al buscar URL: " + e.getMessage());
        }
        return null;
    }

// Eliminar playlist por nombre
    private void deletePlaylistFromFile(String playlistName) {
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

// Método para refrescar la lista de playlists
    private void refreshSavedPlaylistsList(ListView<String> listView, Label titleLabel) {
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

    private boolean isValidYouTubeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String urlLower = url.toLowerCase();
        return urlLower.contains("youtube.com") || urlLower.contains("youtu.be");
    }

    private VBox createPlayPanel() {
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

    /**
     * Limpia la lista de reproducción actual sin borrar archivos guardados
     */
    private void clearPlaylist() {
        // Verificar si hay descargas en progreso
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        if (playlist == null || playlist.isEmpty()) {
            showAlert("Lista vacía", "No hay canciones en la cola de reproducción para limpiar.");
            return;
        }

        // Pedir confirmación al usuario
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Limpiar cola de reproducción");
        alert.setHeaderText("¿Estás seguro de que quieres limpiar la cola de reproducción?");
        alert.setContentText("Esta acción:\n"
                + "• Limpiará todas las canciones de la lista actual\n"
                + "• Detendrá la reproducción\n");

        // Personalizar los botones
        ButtonType yesButton = new ButtonType("Sí, limpiar", ButtonBar.ButtonData.YES);
        ButtonType noButton = new ButtonType("Cancelar", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(yesButton, noButton);

        // Mostrar y esperar respuesta
        alert.showAndWait().ifPresent(response -> {
            if (response == yesButton) {
                performPlaylistClearing();
            }
        });
    }

    /**
     * Realiza la limpieza de la lista de reproducción
     */
    private void performPlaylistClearing() {
        log("Limpiando lista de reproducción...");

        currentSessionUrls.clear();
        if (SESSION_FILE.exists()) {
            SESSION_FILE.delete();
        }

        // Detener la reproducción si está activa
        if (mediaPlayer != null && mediaPlayer.status().isPlaying()) {
            mediaPlayer.controls().stop();
        }

        // Resetear variables de reproducción
        savedPlaybackTime = 0;
        isPlaying = false;
        currentIndex = -1;

        // Limpiar listas
        synchronized (this) {
            playlist.clear();
            shuffleOrder.clear();
            failedVideos.clear(); // Opcional: también limpiar videos fallidos
        }

        // Resetear modo aleatorio
        shuffleMode = false;

        // Actualizar la interfaz
        Platform.runLater(() -> {
            // Actualizar etiquetas
            nowPlayingLabel.setText("Lista limpiada - Selecciona nueva playlist");
            playProgress.setProgress(0);
            seekSlider.setValue(0);
            timeLabel.setText("00:00 / 00:00");

            // Actualizar botones
            playPauseBtn.setText("▶");
            playPauseBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
            shuffleBtn.setText("🔀 OFF");
            shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");

            // Limpiar lista visual
            trackListView.getItems().clear();

            // Actualizar estadísticas
            updateStats();
            updateUI();
        });

        log("✅ Lista limpiada exitosamente");
    }

    private void togglePlayPause() {
        if (playlist.isEmpty()) {
            return;
        }

        if (mediaPlayer.status().isPlaying()) {
            // Si está sonando, pausamos y guardamos el tiempo por seguridad
            savedPlaybackTime = mediaPlayer.status().time();
            mediaPlayer.controls().pause();
            isPlaying = false;
            log("⏸ Pausado en: " + (savedPlaybackTime / 1000) + "s");
        } else {
            // Si NO está sonando, primero vemos si hay un medio ya cargado
            if (mediaPlayer.status().isPlayable()) {
                // Si el medio ya existe en el motor de VLC, solo damos 'play'
                mediaPlayer.controls().play();
                isPlaying = true;
                log("▶ Reanudando desde: " + (mediaPlayer.status().time() / 1000) + "s");
            } else {
                // Si no había nada cargado (primera vez o cambio de canción), usamos playCurrent
                playCurrent();
            }
        }
        updateUI();
    }

    private void handleTrackSelection(int index) {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            // Opcional: Re-seleccionar el currentIndex anterior para que el foco no se mueva visualmente si cancelamos
            return;
        }

        if (index == currentIndex && mediaPlayer != null && mediaPlayer.status().isPlaying()) {
            log("ℹ️ Ya reproduciendo esta canción");
            return;
        }

        final int selectedIndex = index;
        PlaylistItem item = playlist.get(selectedIndex);

        if (isVideoFailed(item.videoId)) {
            log("❌ Esta canción fue marcada como fallida anteriormente");
            return;
        }

        log("🎯 Seleccionada: " + item.title + " (índice " + selectedIndex + ")");

        Track track = trackCache.get(item.videoId);

        synchronized (this) {
            currentIndex = selectedIndex;
        }

        // Guardar sesión para que si cerramos la app tras hacer click, se acuerde
        saveCurrentSession();

        if (track != null && track.isFileValid()) {
            playCurrent();
        } else {
            downloadExecutor.submit(() -> {
                try {
                    Thread.sleep(100);
                    downloadTrack(item, selectedIndex, true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private Button createControlBtn(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle(String.format(
                "-fx-font-size: 16px; -fx-font-weight: bold; "
                + "-fx-background-color: %s; -fx-text-fill: white; "
                + "-fx-min-width: 60px; -fx-min-height: 40px;",
                color
        ));
        return btn;
    }

    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    private void loadPlaylistNamesOnly() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            showAlert("URL vacía", "Ingresa URL de playlist");
            return;
        }

        // Verificar yt-dlp antes de continuar
        if (!checkYtDlpSilent()) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("yt-dlp no encontrado");
                alert.setHeaderText("No se puede descargar sin yt-dlp");
                alert.setContentText("yt-dlp es necesario para obtener la lista de canciones.\n\n"
                        + "¿Quieres ver instrucciones para instalarlo?");

                ButtonType installBtn = new ButtonType("Sí, ver instrucciones");
                ButtonType continueBtn = new ButtonType("Continuar de todos modos");
                ButtonType cancelBtn = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);

                alert.getButtonTypes().setAll(installBtn, continueBtn, cancelBtn);

                alert.showAndWait().ifPresent(response -> {
                    if (response == installBtn) {
                        showYtDlpInstallHelp();
                    } else if (response == continueBtn) {
                        currentSessionUrls.add(url);
                        saveCurrentSession(); // Guardamos el estado actual
                        // Continuar con el método original
                        continuePlaylistLoad(url);
                    }
                    // Si cancela, no hacer nada
                });
            });
            return;
        }

        currentSessionUrls.add(url);
        saveCurrentSession(); // Guardamos el estado actual
        // Si yt-dlp está disponible, cargar normalmente
        continuePlaylistLoad(url);
    }

    /**
     * Continúa con la carga de playlist (separado para reutilización)
     */
    private void continuePlaylistLoad(String url) {
        // Estado inicial de la UI de carga
        if (loadBtn == null) {
            return;
        }
        loadBtn.setDisable(true);
        loadStatus.setText("Obteniendo lista...");

        // Si la lista está vacía, nos aseguramos de que el índice sea inicial
        if (playlist.isEmpty()) {
            currentIndex = -1;
        }

        shuffleOrder.clear();
        shuffleMode = false;
        failedVideos.clear();

        log("📋 Iniciando descarga de metadatos de la playlist...");

        downloadExecutor.submit(() -> {
            try {
                // Descarga de ítems (proceso pesado fuera del hilo de UI)
                List<PlaylistItem> newItems = fetchPlaylistItems(url);

                Platform.runLater(() -> {
                    // FILTRO ANTI-DUPLICADOS
                    Set<String> existingIds = playlist.stream()
                            .map(item -> item.videoId)
                            .collect(Collectors.toSet());

                    List<PlaylistItem> uniqueItems = newItems.stream()
                            .filter(item -> !existingIds.contains(item.videoId))
                            .collect(Collectors.toList());

                    int duplicatesCount = newItems.size() - uniqueItems.size();

                    if (uniqueItems.isEmpty() && !newItems.isEmpty()) {
                        log("⚠️ Playlist duplicada.");
                        showAlert("Playlist duplicada", "Esta playlist ya la cargaste anteriormente.");
                    } else {
                        // Añadir nuevos elementos y refrescar la vista
                        playlist.addAll(uniqueItems);
                        updateTrackList();

                        // Si estamos cargando una playlist adicional (no es restauración)
                        // mantenemos el foco en la canción que ya estaba seleccionada
                        if (videoIdToRestore == null && currentIndex >= 0) {
                            aplicarFocoYScroll(currentIndex);
                        }

                        log("✅ Añadidas " + uniqueItems.size() + " canciones nuevas");
                        loadStatus.setText("✅ Lista cargada: " + uniqueItems.size() + " nuevas");
                    }

                    cleanupFailedVideosOnLoad();

                    // CAMBIO AUTOMÁTICO DE PESTAÑA
                    TabPane tabs = (TabPane) urlField.getScene().lookup(".tab-pane");
                    if (tabs != null) {
                        tabs.getTabs().get(1).setDisable(false);
                        tabs.getSelectionModel().select(1);
                    }

                    updateTracksWithCacheInfo();

                    // RESTAURACIÓN DE SESIÓN (Solo ocurre al iniciar la App)
                    if (videoIdToRestore != null && !videoIdToRestore.equals("NONE")) {
                        for (int i = 0; i < playlist.size(); i++) {
                            PlaylistItem item = playlist.get(i);
                            if (item.videoId.equals(videoIdToRestore)) {
                                currentIndex = i;

                                // Actualizamos etiquetas visuales
                                nowPlayingLabel.setText("▶ " + item.title);
                                timeLabel.setText("00:00 / " + durationToRestore);

                                // Aplicamos el foco y scroll a la canción restaurada
                                aplicarFocoYScroll(currentIndex);

                                updateUI();
                                videoIdToRestore = null; // Limpiamos la bandera
                                break;
                            }
                        }
                    }

                    // Reset visual del botón Shuffle
                    shuffleBtn.setText("🔀 OFF");
                    shuffleBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    log("❌ Error: " + e.getMessage());
                    loadStatus.setText("Error cargando lista");

                    if (e.getMessage() != null && e.getMessage().contains("yt-dlp")) {
                        showYtDlpInstallHelp();
                    }
                });
            } finally {
                Platform.runLater(() -> loadBtn.setDisable(false));
            }
        });
    }

    /**
     * Método auxiliar para asegurar que el foco y el scroll funcionen
     * correctamente tras la carga de datos.
     */
    private void aplicarFocoYScroll(int index) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }

        Platform.runLater(() -> {
            // Verificación de seguridad: si la lista se vació justo antes de ejecutar este hilo
            if (trackListView == null || playlist.isEmpty() || index >= playlist.size()) {
                return;
            }

            programmaticSelection = true;

            trackListView.requestFocus();
            trackListView.getSelectionModel().select(index);
            trackListView.getFocusModel().focus(index);

            // Logica de Scroll Mínimo
            javafx.scene.control.skin.ListViewSkin<?> skin = (javafx.scene.control.skin.ListViewSkin<?>) trackListView.getSkin();
            if (skin != null && !skin.getChildren().isEmpty()) {
                javafx.scene.control.skin.VirtualFlow<?> flow = (javafx.scene.control.skin.VirtualFlow<?>) skin.getChildren().get(0);

                // Verificar que existan celdas visibles antes de pedir el Index
                var firstCell = flow.getFirstVisibleCell();
                var lastCell = flow.getLastVisibleCell();

                if (firstCell != null && lastCell != null) {
                    int first = firstCell.getIndex();
                    int last = lastCell.getIndex();

                    // Solo hacemos scroll si el índice está fuera del rango visible
                    if (index <= first || index >= last) {
                        trackListView.scrollTo(index);
                    }
                } else {
                    // Si no hay celdas visibles aún, scroll estándar por seguridad
                    trackListView.scrollTo(index);
                }
            } else {
                trackListView.scrollTo(index);
            }

            programmaticSelection = false;
        });
    }

    private List<PlaylistItem> fetchPlaylistItems(String playlistUrl) throws Exception {
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

    /**
     * Verifica si un video está marcado como fallido en el archivo
     */
    private boolean isVideoFailedInFile(String videoId) {
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
     * Limpia duplicados del archivo de videos fallidos
     */
    private void cleanupFailedVideosFile() {
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

// Método auxiliar para limpiar títulos
    private String cleanTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "Sin título";
        }

        // Eliminar caracteres de control y caracteres problemáticos
        title = title.replaceAll("[\\x00-\\x1F\\x7F]", "") // Caracteres de control
                .replaceAll("^[\\|\\t\\s]+", "") // Eliminar pipes, tabs y espacios al inicio
                .replaceAll("[\\|\\t\\s]+$", "") // Eliminar pipes, tabs y espacios al final
                .trim();

        // Si después de limpiar está vacío
        if (title.isEmpty()) {
            return "Sin título";
        }

        return title;
    }

    private void updateTracksWithCacheInfo() {
        for (PlaylistItem item : playlist) {
            Track track = trackCache.get(item.videoId);
            if (track != null) {
                if (track.status == TrackStatus.DOWNLOADED) {
                    if (!track.isFileValid()) {
                        track.status = TrackStatus.NOT_DOWNLOADED;
                        track.filePath = null;
                    }
                } else {
                    try {
                        Path expectedFile = cacheDir.resolve(item.videoId + "." + AUDIO_FORMAT);
                        if (Files.exists(expectedFile) && Files.size(expectedFile) > MIN_FILE_SIZE) {
                            track.filePath = expectedFile;
                            track.fileSize = Files.size(expectedFile);
                            track.status = TrackStatus.DOWNLOADED;
                        }
                    } catch (IOException ex) {
                        System.getLogger(Faklify.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                    }
                }
            }
        }
    }
    
    private void downloadTrack(PlaylistItem item, int index, boolean playWhenDone) {
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

        Track track = trackCache.get(videoId);
        if (track == null) {
            track = new Track();
            track.title = trackTitle;
            track.status = TrackStatus.NOT_DOWNLOADED;
            trackCache.put(videoId, track);
        }

        final Track finalTrack = track;

        if (finalTrack.isFileValid()) {
            log("✅ Ya descargado y válido: " + trackTitle);
            if (playWhenDone) {
                Platform.runLater(() -> {
                    if (currentIndex != trackIndex) {
                        currentIndex = trackIndex;
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

        Path expectedFile = cacheDir.resolve(videoId + "." + AUDIO_FORMAT);
        if (Files.exists(expectedFile)) {
            try {
                long fileSize = Files.size(expectedFile);
                if (fileSize > MIN_FILE_SIZE) {
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

    private void showErrorPopupAndRemoveTrack(PlaylistItem item, int trackIndex, String errorMessage) {
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
                // Llamamos directamente a tu método de limpieza permanente
                removeFailedVideoWithCleanup(item.videoId, errorType, trackIndex);
            } catch (Exception e) {
                log("❌ Error al intentar auto-eliminar: " + e.getMessage());
            }
        });
    }

    /**
     * Elimina una canción fallida y limpia archivos asociados (BÚSQUEDA POR
     * VIDEOID)
     */
    private void removeFailedVideoWithCleanup(String videoId, String errorReason, int originalTrackIndex) {
        synchronized (this) {
            // Marcar como fallido primero
            failedVideos.put(videoId, Boolean.TRUE);

            // GUARDAR EN ARCHIVO PERSISTENTE
            saveFailedVideoToFile(videoId, errorReason);

            // Buscar por videoId en lugar de confiar en el índice
            boolean found = false;
            int actualIndex = -1;
            final PlaylistItem[] itemToRemoveWrapper = new PlaylistItem[1];

            // Primero, encontrar el ítem y su índice actual
            for (int i = 0; i < playlist.size(); i++) {
                PlaylistItem item = playlist.get(i);
                if (item.videoId.equals(videoId)) {
                    itemToRemoveWrapper[0] = item;
                    actualIndex = i;
                    found = true;
                    break;
                }
            }

            if (!found || itemToRemoveWrapper[0] == null) {
                log("⚠️ No se encontró la canción con ID: " + videoId + " en la playlist");
                return;
            }

            // Ahora eliminar usando el índice actual encontrado
            playlist.remove(actualIndex);

            // Ajustar currentIndex si es necesario
            if (actualIndex <= currentIndex && currentIndex > 0) {
                currentIndex--;
            }

            // Limpiar del cache
            trackCache.remove(videoId);

            log("🗑️ ELIMINADA CANCIÓN [" + (actualIndex + 1) + "]: " + itemToRemoveWrapper[0].title
                    + " - Razón: " + errorReason);

            // LIMPIAR ARCHIVOS EN CACHÉ
            cleanupFailedVideoFiles(videoId);

            Platform.runLater(() -> {
                updateTrackList();
                updateUI();
                updateStats();

                // Mostrar mensaje en la interfaz
                nowPlayingLabel.setText("Canción eliminada: " + itemToRemoveWrapper[0].title);
            });

            // Después de eliminar una canción fallida, intentar precargar una de reemplazo
            if (!playlist.isEmpty() && currentIndex >= 0 && currentIndex < playlist.size()) {
                log("🔄 Buscando reemplazo para precarga después de eliminar canción fallida");
                scheduleNextPreloadsWithReplacement(currentIndex, 1);
            }
        }
    }

    /**
     * Elimina archivos asociados a un video fallido
     */
    private void cleanupFailedVideoFiles(String videoId) {
        try {
            // Buscar y eliminar cualquier archivo que empiece con el videoId
            Files.list(cacheDir)
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith(videoId + ".") || filename.contains(videoId);
                    })
                    .forEach(path -> {
                        try {
                            boolean deleted = Files.deleteIfExists(path);
                            if (deleted) {
                                log("🗑️ Eliminado archivo de caché: " + path.getFileName());
                            }
                        } catch (IOException e) {
                            log("⚠️ No se pudo eliminar archivo " + path.getFileName() + ": " + e.getMessage());
                        }
                    });

            // También eliminar archivos temporales de yt-dlp/ffmpeg
            Files.list(cacheDir)
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.contains(videoId)
                                && (filename.endsWith(".part") || filename.endsWith(".ytdl")
                                || filename.endsWith(".temp") || filename.contains("tmp"));
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });

        } catch (IOException e) {
            log("⚠️ Error limpiando archivos de video fallido: " + e.getMessage());
        }
    }

    // Método para asegurar que ffmpeg está disponible
    private synchronized Path ensureFfmpegAvailable() throws IOException {
        // Si ya está inicializado, devolver la carpeta
        if (ffmpegTempDir != null && Files.exists(ffmpegTempDir)) {
            Path ffmpegExe = ffmpegTempDir.resolve("ffmpeg.exe");
            if (Files.exists(ffmpegExe) && Files.size(ffmpegExe) > 1000000) {
                return ffmpegTempDir;
            }
        }

        // Crear carpeta temporal única
        ffmpegTempDir = Files.createTempDirectory("ytplayer_ffmpeg_");
        log("📦 Preparando ffmpeg en: " + ffmpegTempDir);

        // Extraer los binarios necesarios
        extractFfmpegBinary("/lib/ffmpeg.exe", ffmpegTempDir.resolve("ffmpeg.exe"));
        extractFfmpegBinary("/lib/ffprobe.exe", ffmpegTempDir.resolve("ffprobe.exe"));

        // Verificar que funcionan
        if (!testFfmpegBinary(ffmpegTempDir.resolve("ffmpeg.exe"))) {
            throw new IOException("ffmpeg no funciona correctamente");
        }

        // Registrar limpieza al salir
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanupFfmpegTemp();
        }));

        return ffmpegTempDir;
    }

// Método para extraer un binario
    private void extractFfmpegBinary(String resourcePath, Path destination) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Recurso no encontrado: " + resourcePath
                        + "\nAsegúrate de que el archivo está en src/main/resources/lib/");
            }

            try (OutputStream os = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }

                log("   ✅ " + destination.getFileName() + " extraído ("
                        + String.format("%.1f", totalBytes / 1024.0 / 1024.0) + " MB)");
            }
        }
    }

// Método para probar que ffmpeg funciona
    private boolean testFfmpegBinary(Path ffmpegExe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegExe.toString(),
                    "-version"
            );

            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                log("   ✅ ffmpeg funciona correctamente");
                return true;
            }
            return false;
        } catch (Exception e) {
            log("   ❌ Error probando ffmpeg: " + e.getMessage());
            return false;
        }
    }

// Método para limpiar la carpeta temporal
    private void cleanupFfmpegTemp() {
        if (ffmpegTempDir != null && Files.exists(ffmpegTempDir)) {
            try {
                Files.walk(ffmpegTempDir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
                log("🗑️ Carpeta temporal de ffmpeg eliminada");
            } catch (IOException e) {
                // Ignorar errores de limpieza
            }
        }
    }
    
    private boolean isDownloadInProgress() {
        // Verificar en todos los tracks
        for (Track track : trackCache.values()) {
            if (track.status == TrackStatus.DOWNLOADING) {
                log("⏳ Hay descarga en progreso: " + track.title);
                return true;
            }
        }
        return false;
    }
    
    private void scheduleNextPreloads(int currentIdx) {
        scheduleNextPreloadsWithReplacement(currentIdx, 0);
    }

    private void scheduleNextPreloadsWithReplacement(int currentIdx, int extraReplacements) {
        downloadExecutor.submit(() -> {
            try {
                final int idx = currentIdx;

                int preloads = 0;
                List<Integer> indicesToPreload = new ArrayList<>();
                List<Integer> failedDuringPreload = new ArrayList<>();

                if (shuffleMode) {
                    indicesToPreload = getNextShuffleIndices(PRELOAD_AHEAD + extraReplacements);
                } else {
                    // Tomar las siguientes canciones en orden
                    for (int i = 1; i <= PRELOAD_AHEAD + extraReplacements; i++) {
                        int nextIdx = idx + i;
                        if (nextIdx < playlist.size()) {
                            indicesToPreload.add(nextIdx);
                        }
                    }
                }

                for (int nextIdx : indicesToPreload) {
                    if (nextIdx < playlist.size()) {
                        PlaylistItem nextItem = playlist.get(nextIdx);

                        // Verificar que no sea un video fallido
                        if (isVideoFailed(nextItem.videoId)) {
                            log("⚠️ Canción para precarga marcada como fallida, buscando reemplazo...");
                            failedDuringPreload.add(nextIdx);
                            continue;
                        }

                        Track nextTrack = trackCache.get(nextItem.videoId);

                        synchronized (Faklify.this) {
                            if (currentIndex != idx) {
                                log("⚠️ Cancelar precarga: usuario cambió de canción");
                                return;
                            }

                            if (nextIdx == currentIndex) {
                                continue;
                            }
                        }

                        if (nextTrack != null && !nextTrack.isFileValid()
                                && nextTrack.status != TrackStatus.DOWNLOADING
                                && activeDownloads.get() < MAX_DOWNLOADS) {

                            downloadTrack(nextItem, nextIdx, false);
                            preloads++;
                            Thread.sleep(300);
                        }
                    }
                }

                // Si hubo canciones fallidas durante la precarga, buscar reemplazos
                if (!failedDuringPreload.isEmpty()) {
                    log("🔄 Buscando " + failedDuringPreload.size() + " reemplazos para canciones fallidas");
                    List<Integer> replacementIndices = findReplacementIndices(indicesToPreload, failedDuringPreload.size(), shuffleMode);

                    for (int replacementIdx : replacementIndices) {
                        if (replacementIdx < playlist.size()) {
                            PlaylistItem replacementItem = playlist.get(replacementIdx);

                            if (isVideoFailed(replacementItem.videoId)) {
                                continue;
                            }

                            Track replacementTrack = trackCache.get(replacementItem.videoId);

                            synchronized (Faklify.this) {
                                if (currentIndex != idx) {
                                    log("⚠️ Cancelar precarga de reemplazo: usuario cambió de canción");
                                    return;
                                }
                            }

                            if (replacementTrack != null && !replacementTrack.isFileValid()
                                    && replacementTrack.status != TrackStatus.DOWNLOADING
                                    && activeDownloads.get() < MAX_DOWNLOADS) {

                                downloadTrack(replacementItem, replacementIdx, false);
                                preloads++;
                                Thread.sleep(300);
                            }
                        }
                    }
                }

                if (preloads > 0) {
                    String mode = shuffleMode ? "aleatorias" : "siguientes";
                    if (failedDuringPreload.size() > 0) {
                        log("🔄 Precargando " + preloads + " canciones " + mode + " (incluyendo " + failedDuringPreload.size() + " reemplazos)");
                    } else {
                        log("🔄 Precargando " + preloads + " canciones " + mode);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    private void playCurrent() {
        // Antes de guardar la sesión, verificamos si ya hay algo cargado en el reproductor.
        // Si mediaPlayer no es null y tiene un medio cargado (seekable), 
        // significa que estamos cambiando de canción, no abriendo la app.
        boolean esCambioDeCancionInterno = (mediaPlayer != null && mediaPlayer.status().isSeekable());

        saveCurrentSession();

        // Si es un cambio manual dentro de la app, reseteamos el tiempo guardado 
        // para que no "contamine" a la nueva canción.
        if (esCambioDeCancionInterno) {
            savedPlaybackTime = 0;
        }

        if (this.mediaPlayer == null) {
            log("❌ Error: El reproductor VLC no se pudo inicializar. Revisa los logs de inicio.");
            initVLC();
            if (this.mediaPlayer == null) {
                return;
            }
        }

        synchronized (this) {
            if (currentIndex < 0 || currentIndex >= playlist.size()) {
                log("⚠️ Índice inválido: " + currentIndex);
                return;
            }
        }

        PlaylistItem item;
        synchronized (this) {
            item = playlist.get(currentIndex);
        }

        if (isVideoFailed(item.videoId)) {
            log("❌ No se puede reproducir: canción marcada como fallida");
            playNext();
            return;
        }

        Track track = trackCache.get(item.videoId);

        if (track == null || !track.isFileValid()) {
            log("❌ No se puede reproducir: archivo no válido para: " + item.title);
            if (track == null || track.status != TrackStatus.DOWNLOADING) {
                log("⬇️ Iniciando descarga para reproducción: " + item.title);
                downloadTrack(item, currentIndex, true);
            }
            return;
        }

        try {
            String currentMediaPath = null;
            if (mediaPlayer.status().isPlaying() || mediaPlayer.status().isSeekable()) {
                try {
                    currentMediaPath = mediaPlayer.media().info().mrl();
                } catch (Exception e) {
                    // Silencioso
                }
            }

            String newMediaPath = track.filePath.toUri().toString();

            if (currentMediaPath == null || !currentMediaPath.equals(newMediaPath)
                    || !mediaPlayer.status().isPlaying()) {

                log("▶️ Iniciando reproducción: " + track.title);

                // Detenemos cualquier rastro de la canción anterior
                mediaPlayer.controls().stop();

                if (savedPlaybackTime > 0) {
                    // Este bloque SOLO se ejecutará cuando se restaura la sesión al abrir la App
                    mediaPlayer.media().start(newMediaPath);
                    mediaPlayer.controls().setTime(savedPlaybackTime);
                    log("↪️ Restaurando sesión previa en: " + formatTime(savedPlaybackTime));

                    // Limpiamos para que la siguiente canción ya no use este tiempo
                    savedPlaybackTime = 0;
                } else {
                    // Comportamiento estándar: limpieza total y empezar desde el principio
                    mediaPlayer.controls().setTime(0);
                    mediaPlayer.controls().setPosition(0.0f);
                    mediaPlayer.media().play(newMediaPath);
                }

                Platform.runLater(() -> {
                    nowPlayingLabel.setText("▶ " + track.title + (shuffleMode ? " 🔀" : ""));
                    playPauseBtn.setText("⏸");
                    playPauseBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");

                    // Reset visual inmediato de la barra y el tiempo
                    seekSlider.setValue(0);
                    timeLabel.setText("00:00");

                    if (!programmaticSelection && currentIndex >= 0
                            && currentIndex < trackListView.getItems().size()) {
                        trackListView.getSelectionModel().select(currentIndex);
                    }

                    updateUI();
                });

                scheduleNextPreloads(currentIndex);

            } else {
                log("ℹ️ Ya se está reproduciendo: " + track.title);
                Platform.runLater(() -> {
                    nowPlayingLabel.setText("▶ " + track.title + (shuffleMode ? " 🔀" : ""));
                    updateUI();
                });
            }

        } catch (Exception e) {
            log("❌ Error en playCurrent: " + e.getMessage());
            if (track != null) {
                track.status = TrackStatus.NOT_DOWNLOADED;
                log("⚠️ Archivo marcado como inválido, reintentando descarga...");
                downloadTrack(item, currentIndex, true);
            }
        }
    }

    private void playNext() {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        int calculoIndice; // Variable temporal para el cálculo
        if (shuffleMode) {
            calculoIndice = getNextShuffleIndex();

            // Si el modo aleatorio se agota, reiniciamos el orden
            if (calculoIndice == -1) {
                log("🔀 Reiniciando ciclo aleatorio...");
                generateShuffleOrder();
                calculoIndice = getNextShuffleIndex();
            }
        } else {
            // MODO NORMAL:
            if (currentIndex < playlist.size() - 1) {
                calculoIndice = currentIndex + 1;
            } else {
                // Si llegamos al final, volvemos al inicio (Loop de lista)
                log("🔁 Fin de la lista alcanzado. Volviendo al inicio...");
                calculoIndice = 0;
            }
        }

        // Creamos la variable FINAL para que el Lambda pueda usarla
        final int nextIndexFinal = calculoIndice;

        if (nextIndexFinal >= 0 && nextIndexFinal < playlist.size()) {
            PlaylistItem item = playlist.get(nextIndexFinal);

            if (isVideoFailed(item.videoId)) {
                log("⚠️ Siguiente canción está marcada como fallida, saltando...");
                currentIndex = nextIndexFinal;
                playNext();
                return;
            }

            // Actualización de estado y UI
            currentIndex = nextIndexFinal;
            aplicarFocoYScroll(currentIndex);

            Track track = trackCache.get(item.videoId);
            savedPlaybackTime = 0;

            if (track != null && track.isFileValid()) {
                playCurrent();
            } else {
                // Ahora pasamos la variable FINAL al executor
                downloadExecutor.submit(() -> {
                    try {
                        Thread.sleep(100);
                        // Usamos la constante aquí
                        downloadTrack(item, nextIndexFinal, true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } else {
            log("🏁 Fin de la lista (Lista vacía)");
        }
    }

    private void playPrev() {
        if (isDownloadInProgress()) {
            showDownloadInProgressAlert();
            return;
        }

        int calculoIndice;
        if (shuffleMode) {
            calculoIndice = getPrevShuffleIndex();

            // Si el modo aleatorio no tiene una anterior (inicio del shuffle), 
            // podrías dejarlo ahí o saltar al final del orden aleatorio. 
            // Normalmente en shuffle se vuelve al último del historial.
            if (calculoIndice == -1) {
                log("ℹ️ Inicio del historial aleatorio.");
                return;
            }
        } else {
            // MODO NORMAL:
            if (currentIndex > 0) {
                calculoIndice = currentIndex - 1;
            } else {
                // Si estamos en la primera, saltamos a la última canción
                log("🔁 Volviendo al final de la lista...");
                calculoIndice = playlist.size() - 1;
            }
        }

        // Declaramos la variable FINAL para el lambda
        final int prevIndexFinal = calculoIndice;

        if (prevIndexFinal >= 0 && prevIndexFinal < playlist.size()) {
            PlaylistItem item = playlist.get(prevIndexFinal);

            if (isVideoFailed(item.videoId)) {
                log("⚠️ Canción anterior está marcada como fallida, saltando...");
                currentIndex = prevIndexFinal;
                playPrev();
                return;
            }

            // Foco y Scroll al nuevo índice
            currentIndex = prevIndexFinal;
            aplicarFocoYScroll(currentIndex);

            Track track = trackCache.get(item.videoId);
            savedPlaybackTime = 0; // Reset de tiempo para evitar el error anterior

            if (track != null && track.isFileValid()) {
                playCurrent();
            } else {
                downloadExecutor.submit(() -> {
                    try {
                        Thread.sleep(100);
                        // Usamos la variable constante aquí
                        downloadTrack(item, prevIndexFinal, true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }
    
    private void showDownloadInProgressAlert() {
        // Evitar mostrar múltiples alerts
        if (showingDownloadAlert) {
            return;
        }

        showingDownloadAlert = true;

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
            showingDownloadAlert = false;

            log("⚠️ Intento de cambiar canción durante descarga - Descargas activas: " + activeDownloads.get());
        });
    }

    @Override
    public void stop() {
        if (mediaPlayer != null) {
            try {
                // Quitar eventos primero para que no lleguen avisos tarde
                mediaPlayer.events().removeMediaPlayerEventListener(this);

                // Detener la reproducción
                if (mediaPlayer.status().isPlaying()) {
                    mediaPlayer.controls().stop();
                }

                // Soltar la memoria nativa
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Throwable t) {
                
            }
        }

        // Reset de variables
        savedPlaybackTime = 0;
        isPlaying = false;

        // UI limpia
        Platform.runLater(() -> {
            if (playProgress != null) {
                playProgress.setProgress(0);
            }
            if (timeLabel != null) {
                timeLabel.setText("00:00 / 00:00");
            }
        });
        saveCurrentSession();
    }

    private boolean checkYtDlpSilent() {
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void showYtDlpInstallHelp() {
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
    
    private void executePowerShellUpdate(java.util.concurrent.CountDownLatch latch) {
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

    private void showRestartAlert() {
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
    
    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public static void log(String message) {
        if (DEBUG_MODE) {
            System.out.println("[LOG] " + message);
        }
    }
    
    private void updateTrackList() {
        Platform.runLater(() -> {
            programmaticSelection = true;

            trackListView.getItems().clear();
            for (int i = 0; i < playlist.size(); i++) {
                PlaylistItem item = playlist.get(i);
                Track track = trackCache.get(item.videoId);

                String prefix;
                if (track == null) {
                    prefix = "❓ ";
                } else if (track.isFileValid()) {
                    prefix = "✅ ";
                } else if (track.status == TrackStatus.DOWNLOADING) {
                    prefix = "⏳ ";
                } else {
                    prefix = "❌ ";
                }

                String title = (track != null && track.title != null) ? track.title : item.title;

                if (i == currentIndex) {
                    if (shuffleMode) {
                        title = "🎵 " + title + " 🔀";
                    } else {
                        title = "🎵 " + title;
                    }
                }

                trackListView.getItems().add(prefix + title);
            }

            if (currentIndex >= 0 && currentIndex < trackListView.getItems().size()) {
                trackListView.getSelectionModel().select(currentIndex);
            }

            programmaticSelection = false;
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

    private void updateStats() {
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

    private void updateUI() {
        Platform.runLater(() -> {
            boolean hasSelection = currentIndex >= 0 && currentIndex < playlist.size();

            // Habilitar/Deshabilitar Play/Pause según si hay algo seleccionado
            playPauseBtn.setDisable(!hasSelection);
            
            // Los botones Next y Prev solo se deshabilitan si la lista tiene 0 o 1 canción.
            // Si hay 2 o más, siempre puedes ir hacia adelante o hacia atrás (aunque sea la misma).
            boolean canNavigate = playlist.size() > 1;

            nextBtn.setDisable(!canNavigate);
            prevBtn.setDisable(!canNavigate);

            // Actualizar el texto y estilo del botón Play/Pause
            if (isPlaying) {
                playPauseBtn.setText("⏸");
                playPauseBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");
            } else {
                playPauseBtn.setText("▶");
                playPauseBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 50px; -fx-min-height: 40px;");
            }
        });
    }

    private void cleanup() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            if (factory != null) {
                factory.release();
            }
            downloadExecutor.shutdownNow();
            monitorExecutor.shutdownNow();
            cleanupExecutor.shutdownNow();

            // Limpiar archivos temporales de VLC si es necesario
            cleanupVlcTempFiles();

        } catch (Exception e) {
            log("⚠️ Error durante cleanup: " + e.getMessage());
        }
    }

    // Limpiar archivos temporales de VLC
    private void cleanupVlcTempFiles() {
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
    
    private static class PlaylistItem {

        String title;
        String videoId;
        String url;
    }

    private enum TrackStatus {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED
    }

    private static class Track {

        String title;
        Path filePath;
        long fileSize;
        TrackStatus status = TrackStatus.NOT_DOWNLOADED;
        int downloadAttempts = 0;
        long lastDownloadAttempt = 0;

        boolean isFileValid() {
            if (status != TrackStatus.DOWNLOADED || filePath == null) {
                return false;
            }
            try {
                return Files.exists(filePath) && Files.size(filePath) > MIN_FILE_SIZE;
            } catch (IOException e) {
                return false;
            }
        }
    }
    
    public static void main(String[] args) {
        // Configurar propiedades del sistema antes de iniciar
        System.setProperty("vlcj.log", "WARN");  // Reducir logging de vlcj
        System.setProperty("jna.nounpack", "true");  // Evitar unpacking de JNA
        System.setProperty("jna.nosys", "true");  // No cargar desde system path

        launch(args);
    }
    // Interfaz para que JNA encuentre el método de Windows

    private interface DirectKernel32 extends com.sun.jna.Library {
        // Cargamos kernel32 con opciones para que use Unicode por defecto

        DirectKernel32 INSTANCE = com.sun.jna.Native.load("kernel32", DirectKernel32.class,
                com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);

        // Forzamos el nombre de la función a SetDllDirectoryW
        boolean SetDllDirectoryW(String lpPathName);
    }
}
