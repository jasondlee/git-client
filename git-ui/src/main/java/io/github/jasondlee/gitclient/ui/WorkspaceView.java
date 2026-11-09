package io.github.jasondlee.gitclient.ui;
import io.github.jasondlee.gitclient.model.*;
import io.github.jasondlee.gitclient.service.RepositoryService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;

public final class WorkspaceView extends BorderPane {
    private final RepositoryService repositories = new RepositoryService();
    private final Label location = new Label("No repository open");
    private final Label branch = new Label("");
    private final Label operationState = new Label("");
    private final ComboBox<String> branchSelector = new ComboBox<>();
    private final ComboBox<String> tagSelector = new ComboBox<>();
    private final ComboBox<String> remoteSelector = new ComboBox<>();
    private final ComboBox<String> stashSelector = new ComboBox<>();
    private final TableView<ChangedFile> status = new TableView<>();
    private final ListView<CommitInfo> history = new ListView<>();
    private final TextArea diffPreview = new TextArea();
    private final TabPane diffTabs = new TabPane();
    private long diffGeneration;
    private final Label message = new Label("Choose Open Repository to begin.");
    private final TextArea commitMessage = new TextArea();
    private final Button commitButton = new Button("Commit Staged");
    private final SimpleBooleanProperty operationRunning = new SimpleBooleanProperty(false);
    private Path repository;
    private long refreshGeneration;

