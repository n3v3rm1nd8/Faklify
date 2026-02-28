package com.faklify.remoteAccess;

import static com.faklify.attributesGenerics.Attributes.nowPlayingLabel;
import static com.faklify.attributesGenerics.Attributes.remoteServer;
import static com.faklify.attributesGenerics.Attributes.serverExecutor;
import static com.faklify.attributesGenerics.Attributes.serverStarted;
import static com.faklify.attributesGenerics.Attributes.volumeSlider;
import static com.faklify.methodsGenerics.Methods.log;
import static com.faklify.services.PlayCurrent.togglePlayPause;
import static com.faklify.services.PlayNext.playNext;
import static com.faklify.services.PlayPrev.playPrev;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import javafx.application.Platform;

public class RemoteAccess {

    private static void adjustVolume(int delta) {
        Platform.runLater(() -> {
            double current = volumeSlider.getValue();
            double newValue = current + delta;

            // Limitamos entre 0 y 100 para no romper el Slider
            if (newValue > 100) {
                newValue = 100;
            }
            if (newValue < 0) {
                newValue = 0;
            }

            volumeSlider.setValue(newValue);
        });
    }

    private static String getCurrentSongName() {
        // Si el label tiene "▶ Canción...", limpiamos el icono
        String texto = nowPlayingLabel.getText();
        if (texto == null || texto.isEmpty()) {
            return "Sin reproducción";
        }

        return texto.replace("▶ ", "").replace(" 🔀", "").trim();
    }

    public static synchronized void startRemoteControlServer() {
        if (serverStarted) {
            return; // Si ya se ejecutó, salimos inmediatamente
        }
        try {
            // Forzamos que se escuche en todas las IPs de la casa (0.0.0.0)
            remoteServer = HttpServer.create(new InetSocketAddress(8080), 0);

            // Registro SEGURO de rutas
            registerSafeContext("/api/control", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                if (query != null) {
                    if (query.contains("action=playpause")) {
                        togglePlayPause();
                    }
                    if (query.contains("action=next")) {
                        playNext();
                    }
                    if (query.contains("action=prev")) {
                        playPrev();
                    }
                    if (query.contains("action=volup")) {
                        adjustVolume(5);
                    }
                    if (query.contains("action=voldown")) {
                        adjustVolume(-5);
                    }
                }
                sendTextResponse(exchange, "{\"status\":\"ok\"}", "application/json");
            });

            registerSafeContext("/api/status", exchange -> {
                String songTitle = getCurrentSongName().replace("\"", "\\\"");
                String json = "{\"title\":\"" + songTitle + "\", \"artist\":\"Control PC\"}";
                sendTextResponse(exchange, json, "application/json");
            });

            registerSafeContext("/", exchange -> {
                sendTextResponse(exchange, getRemoteHTML(), "text/html");
            });

            serverExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
            remoteServer.setExecutor(serverExecutor);
            remoteServer.start();
            serverStarted = true;
            log("🚀 Servidor Mando a Distancia INICIADO en puerto 8080");

        } catch (Exception e) {
            log("❌ Error crítico en el servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

// Método auxiliar para evitar el error de "cannot add context"
    private static void registerSafeContext(String path, HttpHandler handler) {
        try {
            remoteServer.createContext(path, handler);
        } catch (IllegalArgumentException e) {
            log("⚠️ La ruta " + path + " ya estaba registrada, saltando...");
        }
    }

// Método auxiliar para enviar respuestas sin repetir código
    private static void sendTextResponse(HttpExchange exchange, String text, String contentType) throws IOException {
        byte[] response = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static String getRemoteHTML() {
        return "<!DOCTYPE html><html><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "  body { background: #0a192f; color: #e6f1ff; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; text-align: center; padding-top: 50px; }"
                + "  button { background: #3498db; border: none; color: white; padding: 20px; margin: 10px; border-radius: 12px; width: 85%; font-size: 18px; font-weight: bold; transition: background 0.3s; }"
                + "  button:active { background: #2980b9; transform: scale(0.98); }"
                + "  p { color: #8892b0; font-size: 14px; }"
                + "  h2 { margin-bottom: 5px; color: #64ffda; }"
                + "</style></head><body>"
                + "  <h2>Faklify Remote</h2>"
                + "  <p>Control remoto desde tu dispositivo</p>"
                + "  <button onclick=\"fetch('/api/control?action=playpause')\">⏯ PLAY / PAUSE</button>"
                + "  <button onclick=\"fetch('/api/control?action=next')\">⏭ SIGUIENTE</button>"
                + "  <button onclick=\"fetch('/api/control?action=prev')\">⏮ ANTERIOR</button>"
                + "  <button onclick=\"fetch('/api/control?action=volup')\">🔊 SUBIR VOLUMEN</button>"
                + "  <button onclick=\"fetch('/api/control?action=voldown')\">🔉 BAJAR VOLUMEN</button>"
                + "</body></html>";
    }
}
