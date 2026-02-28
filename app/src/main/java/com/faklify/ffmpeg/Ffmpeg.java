package com.faklify.ffmpeg;

import static com.faklify.attributesGenerics.Attributes.ffmpegTempDir;
import static com.faklify.methodsGenerics.Methods.log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class Ffmpeg {
    // Método para asegurar que ffmpeg está disponible
    public synchronized Path ensureFfmpegAvailable() throws IOException {
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
    private static boolean testFfmpegBinary(Path ffmpegExe) {
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
    private static void cleanupFfmpegTemp() {
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
}
