package io.github.jasondlee.gitclient.app;

import io.github.jasondlee.gitclient.ui.WorkspaceView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.prefs.Preferences;

public final class GitClientApp extends Application {
    private final TabPane repositories = new TabPane();
    private final MenuButton recentlyClosed = new MenuButton("Reopen Closed");
    private final java.util.Deque<Path> closedPaths = new java.util.ArrayDeque<>();
    private final Preferences preferences = Preferences.userNodeForPackage(GitClientApp.class);
    private static final String SESSION_KEY = "openRepositoryTabs";

    @Override public void start(Stage stage) {
        stage.setTitle("Git Client — Repository Explorer");
        Button newTab = new Button("New Repository Tab");
        newTab.setOnAction(event -> addRepositoryTab(null));
        BorderPane layout = new BorderPane();
        recentlyClosed.setDisable(true);
        layout.setTop(new ToolBar(newTab, recentlyClosed));
        layout.setCenter(repositories);
        repositories.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        if (!getParameters().getRaw().isEmpty()) {
            for (String argument : getParameters().getRaw()) {
                addRepositoryTab(Path.of(argument));
            }
        } else {
            restoreSession();
        }
        if (repositories.getTabs().isEmpty()) addRepositoryTab(null);
        Scene scene = new Scene(layout, 1100, 700);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN),
                () -> addRepositoryTab(null));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    Tab selected = repositories.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        rememberClosed((WorkspaceView) selected.getContent());
                        repositories.getTabs().remove(selected);
                    }
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

    private void rememberClosed(WorkspaceView workspace) {
        String location = workspace.repositoryLocationProperty().get();
        if (location == null || location.equals("No repository open")) return;
        try {
            Path path = Path.of(location);
            if (!Files.isDirectory(path)) return;
            closedPaths.remove(path);
            closedPaths.addFirst(path);
            while (closedPaths.size() > 10) closedPaths.removeLast();
            updateClosedMenu();
        } catch (IllegalArgumentException ignored) {
            // An invalid repository location cannot be reopened.
        }
    }

    private void updateClosedMenu() {
        recentlyClosed.getItems().clear();
        for (Path path : closedPaths) {
            MenuItem item = new MenuItem(path.toString());
            item.setOnAction(event -> {
                closedPaths.remove(path);
                updateClosedMenu();
                if (Files.isDirectory(path)) addRepositoryTab(path);
            });
            recentlyClosed.getItems().add(item);
        }
        recentlyClosed.setDisable(closedPaths.isEmpty());
    }

    private void restoreSession() {
        String saved = preferences.get(SESSION_KEY, "");
        for (String encoded : saved.split("\\n")) {
            if (encoded.isBlank()) continue;
            try {
                Path path = Path.of(new String(java.util.Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8));
                if (Files.isDirectory(path)) addRepositoryTab(path);
            } catch (IllegalArgumentException ignored) {
                // Skip a corrupt or obsolete saved path.
            }
        }
    }

    @Override public void stop() {
        StringBuilder saved = new StringBuilder();
        for (Tab tab : repositories.getTabs()) {
            WorkspaceView workspace = (WorkspaceView) tab.getContent();
            String location = workspace.repositoryLocationProperty().get();
            if (location == null || location.equals("No repository open")) continue;
            try {
                Path path = Path.of(location);
                if (!Files.isDirectory(path)) continue;
                if (!saved.isEmpty()) saved.append('\n');
                saved.append(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        location.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid paths rather than preventing application shutdown.
            }
        }
        preferences.put(SESSION_KEY, saved.toString());
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
            rememberClosed(workspace);
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
