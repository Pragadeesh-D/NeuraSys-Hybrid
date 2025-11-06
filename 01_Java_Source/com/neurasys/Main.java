package com.neurasys;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

public class Main extends Application {

    private static final String WINDOW_TITLE = "NeuraSys - Intelligent Backup System";
    private static final String FXML_RESOURCE = "/fxml/main.fxml";
    private static final String CSS_RESOURCE = "/css/application.css"; // ✅ Matches your folder structure

    private static final double DEFAULT_WIDTH = 1400;
    private static final double DEFAULT_HEIGHT = 900;
    private static final double MIN_WIDTH = 800;
    private static final double MIN_HEIGHT = 600;

    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = loadFXML();

        Scene scene = new Scene(root);

        loadStylesheet(scene); // ✅ Loads application.css from /css/

        configurePrimaryStage(primaryStage, scene);

        primaryStage.show();
    }

    private Parent loadFXML() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_RESOURCE));
        return loader.load();
    }

    private void loadStylesheet(Scene scene) {
        try {
            String css = getClass().getResource(CSS_RESOURCE).toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            System.err.println("Warning: could not load stylesheet " + CSS_RESOURCE);
        }
    }

    private void configurePrimaryStage(Stage primaryStage, Scene scene) {
        primaryStage.initStyle(StageStyle.DECORATED);
        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(DEFAULT_WIDTH, bounds.getWidth() * 0.95);
        double height = Math.min(DEFAULT_HEIGHT, bounds.getHeight() * 0.95);
        primaryStage.setWidth(width);
        primaryStage.setHeight(height);
        primaryStage.setX((bounds.getWidth() - width) / 2);
        primaryStage.setY((bounds.getHeight() - height) / 2);

        primaryStage.setOnCloseRequest(event -> {
            System.out.println("Application closing...");
            System.exit(0);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
