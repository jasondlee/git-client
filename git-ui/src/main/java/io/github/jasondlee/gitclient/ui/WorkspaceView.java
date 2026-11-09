package io.github.jasondlee.gitclient.ui;
import io.github.jasondlee.gitclient.model.*;
import io.github.jasondlee.gitclient.service.RepositoryService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;

public final class WorkspaceView extends BorderPane {
    private final RepositoryService repositories = new RepositoryService();
    private final Label location = new Label("No repository open");
    private final Label branch = new Label("");
    private final TableView<ChangedFile> status = new TableView<>();
    private final ListView<CommitInfo> history = new ListView<>();
    private final Label message = new Label("Choose Open Repository to begin.");
    private final TextArea commitMessage = new TextArea();
    private final Button commitButton = new Button("Commit Staged");
    private boolean operationRunning;
    private Path repository;
    private long refreshGeneration;

    public WorkspaceView() {
        Button open = new Button("Open Repository");
        Button refresh = new Button("Refresh");
        open.setOnAction(e -> openRepository());
        refresh.setOnAction(e -> refresh());
        Button stage = new Button("Stage Selected");
        Button unstage = new Button("Unstage Selected");
        stage.setOnAction(e -> changeStaging(false));
        unstage.setOnAction(e -> changeStaging(true));
        ToolBar toolbar = new ToolBar(open, refresh, stage, unstage, new Separator(), location, new Separator(), branch);
        setTop(toolbar);
        TableColumn<ChangedFile,String> path = new TableColumn<>("File");
        path.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().path()));
        TableColumn<ChangedFile,String> index = new TableColumn<>("Index");
        index.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().indexStatus()));
        TableColumn<ChangedFile,String> working = new TableColumn<>("Worktree");
        working.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().worktreeStatus()));
        status.getColumns().addAll(path, index, working);
        status.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        status.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(CommitInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.id().substring(0, 8) + "  " + item.subject() + "  — " + item.author());
            }
        });
        SplitPane panes = new SplitPane(new VBox(new Label("Working tree"), status), new VBox(new Label("Recent commits"), history));
        panes.setDividerPositions(0.5);
        VBox.setVgrow(status, Priority.ALWAYS);
        VBox.setVgrow(history, Priority.ALWAYS);
        setCenter(panes);
        BorderPane.setMargin(panes, new Insets(8));
        commitMessage.setPromptText("Commit message");
        commitMessage.setPrefRowCount(3);
        commitButton.setOnAction(e -> commit());
        commitButton.disableProperty().bind(commitMessage.textProperty().isEmpty());
        VBox commitPane = new VBox(6, new Label("Commit staged changes"), commitMessage,
                commitButton, message);
        commitPane.setPadding(new Insets(8));
        setBottom(commitPane);
    }
    private void changeStaging(boolean unstage) {
        Path current = repository;
        if (current == null) return;
        var paths = status.getSelectionModel().getSelectedItems().stream()
                .map(ChangedFile::path).toList();
        if (paths.isEmpty()) {
            message.setText("Select one or more files first.");
            return;
        }
        message.setText(unstage ? "Unstaging..." : "Staging...");
        Thread.startVirtualThread(() -> {
            try {
                if (unstage) repositories.unstage(current, paths);
                else repositories.stage(current, paths);
                Platform.runLater(this::refresh);
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Error: " + ex.getMessage()));
            }
        });
    }
    private void commit() {
        Path current = repository;
        if (current == null || operationRunning) return;
        String text = commitMessage.getText();
        if (text.isBlank()) {
            message.setText("Enter a commit message.");
            return;
        }
        operationRunning = true;
        commitButton.setDisable(true);
        message.setText("Committing...");
        Thread.startVirtualThread(() -> {
            try {
                String id = repositories.commit(current, text);
                Platform.runLater(() -> {
                    operationRunning = false;
                    commitMessage.clear();
                    message.setText("Committed " + id.substring(0, 8));
                    refresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    operationRunning = false;
                    commitButton.setDisable(false);
                    message.setText("Commit failed: " + ex.getMessage());
                });
            }
        });
    }
    private void openRepository() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Git repository");
        java.io.File chosen = chooser.showDialog(getScene().getWindow());
        if (chosen == null) return;
        repository = chosen.toPath();
        location.setText(repository.toString());
        refresh();
    }
    private void refresh() {
        Path current = repository;
        if (current == null) return;
        long generation = ++refreshGeneration;
        message.setText("Loading...");
        Thread.startVirtualThread(() -> {
            try {
                Path root = repositories.findRepository(current);
                RepositoryStatus snapshot = repositories.status(root);
                var commits = repositories.history(root, 100);
                Platform.runLater(() -> {
                    if (generation != refreshGeneration) return;
                    repository = root;
                    location.setText(root.toString());
                    branch.setText(snapshot.branch());
                    status.getItems().setAll(snapshot.files());
                    history.getItems().setAll(commits);
                    message.setText(snapshot.files().size() + " changed files · " + commits.size() + " commits");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> { if (generation == refreshGeneration) message.setText("Error: " + ex.getMessage()); });
            }
        });
    }
}