    public WorkspaceView() {
        Button open = new Button("Open Repository");
        Button initialize = new Button("Initialize...");
        Button clone = new Button("Clone...");
        initialize.setOnAction(e -> initializeRepository());
        clone.setOnAction(e -> cloneRepository());
        Button refresh = new Button("Refresh");
        open.setOnAction(e -> openRepository());
        refresh.setOnAction(e -> refresh());
        Button stage = new Button("Stage Selected");
        Button unstage = new Button("Unstage Selected");
        stage.setOnAction(e -> changeStaging(false));
        unstage.setOnAction(e -> changeStaging(true));
        Button createBranch = new Button("New Branch");
        Button mergeBranch = new Button("Merge");
        Button abortMerge = new Button("Abort Merge");
        Button deleteBranch = new Button("Delete Branch");
        Button switchBranch = new Button("Switch Branch");
        createBranch.setOnAction(e -> createBranch());
        mergeBranch.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            Path current = repository;
            String selected = branchSelector.getValue();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Merge '" + selected + "' into the current branch?", ButtonType.OK, ButtonType.CANCEL);
            confirm.showAndWait().filter(button -> button == ButtonType.OK)
                    .ifPresent(button -> runBranchAction(() -> repositories.merge(current, selected)));
        });
        abortMerge.setOnAction(e -> {
            if (repository != null) {
                Path current = repository;
                runBranchAction(() -> repositories.abortMerge(current));
            }
        });
        deleteBranch.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            Path current = repository;
            String selected = branchSelector.getValue();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete merged local branch '" + selected + "'?", ButtonType.OK, ButtonType.CANCEL);
            confirm.showAndWait().filter(button -> button == ButtonType.OK)
                    .ifPresent(button -> runBranchAction(() -> repositories.deleteBranch(current, selected)));
        });
        switchBranch.setOnAction(e -> switchBranch());
        branchSelector.setPromptText("Local branches");
        Button rebase = new Button("Rebase Onto");
        Button cherryPick = new Button("Cherry-pick");
        Button revert = new Button("Revert");
        rebase.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            Path current = repository;
            String target = branchSelector.getValue();
            confirmOperation("Rebase current branch onto '" + target + "'?",
                    () -> repositories.rebase(current, target));
        });
        cherryPick.setOnAction(e -> {
            CommitInfo selected = history.getSelectionModel().getSelectedItem();
            if (repository == null || selected == null) return;
            Path current = repository;
            confirmOperation("Cherry-pick " + selected.id().substring(0, 8) + "?",
                    () -> repositories.cherryPick(current, selected.id()));
        });
        revert.setOnAction(e -> {
            CommitInfo selected = history.getSelectionModel().getSelectedItem();
            if (repository == null || selected == null) return;
            Path current = repository;
            confirmOperation("Create a revert commit for " + selected.id().substring(0, 8) + "?",
                    () -> repositories.revert(current, selected.id()));
        });
        Button reflog = new Button("Reflog");
        Button blame = new Button("Blame File");
        Button worktrees = new Button("Worktrees");
        reflog.setOnAction(e -> showTextReport("Reflog",
                () -> String.join("\n", repositories.reflog(repository, 100))));
        blame.setOnAction(e -> {
            ChangedFile selected = status.getSelectionModel().getSelectedItem();
            if (selected != null) showTextReport("Blame: " + selected.path(),
                    () -> repositories.blame(repository, selected.path()));
        });
        worktrees.setOnAction(e -> showTextReport("Worktrees",
                () -> String.join("\n", repositories.worktrees(repository))));
        Button reset = new Button("Reset...");
        Button submodules = new Button("Submodules");
        Button updateSubmodules = new Button("Update Submodules");
        reset.setOnAction(e -> {
            CommitInfo selected = history.getSelectionModel().getSelectedItem();
            if (repository == null || selected == null) return;
            ChoiceDialog<String> mode = new ChoiceDialog<>("mixed", "soft", "mixed", "hard");
            mode.setTitle("Reset Branch");
            mode.setHeaderText("Reset to " + selected.id().substring(0, 8));
            mode.setContentText("Reset mode:");
            mode.showAndWait().ifPresent(choice -> {
                Path current = repository;
                confirmOperation("Reset current branch to " + selected.id().substring(0, 8)
                                + " (" + choice + ")? Hard reset discards working-tree changes.",
                        () -> repositories.reset(current, selected.id(), choice));
            });
        });
        submodules.setOnAction(e -> showTextReport("Submodules",
                () -> String.join("\n", repositories.submodules(repository))));
        updateSubmodules.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            confirmOperation("Initialize and update submodules?",
                    () -> repositories.updateSubmodules(current));
        });
        Button continueOperation = new Button("Continue Operation");
        Button abortOperation = new Button("Abort Operation");
        Button skipRebase = new Button("Skip Rebase Commit");
        Button conflicts = new Button("Conflicts");
        continueOperation.setOnAction(e -> operateOnSequencer(false));
        abortOperation.setOnAction(e -> operateOnSequencer(true));
        skipRebase.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            confirmOperation("Skip the current rebase commit?",
                    () -> repositories.skipRebase(current));
        });
        conflicts.setOnAction(e -> showTextReport("Unresolved conflicts",
                () -> String.join("\n", repositories.conflictedFiles(repository))));
        Button createTag = new Button("New Tag");
        Button deleteTag = new Button("Delete Tag");
        createTag.setOnAction(e -> createTag());
        deleteTag.setOnAction(e -> deleteTag());
        tagSelector.setPromptText("Local tags");
        Button fetch = new Button("Fetch");
        fetch.setOnAction(e -> {
            if (repository == null || remoteSelector.getValue() == null) return;
            Path current = repository;
            String remote = remoteSelector.getValue();
            runBranchAction(() -> repositories.fetch(current, remote));
        });
        remoteSelector.setPromptText("Remote");
        Button pull = new Button("Pull (FF)");
        Button push = new Button("Push");
        pull.setOnAction(e -> {
            if (repository != null) {
                Path current = repository;
                runBranchAction(() -> repositories.pull(current));
            }
        });
        push.setOnAction(e -> {
            if (repository != null) {
                Path current = repository;
                runBranchAction(() -> repositories.push(current));
            }
        });
        Button stash = new Button("Stash");
        Button applyStash = new Button("Apply Stash");
        Button dropStash = new Button("Drop Stash");
        stash.setOnAction(e -> {
            if (repository != null) {
                Path current = repository;
                runBranchAction(() -> repositories.stash(current, "Saved from Git Client"));
            }
        });
        applyStash.setOnAction(e -> {
            String ref = selectedStashReference();
            if (repository != null && ref != null) {
                Path current = repository;
                runBranchAction(() -> repositories.applyStash(current, ref));
            }
        });
        dropStash.setOnAction(e -> {
            String ref = selectedStashReference();
            if (repository == null || ref == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Permanently drop " + ref + "?", ButtonType.OK, ButtonType.CANCEL);
            confirm.showAndWait().filter(button -> button == ButtonType.OK)
                    .ifPresent(button -> {
                        Path current = repository;
                        runBranchAction(() -> repositories.dropStash(current, ref));
                    });
        });
        stashSelector.setPromptText("Stashes");
        MenuButton branchesMenu = actionsMenu("Branches", createBranch, switchBranch,
                mergeBranch, abortMerge, deleteBranch, rebase);
        MenuButton historyMenu = actionsMenu("History", cherryPick, revert, reflog,
                blame, reset);
        MenuButton operationsMenu = actionsMenu("Operations", continueOperation,
                abortOperation, skipRebase, conflicts);
        MenuButton advancedMenu = actionsMenu("Advanced", worktrees, submodules,
                updateSubmodules, createTag, deleteTag, stash, applyStash, dropStash);
        ToolBar toolbar = new ToolBar(open, initialize, clone, refresh, stage, unstage,
                new Separator(), branchSelector, branchesMenu,
                new Separator(), historyMenu, operationsMenu, advancedMenu,
                new Separator(), tagSelector, stashSelector,
                new Separator(), remoteSelector, fetch, pull, push,
                new Separator(), branch, location);
        setTop(toolbar);
        TableColumn<ChangedFile,String> path = new TableColumn<>("File");
        path.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().path()));
        TableColumn<ChangedFile,String> index = new TableColumn<>("Index");
        index.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().indexStatus()));
        TableColumn<ChangedFile,String> working = new TableColumn<>("Worktree");
        working.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().worktreeStatus()));
        status.getColumns().addAll(path, index, working);
        status.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        status.getSelectionModel().selectedItemProperty().addListener((observable, oldFile, newFile) -> loadDiff());
        status.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(CommitInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.id().substring(0, 8) + "  " + item.subject() + "  — " + item.author());
            }
        });
        SplitPane panes = new SplitPane(new VBox(new Label("Working tree"), status), new VBox(new Label("Recent commits"), history));
        panes.setDividerPositions(0.5);
        diffPreview.setEditable(false);
        diffPreview.setWrapText(false);
        diffPreview.setStyle("-fx-font-family: monospace;");
        Tab unstagedTab = new Tab("Unstaged");
        Tab stagedTab = new Tab("Staged");
        unstagedTab.setClosable(false);
        stagedTab.setClosable(false);
        Tab commitTab = new Tab("Commit Details");
        commitTab.setClosable(false);
        diffTabs.getTabs().addAll(unstagedTab, stagedTab, commitTab);
        diffTabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> loadDiff());
        history.getSelectionModel().selectedItemProperty().addListener((observable, oldCommit, newCommit) -> {
            if (diffTabs.getSelectionModel().getSelectedIndex() == 2) loadDiff();
        });
        VBox diffPanel = new VBox(6, diffTabs, diffPreview);
        diffPanel.setPadding(new Insets(8));
        VBox.setVgrow(diffPreview, Priority.ALWAYS);
        SplitPane workspace = new SplitPane(panes, diffPanel);
        workspace.setOrientation(javafx.geometry.Orientation.VERTICAL);
        workspace.setDividerPositions(0.6);
        VBox.setVgrow(status, Priority.ALWAYS);
        VBox.setVgrow(history, Priority.ALWAYS);
        setCenter(workspace);
        BorderPane.setMargin(workspace, new Insets(8));
        commitMessage.setPromptText("Commit message");
        commitMessage.setPrefRowCount(3);
        commitButton.setOnAction(e -> commit());
        commitButton.disableProperty().bind(commitMessage.textProperty().isEmpty().or(operationRunning));
        VBox commitPane = new VBox(6, new Label("Commit staged changes"), commitMessage,
                commitButton, message);
        commitPane.setPadding(new Insets(8));
        setBottom(commitPane);
    }
    private MenuButton actionsMenu(String title, Button... actions) {
        MenuButton menu = new MenuButton(title);
        for (Button action : actions) {
            MenuItem item = new MenuItem(action.getText());
            item.setOnAction(event -> action.fire());
            menu.getItems().add(item);
        }
        return menu;
    }
    private void operateOnSequencer(boolean abort) {
        if (repository == null) return;
        Path current = repository;
        String state = operationState.getText();
        Runnable action = switch (state) {
            case "MERGE" -> abort ? () -> repositories.abortMerge(current)
                    : () -> repositories.continueMerge(current);
            case "REBASE" -> abort ? () -> repositories.abortRebase(current)
                    : () -> repositories.continueRebase(current);
            case "CHERRY_PICK" -> abort ? () -> repositories.abortCherryPick(current)
                    : () -> repositories.continueCherryPick(current);
            case "REVERT" -> abort ? () -> repositories.abortRevert(current)
                    : () -> repositories.continueRevert(current);
            default -> null;
        };
        if (action != null) confirmOperation((abort ? "Abort " : "Continue ") + state + "?", action);
    }
    private void showTextReport(String title, java.util.function.Supplier<String> loader) {
        if (repository == null) return;
        message.setText("Loading " + title + "...");
        Thread.startVirtualThread(() -> {
            try {
                String content = loader.get();
                Platform.runLater(() -> {
                    TextArea area = new TextArea(content);
                    area.setEditable(false);
                    area.setWrapText(false);
                    area.setPrefSize(850, 550);
                    Dialog<Void> dialog = new Dialog<>();
                    dialog.setTitle(title);
                    dialog.getDialogPane().setContent(area);
                    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    dialog.showAndWait();
                    message.setText(title + " loaded");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText(title + ": " + ex.getMessage()));
            }
        });
    }
    private void confirmOperation(String question, Runnable action) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, question,
                ButtonType.OK, ButtonType.CANCEL);
        confirm.showAndWait().filter(button -> button == ButtonType.OK)
                .ifPresent(button -> runBranchAction(action));
    }
    private String selectedStashReference() {
        String selected = stashSelector.getValue();
        if (selected == null) return null;
        int tab = selected.indexOf('\t');
        return tab < 0 ? selected : selected.substring(0, tab);
    }
    private void createTag() {
        if (repository == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create Tag");
        dialog.setHeaderText("Create lightweight tag at HEAD");
        dialog.setContentText("Tag name:");
        dialog.showAndWait().ifPresent(name -> runBranchAction(() -> repositories.createTag(repository, name)));
    }
    private void deleteTag() {
        if (repository == null || tagSelector.getValue() == null) return;
        String name = tagSelector.getValue();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete local tag '" + name + "'?", ButtonType.OK, ButtonType.CANCEL);
        confirm.showAndWait().filter(button -> button == ButtonType.OK)
                .ifPresent(button -> runBranchAction(() -> repositories.deleteTag(repository, name)));
    }
    private void createBranch() {
        if (repository == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create Branch");
        dialog.setHeaderText("New local branch");
        dialog.setContentText("Branch name:");
        dialog.showAndWait().ifPresent(name -> runBranchAction(() -> repositories.createBranch(repository, name)));
    }
    private void switchBranch() {
        if (repository == null || branchSelector.getValue() == null) return;
        String name = branchSelector.getValue();
        runBranchAction(() -> repositories.switchBranch(repository, name));
    }
    private void runBranchAction(Runnable action) {
        message.setText("Updating branch...");
        Thread.startVirtualThread(() -> {
            try {
                action.run();
                Platform.runLater(this::refresh);
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Branch error: " + ex.getMessage()));
            }
        });
    }
    private void loadDiff() {
        if (diffTabs.getSelectionModel().getSelectedIndex() == 2) {
            loadCommitDetails();
            return;
        }
        ChangedFile selected = status.getSelectionModel().getSelectedItem();
        Path current = repository;
        long generation = ++diffGeneration;
        if (current == null || selected == null) {
            diffPreview.clear();
            return;
        }
        boolean staged = diffTabs.getSelectionModel().getSelectedIndex() == 1;
        diffPreview.setText("Loading diff...");
        Thread.startVirtualThread(() -> {
            try {
                String diff = repositories.diff(current, selected.path(), staged);
                Platform.runLater(() -> {
                    if (generation == diffGeneration) {
                        diffPreview.setText(diff.isEmpty() ? "No changes in this view." : diff);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (generation == diffGeneration) diffPreview.setText("Diff error: " + ex.getMessage());
                });
            }
        });
    }
    private void loadCommitDetails() {
        CommitInfo selected = history.getSelectionModel().getSelectedItem();
        Path current = repository;
        long generation = ++diffGeneration;
        if (selected == null || current == null) {
            diffPreview.setText("Select a commit to view its details.");
            return;
        }
        diffPreview.setText("Loading commit...");
        Thread.startVirtualThread(() -> {
            try {
                String details = repositories.showCommit(current, selected.id());
                Platform.runLater(() -> {
                    if (generation == diffGeneration) diffPreview.setText(details);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (generation == diffGeneration) diffPreview.setText("Commit error: " + ex.getMessage());
                });
            }
        });
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
        if (current == null || operationRunning.get()) return;
        String text = commitMessage.getText();
        if (text.isBlank()) {
            message.setText("Enter a commit message.");
            return;
        }
        operationRunning.set(true);
        message.setText("Committing...");
        Thread.startVirtualThread(() -> {
            try {
                String id = repositories.commit(current, text);
                Platform.runLater(() -> {
                    operationRunning.set(false);
                    commitMessage.clear();
                    message.setText("Committed " + id.substring(0, 8));
                    refresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    operationRunning.set(false);
                    message.setText("Commit failed: " + ex.getMessage());
                });
            }
        });
    }
    private void initializeRepository() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Initialize Git repository in directory");
        java.io.File selected = chooser.showDialog(getScene().getWindow());
        if (selected == null) return;
        Thread.startVirtualThread(() -> {
            try {
                Path root = repositories.initialize(selected.toPath());
                Platform.runLater(() -> {
                    repository = root;
                    refresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Initialize failed: " + ex.getMessage()));
            }
        });
    }
    private void cloneRepository() {
        TextInputDialog urlDialog = new TextInputDialog();
        urlDialog.setTitle("Clone Repository");
        urlDialog.setHeaderText("Clone a Git repository");
        urlDialog.setContentText("Repository URL or local path:");
        var url = urlDialog.showAndWait();
        if (url.isEmpty() || url.get().isBlank()) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose parent directory for clone");
        java.io.File parent = chooser.showDialog(getScene().getWindow());
        if (parent == null) return;
        TextInputDialog folderDialog = new TextInputDialog("repository");
        folderDialog.setTitle("Clone Repository");
        folderDialog.setHeaderText("New folder name");
        folderDialog.setContentText("Folder:");
        folderDialog.showAndWait().ifPresent(folder -> {
            Thread.startVirtualThread(() -> {
                try {
                    Path root = repositories.cloneRepository(parent.toPath(), url.get(), folder);
                    Platform.runLater(() -> {
                        repository = root;
                        refresh();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> message.setText("Clone failed: " + ex.getMessage()));
                }
            });
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
                var branches = repositories.branches(root);
                var tags = repositories.tags(root);
                var remotes = repositories.remotes(root);
                var stashes = repositories.stashes(root);
                var operation = repositories.operationState(root);
                Platform.runLater(() -> {
                    if (generation != refreshGeneration) return;
                    repository = root;
                    location.setText(root.toString());
                    branch.setText(snapshot.branch());
                    operationState.setText(operation);
                    branchSelector.getItems().setAll(branches);
                    branchSelector.setValue(snapshot.branch());
                    tagSelector.getItems().setAll(tags);
                    stashSelector.getItems().setAll(stashes);
                    String selectedRemote = remoteSelector.getValue();
                    remoteSelector.getItems().setAll(remotes);
                    if (remotes.contains(selectedRemote)) remoteSelector.setValue(selectedRemote);
                    else if (!remotes.isEmpty()) remoteSelector.setValue(remotes.getFirst());
                    status.getItems().setAll(snapshot.files());
                    history.getItems().setAll(commits);
                    loadDiff();
                    message.setText(snapshot.files().size() + " changed files · " + commits.size() + " commits");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> { if (generation == refreshGeneration) message.setText("Error: " + ex.getMessage()); });
            }
        });
    }
}
