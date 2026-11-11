package com.steeplesoft.kiboko.app;

import com.steeplesoft.kiboko.service.RepositoryService;
import com.steeplesoft.kiboko.ui.WorkspaceView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

public final class GitClientApp extends Application {
    private final TabPane repositories = new TabPane();
    private final MenuButton recentlyClosed = new MenuButton("Reopen Closed");
    private final java.util.Deque<Path> closedPaths = new java.util.ArrayDeque<>();
    private final Preferences preferences = Preferences.userNodeForPackage(GitClientApp.class);
    private final RepositoryService repositoryService =
            new RepositoryService(this::promptForOpenPgpPassphrase);
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
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN,
                        KeyCombination.SHIFT_DOWN),
                () -> {
                    if (!closedPaths.isEmpty()) {
                        Path path = closedPaths.removeFirst();
                        updateClosedMenu();
                        if (Files.isDirectory(path)) addRepositoryTab(path);
                    }
                });
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
        try {
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
        } finally {
            repositoryService.close();
        }
    }

    /** Shows the one-time OpenPGP passphrase prompt on JavaFX's application thread. */
    private char[] promptForOpenPgpPassphrase() {
        if (Platform.isFxApplicationThread()) return showOpenPgpPassphraseDialog();
        CompletableFuture<char[]> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (result.isCancelled()) return;
            try {
                result.complete(showOpenPgpPassphraseDialog());
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        try {
            return result.get();
        } catch (InterruptedException ex) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unable to show the OpenPGP passphrase prompt", ex.getCause());
        }
    }

    /** Collects a passphrase without placing it in preferences or process arguments. */
    private char[] showOpenPgpPassphraseDialog() {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle("OpenPGP Signing");
        dialog.setHeaderText("Enter your OpenPGP passphrase");
        ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);
        PasswordField field = new PasswordField();
        field.setPromptText("Passphrase");
        dialog.getDialogPane().setContent(new VBox(8, field));
        dialog.setResultConverter(button -> {
            if (button != continueButton) {
                field.clear();
                return null;
            }
            char[] entered = field.getText().toCharArray();
            field.clear();
            return entered;
        });
        dialog.setOnShown(event -> field.requestFocus());
        return dialog.showAndWait().orElse(null);
    }

    private void addRepositoryTab(Path path) {
        WorkspaceView workspace = new WorkspaceView(repositoryService);
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
