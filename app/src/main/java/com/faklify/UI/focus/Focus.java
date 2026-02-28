package com.faklify.UI.focus;

import com.faklify.attributesGenerics.Attributes;
import javafx.application.Platform;

public class Focus {
    /**
     * Método auxiliar para asegurar que el foco y el scroll funcionen
     * correctamente tras la carga de datos.
     */
    public static void aplicarFocoYScroll(int index) {
        if (index < 0 || index >= Attributes.playlist.size()) {
            return;
        }

        Platform.runLater(() -> {
            // Verificación de seguridad: si la lista se vació justo antes de ejecutar este hilo
            if (Attributes.trackListView == null || Attributes.playlist.isEmpty()
                    || index >= Attributes.playlist.size()) {
                return;
            }

            Attributes.programmaticSelection = true;

            Attributes.trackListView.requestFocus();
            Attributes.trackListView.getSelectionModel().select(index);
            Attributes.trackListView.getFocusModel().focus(index);

            // Logica de Scroll Mínimo
            javafx.scene.control.skin.ListViewSkin<?> skin
                    = (javafx.scene.control.skin.ListViewSkin<?>) Attributes.trackListView.getSkin();
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
                        Attributes.trackListView.scrollTo(index);
                    }
                } else {
                    // Si no hay celdas visibles aún, scroll estándar por seguridad
                    Attributes.trackListView.scrollTo(index);
                }
            } else {
                Attributes.trackListView.scrollTo(index);
            }

            Attributes.programmaticSelection = false;
        });
    }
}
