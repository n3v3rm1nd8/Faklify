package com.faklify.UI.createBtn;

import javafx.scene.control.Button;

public class Btn {
    public static Button createControlBtn(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle(String.format(
                "-fx-font-size: 16px; -fx-font-weight: bold; "
                + "-fx-background-color: %s; -fx-text-fill: white; "
                + "-fx-min-width: 60px; -fx-min-height: 40px;",
                color
        ));
        return btn;
    }
}
