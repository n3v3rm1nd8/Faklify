package com.faklify.donations;

import static com.faklify.attributesGenerics.Attributes.CACHE_DIR;
import static com.faklify.methodsGenerics.Methods.log;
import java.io.File;
import java.nio.file.Files;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public class Donation {

    public static boolean shouldShowMonthlyDonation() {
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

    public static void showDonationDialog() {
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

    // Método auxiliar para abrir la URL en el navegador predeterminado
    private static void abrirNavegador(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (Exception ex) {
            log("No se pudo abrir el navegador: " + ex.getMessage());
        }
    }
}
