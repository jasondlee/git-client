package io.github.jasondlee.gitclient.app;

import io.github.jasondlee.gitclient.ui.WorkspaceView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.nio.file.Path;

public final class GitClientApp extends Application {
    private final TabPane repositories = new TabPane();

    @Override public void start(Stage stage) {
        stage.setTitle("Git Client — Repository Explorer");
        Button newTab = new Button("New Repository Tab");
        newTab.setOnAction(event -> addRepositoryTab(null));
        BorderPane layout = new BorderPane();
        layout.setTop(new ToolBar(newTab));
        layout.setCenter(repositories);
        repositories.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        if (getParameters().getRaw().isEmpty()) {
            addRepositoryTab(null);
        } else {
            for (String argument : getParameters().getRaw()) {
                addRepositoryTab(Path.of(argument));
            }
        }
        Scene scene = new Scene(layout, 1100, 700);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN),
                () -> addRepositoryTab(null));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    Tab selected = repositories.getSelectionModel().getSelectedItem();
                    if (selected != null) repositories.getTabs().remove(selected);
                    if (repositories.getTabs().isEmpty()) addRepositoryTab(null);
                });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
                () -> repositories.getSelectionModel().selectNext());
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN,
                        KeyCombination.SHIFT_DOWN),
                () -> repositories.getSelectionModel().selectPrevious());
        stage.setScene(scene);
        stage.show();
    }

    private void addRepositoryTab(Path path) {
        WorkspaceView workspace = new WorkspaceView();
        Tab tab = new Tab(path == null ? "New Repository" : path.getFileName().toString(),
                workspace);
        workspace.repositoryLocationProperty().addListener((observable, oldLocation, newLocation) -> {
            if (newLocation == null || newLocation.equals("No repository open")) {
                tab.setText("New Repository");
            } else {
                Path location = Path.of(newLocation);
                tab.setText(location.getFileName() == null ? newLocation : location.getFileName().toString());
                tab.setTooltip(new javafx.scene.control.Tooltip(newLocation));
            }
        });
        tab.setClosable(true);
        tab.setOnClosed(event -> {
            if (repositories.getTabs().isEmpty()) addRepositoryTab(null);
        });
        repositories.getTabs().add(tab);
        repositories.getSelectionModel().select(tab);
        if (path != null) workspace.openPath(path);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
