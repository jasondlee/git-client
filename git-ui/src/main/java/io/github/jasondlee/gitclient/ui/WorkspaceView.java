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
import javafx.stage.FileChooser;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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
    private final CheckBox amend = new CheckBox("Amend last commit");
    private final SimpleBooleanProperty operationRunning = new SimpleBooleanProperty(false);
    private final java.util.prefs.Preferences preferences = java.util.prefs.Preferences.userNodeForPackage(WorkspaceView.class);
    private final MenuButton recentRepositories = new MenuButton("Recent");
    private Path repository;
    private long refreshGeneration;

    public WorkspaceView() {
        Button open = new Button("Open Repository");
        updateRecentRepositories();
        Button initialize = new Button("Initialize...");
        Button clone = new Button("Clone...");
        initialize.setOnAction(e -> initializeRepository());
        clone.setOnAction(e -> cloneRepository());
        Button refresh = new Button("Refresh");
        open.setOnAction(e -> openRepository());
        refresh.setOnAction(e -> refresh());
        Button clean = new Button("Clean Untracked");
        clean.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            Thread.startVirtualThread(() -> {
                try {
                    var preview = repositories.cleanPreview(current, false);
                    Platform.runLater(() -> {
                        if (preview.isEmpty()) {
                            message.setText("No untracked files to clean.");
                            return;
                        }
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("Clean Untracked Files");
                        confirm.setHeaderText("Permanently delete " + preview.size()
                                + " untracked path(s)?");
                        TextArea paths = new TextArea(String.join("\n", preview));
                        paths.setEditable(false);
                        paths.setPrefRowCount(12);
                        confirm.getDialogPane().setContent(paths);
                        confirm.showAndWait().filter(button -> button == ButtonType.OK)
                                .ifPresent(button -> runBranchAction(
                                        () -> repositories.cleanUntracked(current, false)));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> message.setText("Clean preview: " + ex.getMessage()));
                }
            });
        });
        Button discard = new Button("Discard Tracked");
        Button ignore = new Button("Ignore Pattern");
        discard.setOnAction(e -> {
            if (repository == null) return;
            var paths = status.getSelectionModel().getSelectedItems().stream()
                    .filter(file -> !file.indexStatus().equals("?"))
                    .map(ChangedFile::path).toList();
            if (paths.isEmpty()) {
                message.setText("Select tracked files to discard.");
                return;
            }
            Path current = repository;
            confirmOperation("Discard unstaged edits in " + paths.size()
                    + " tracked file(s)? This cannot be undone.",
                    () -> repositories.discardChanges(current, paths));
        });
        ignore.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Ignore Pattern");
            dialog.setHeaderText("Append a pattern to .gitignore");
            dialog.setContentText("Pattern:");
            dialog.showAndWait().ifPresent(pattern -> {
                Path current = repository;
                runBranchAction(() -> repositories.ignorePattern(current, pattern));
            });
        });
        Button stageLines = new Button("Stage Lines");
        Button unstageLines = new Button("Unstage Lines");
        stageLines.setOnAction(e -> chooseDiffLines(false));
        unstageLines.setOnAction(e -> chooseDiffLines(true));
        Button stageHunk = new Button("Stage Hunk");
        Button unstageHunk = new Button("Unstage Hunk");
        stageHunk.setOnAction(e -> chooseDiffHunk(false));
        unstageHunk.setOnAction(e -> chooseDiffHunk(true));
        Button stage = new Button("Stage Selected");
        Button unstage = new Button("Unstage Selected");
        stage.setOnAction(e -> changeStaging(false));
        unstage.setOnAction(e -> changeStaging(true));
        Button createBranch = new Button("New Branch");
        Button remoteBranches = new Button("Remote Branches");
        Button trackingBranch = new Button("Track Remote Branch");
        remoteBranches.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showTextReport("Remote-tracking branches",
                    () -> String.join("\n", repositories.remoteBranches(current)));
        });
        trackingBranch.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            Thread.startVirtualThread(() -> {
                try {
                    var remoteNames = repositories.remoteBranches(current);
                    Platform.runLater(() -> {
                        if (remoteNames.isEmpty()) {
                            message.setText("Fetch a remote to discover remote-tracking branches.");
                            return;
                        }
                        ChoiceDialog<String> select = new ChoiceDialog<>(remoteNames.getFirst(), remoteNames);
                        select.setTitle("Track Remote Branch");
                        select.setHeaderText("Choose remote-tracking branch");
                        select.showAndWait().ifPresent(remoteName -> {
                            String suggested = remoteName.contains("/")
                                    ? remoteName.substring(remoteName.indexOf('/') + 1) : remoteName;
                            TextInputDialog local = new TextInputDialog(suggested);
                            local.setTitle("Track Remote Branch");
                            local.setContentText("Local branch name:");
                            local.showAndWait().ifPresent(name -> runBranchAction(
                                    () -> repositories.createTrackingBranch(current, name, remoteName)));
                        });
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> message.setText("Remote branches: " + ex.getMessage()));
                }
            });
        });
        Button renameBranch = new Button("Rename Branch");
        renameBranch.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            String oldName = branchSelector.getValue();
            TextInputDialog dialog = new TextInputDialog(oldName);
            dialog.setTitle("Rename Branch");
            dialog.setContentText("New branch name:");
            dialog.showAndWait().ifPresent(newName -> {
                Path current = repository;
                runBranchAction(() -> repositories.renameBranch(current, oldName, newName));
            });
        });
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
        Button fixup = new Button("Fixup Selected Commit");
        Button autosquash = new Button("Autosquash Rebase");
        Button interactiveRebase = new Button("Interactive Rebase");
        fixup.setOnAction(e -> {
            CommitInfo selected = history.getSelectionModel().getSelectedItem();
            if (repository == null || selected == null) return;
            Path current = repository;
            confirmOperation("Create a fixup commit for " + selected.id().substring(0, 8)
                            + " from staged changes?",
                    () -> repositories.createFixupCommit(current, selected.id()));
        });
        interactiveRebase.setOnAction(e -> showInteractiveRebase());
        autosquash.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            Path current = repository;
            String target = branchSelector.getValue();
            confirmOperation("Autosquash fixup commits while rebasing onto '" + target
                            + "'? This rewrites local commit history.",
                    () -> repositories.rebaseAutosquash(current, target));
        });
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
        Button fileHistory = new Button("File History");
        Button searchHistory = new Button("Search Commits");
        fileHistory.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog dialog = new TextInputDialog(
                    status.getSelectionModel().getSelectedItem() == null ? ""
                            : status.getSelectionModel().getSelectedItem().path());
            dialog.setTitle("File History");
            dialog.setContentText("Repository-relative file path:");
            dialog.showAndWait().ifPresent(path -> {
                Path current = repository;
                showTextReport("History: " + path,
                        () -> String.join("\n", repositories.fileHistory(current, path, 100)));
            });
        });
        searchHistory.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Search Commit Messages");
            dialog.setContentText("Search text:");
            dialog.showAndWait().ifPresent(query -> {
                Path current = repository;
                showTextReport("Commit search: " + query,
                        () -> String.join("\n", repositories.searchCommits(current, query, 100)));
            });
        });
        Button compare = new Button("Compare Branch to HEAD");
        compare.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            Path current = repository;
            String target = branchSelector.getValue();
            showTextReport("HEAD vs " + target,
                    () -> repositories.compare(current, "HEAD", target));
        });
        Button startBisect = new Button("Start Bisect");
        Button bisectGood = new Button("Bisect Good");
        Button bisectBad = new Button("Bisect Bad");
        Button resetBisect = new Button("End Bisect");
        startBisect.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog badDialog = new TextInputDialog();
            badDialog.setTitle("Start Git Bisect");
            badDialog.setHeaderText("Identify a known bad commit");
            badDialog.setContentText("Bad commit hash:");
            badDialog.showAndWait().ifPresent(bad -> {
                TextInputDialog goodDialog = new TextInputDialog();
                goodDialog.setTitle("Start Git Bisect");
                goodDialog.setHeaderText("Identify an earlier known good commit");
                goodDialog.setContentText("Good commit hash:");
                goodDialog.showAndWait().ifPresent(good -> {
                    Path current = repository;
                    confirmOperation("Start bisect? Git will check out commits during the search.",
                            () -> repositories.startBisect(current, bad, good));
                });
            });
        });
        bisectGood.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            runBranchAction(() -> repositories.markBisect(current, true));
        });
        bisectBad.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            runBranchAction(() -> repositories.markBisect(current, false));
        });
        resetBisect.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            confirmOperation("End bisect and restore the original branch?",
                    () -> repositories.resetBisect(current));
        });
        Button graph = new Button("Commit Graph");
        graph.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showCommitGraph(current);
        });
        Button reflog = new Button("Reflog");
        Button blame = new Button("Blame File");
        Button worktrees = new Button("Worktrees");
        Button addWorktree = new Button("Add Worktree");
        Button removeWorktree = new Button("Remove Worktree");
        addWorktree.setOnAction(e -> {
            if (repository == null || branchSelector.getValue() == null) return;
            String selectedBranch = branchSelector.getValue();
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose parent directory for new worktree");
            java.io.File parent = chooser.showDialog(getScene().getWindow());
            if (parent == null) return;
            TextInputDialog dialog = new TextInputDialog(selectedBranch.replace('/', '-'));
            dialog.setTitle("Add Worktree");
            dialog.setHeaderText("New worktree for " + selectedBranch);
            dialog.setContentText("New folder name:");
            dialog.showAndWait().ifPresent(folder -> {
                if (folder.isBlank() || folder.equals(".") || folder.equals("..")
                        || folder.contains("/") || folder.contains("\\\\")) {
                    message.setText("Invalid worktree folder name");
                    return;
                }
                Path current = repository;
                Path destination = parent.toPath().resolve(folder);
                runBranchAction(() -> repositories.addWorktree(current, destination, selectedBranch));
            });
        });
        removeWorktree.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showWorktreeRemovalDialog(current);
        });
        reflog.setOnAction(e -> showTextReport("Reflog",
                () -> String.join("\n", repositories.reflog(repository, 100))));
        blame.setOnAction(e -> {
            ChangedFile selected = status.getSelectionModel().getSelectedItem();
            if (selected != null) showTextReport("Blame: " + selected.path(),
                    () -> repositories.blame(repository, selected.path()));
        });
        worktrees.setOnAction(e -> showTextReport("Worktrees",
                () -> String.join("\n", repositories.worktrees(repository))));
        Button exportPatch = new Button("Export Commit Patch");
        Button applyPatch = new Button("Apply Patch");
        Button configView = new Button("Local Config");
        Button configEdit = new Button("Edit Local Config");
        exportPatch.setOnAction(e -> {
            CommitInfo selected = history.getSelectionModel().getSelectedItem();
            if (repository == null || selected == null) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save commit patch");
            chooser.setInitialFileName(selected.id().substring(0, 8) + ".patch");
            java.io.File target = chooser.showSaveDialog(getScene().getWindow());
            if (target == null) return;
            Path current = repository;
            Thread.startVirtualThread(() -> {
                try {
                    String patch = repositories.createPatch(current, selected.id());
                    java.nio.file.Files.writeString(target.toPath(), patch);
                    Platform.runLater(() -> message.setText("Patch exported: " + target));
                } catch (Exception ex) {
                    Platform.runLater(() -> message.setText("Patch export failed: " + ex.getMessage()));
                }
            });
        });
        applyPatch.setOnAction(e -> {
            if (repository == null) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose patch to apply");
            java.io.File patch = chooser.showOpenDialog(getScene().getWindow());
            if (patch == null) return;
            Path current = repository;
            confirmOperation("Apply patch " + patch.getName() + " to working tree?",
                    () -> repositories.applyPatch(current, patch.toPath()));
        });
        configEdit.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog keyDialog = new TextInputDialog("user.name");
            keyDialog.setTitle("Edit Local Git Configuration");
            keyDialog.setHeaderText("Choose a repository-local configuration key");
            keyDialog.setContentText("Key:");
            keyDialog.showAndWait().ifPresent(key -> {
                TextInputDialog valueDialog = new TextInputDialog();
                valueDialog.setTitle("Edit Local Git Configuration");
                valueDialog.setHeaderText(key);
                valueDialog.setContentText("New value:");
                valueDialog.showAndWait().ifPresent(value -> {
                    Path current = repository;
                    confirmOperation("Set local Git configuration '" + key + "'?",
                            () -> repositories.setConfiguration(current, key, value));
                });
            });
        });
        configView.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showTextReport("Local Git configuration",
                    () -> String.join("\n", repositories.gitConfiguration(current)));
        });
        Button reset = new Button("Reset...");
        Button hooks = new Button("Installed Hooks");
        Button lfsFiles = new Button("LFS Files");
        Button lfsTrack = new Button("Track with LFS");
        hooks.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showTextReport("Installed Git hooks",
                    () -> String.join("\n", repositories.installedHooks(current)));
        });
        lfsFiles.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            showTextReport("Git LFS tracked files",
                    () -> String.join("\n", repositories.lfsTrackedFiles(current)));
        });
        lfsTrack.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog dialog = new TextInputDialog("*.psd");
            dialog.setTitle("Track with Git LFS");
            dialog.setHeaderText("Add a Git LFS tracking pattern");
            dialog.setContentText("Pattern:");
            dialog.showAndWait().ifPresent(pattern -> {
                Path current = repository;
                confirmOperation("Track '" + pattern
                                + "' with Git LFS? This updates .gitattributes.",
                        () -> repositories.trackLfsPattern(current, pattern));
            });
        });
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
        Button rebaseProgress = new Button("Rebase Progress");
        Button rebaseRecovery = new Button("Rebase Recovery");
        rebaseRecovery.setOnAction(e -> showRebaseRecovery());
        Button resolveConflicts = new Button("Resolve Conflict");
        resolveConflicts.setOnAction(e -> showConflictEditor());
        continueOperation.setOnAction(e -> operateOnSequencer(false));
        abortOperation.setOnAction(e -> operateOnSequencer(true));
        skipRebase.setOnAction(e -> {
            if (repository == null) return;
            Path current = repository;
            confirmOperation("Skip the current rebase commit?",
                    () -> repositories.skipRebase(current));
        });
        rebaseProgress.setOnAction(e -> {
            if (repository != null) {
                Path current = repository;
                showTextReport("Rebase Progress", () -> repositories.rebaseProgress(current));
            }
        });
        conflicts.setOnAction(e -> showTextReport("Unresolved conflicts",
                () -> String.join("\n", repositories.conflictedFiles(repository))));
        Button createTag = new Button("New Tag");
        Button annotatedTag = new Button("Annotated Tag");
        annotatedTag.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog nameDialog = new TextInputDialog();
            nameDialog.setTitle("Annotated Tag");
            nameDialog.setContentText("Tag name:");
            nameDialog.showAndWait().ifPresent(name -> {
                TextInputDialog messageDialog = new TextInputDialog();
                messageDialog.setTitle("Annotated Tag");
                messageDialog.setContentText("Annotation:");
                messageDialog.showAndWait().ifPresent(annotation -> {
                    Path current = repository;
                    runBranchAction(() -> repositories.createAnnotatedTag(current, name, annotation));
                });
            });
        });
        Button deleteTag = new Button("Delete Tag");
        createTag.setOnAction(e -> createTag());
        deleteTag.setOnAction(e -> deleteTag());
        tagSelector.setPromptText("Local tags");
        Button addRemote = new Button("Add Remote");
        Button renameRemote = new Button("Rename Remote");
        Button removeRemote = new Button("Remove Remote");
        Button remoteDetails = new Button("Remote URL");
        addRemote.setOnAction(e -> {
            if (repository == null) return;
            TextInputDialog nameDialog = new TextInputDialog("origin");
            nameDialog.setTitle("Add Remote");
            nameDialog.setHeaderText("New remote");
            nameDialog.setContentText("Name:");
            nameDialog.showAndWait().ifPresent(name -> {
                TextInputDialog urlDialog = new TextInputDialog();
                urlDialog.setTitle("Add Remote");
                urlDialog.setHeaderText("Remote " + name);
                urlDialog.setContentText("URL:");
                urlDialog.showAndWait().ifPresent(url -> {
                    Path current = repository;
                    runBranchAction(() -> repositories.addRemote(current, name, url));
                });
            });
        });
        renameRemote.setOnAction(e -> {
            if (repository == null || remoteSelector.getValue() == null) return;
            String oldName = remoteSelector.getValue();
            TextInputDialog dialog = new TextInputDialog(oldName);
            dialog.setTitle("Rename Remote");
            dialog.setContentText("New name:");
            dialog.showAndWait().ifPresent(name -> {
                Path current = repository;
                runBranchAction(() -> repositories.renameRemote(current, oldName, name));
            });
        });
        removeRemote.setOnAction(e -> {
            if (repository == null || remoteSelector.getValue() == null) return;
            String name = remoteSelector.getValue();
            Path current = repository;
            confirmOperation("Remove remote '" + name + "' from this repository?",
                    () -> repositories.removeRemote(current, name));
        });
        remoteDetails.setOnAction(e -> {
            if (repository == null || remoteSelector.getValue() == null) return;
            Path current = repository;
            String name = remoteSelector.getValue();
            showTextReport("Remote URL: " + name,
                    () -> repositories.remoteUrl(current, name));
        });
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
        MenuButton branchesMenu = actionsMenu("Branches", createBranch, renameBranch,
                remoteBranches, trackingBranch, switchBranch,
                mergeBranch, abortMerge, deleteBranch, rebase, autosquash, interactiveRebase);
        MenuButton historyMenu = actionsMenu("History", cherryPick, revert, fixup, compare,
                fileHistory, searchHistory, startBisect, bisectGood, bisectBad,
                resetBisect, graph, reflog,
                blame, reset);
        MenuButton operationsMenu = actionsMenu("Operations", continueOperation,
                abortOperation, skipRebase, rebaseProgress, rebaseRecovery, conflicts, resolveConflicts);
        MenuButton remoteMenu = actionsMenu("Remotes", addRemote, renameRemote,
                removeRemote, remoteDetails);
        MenuButton advancedMenu = actionsMenu("Advanced", exportPatch, applyPatch,
                configView, configEdit, hooks, lfsFiles, lfsTrack,
                worktrees, addWorktree, removeWorktree, submodules,
                updateSubmodules, createTag, annotatedTag, deleteTag, stash, applyStash, dropStash);
        ToolBar toolbar = new ToolBar(open, recentRepositories, initialize, clone, refresh, stage, unstage, stageHunk, unstageHunk, stageLines, unstageLines,
                discard, clean, ignore,
                new Separator(), branchSelector, branchesMenu,
                new Separator(), historyMenu, operationsMenu, advancedMenu,
                new Separator(), tagSelector, stashSelector,
                new Separator(), remoteSelector, remoteMenu, fetch, pull, push,
                new Separator(), branch, location);
        setTop(toolbar);
        sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) return;
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O,
                    KeyCombination.SHORTCUT_DOWN), this::openRepository);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R,
                    KeyCombination.SHORTCUT_DOWN), this::refresh);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER,
                    KeyCombination.SHORTCUT_DOWN), this::commit);
        });
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
        amend.setOnAction(e -> commitButton.setText(amend.isSelected() ? "Amend Commit" : "Commit Staged"));
        commitButton.disableProperty().bind(commitMessage.textProperty().isEmpty().or(operationRunning));
        VBox commitPane = new VBox(6, new Label("Commit staged changes"), commitMessage,
                amend, commitButton, message);
        commitPane.setPadding(new Insets(8));
        setBottom(commitPane);
    }
    private void showWorktreeRemovalDialog(Path current) {
        Thread.startVirtualThread(() -> {
            try {
                var paths = repositories.worktrees(current).stream()
                        .filter(path -> !Path.of(path).toAbsolutePath().normalize()
                                .equals(current.toAbsolutePath().normalize()))
                        .toList();
                Platform.runLater(() -> {
                    if (paths.isEmpty()) {
                        message.setText("No linked worktrees to remove.");
                        return;
                    }
                    ChoiceDialog<String> dialog = new ChoiceDialog<>(paths.getFirst(), paths);
                    dialog.setTitle("Remove Worktree");
                    dialog.setHeaderText("Select a linked worktree");
                    dialog.showAndWait().ifPresent(selected ->
                            confirmOperation("Remove worktree at " + selected
                                            + "? Git refuses removal if it contains changes.",
                                    () -> repositories.removeWorktree(current, Path.of(selected))));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Worktrees: " + ex.getMessage()));
            }
        });
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
    private void showConflictEditor() {
        if (repository == null) return;
        Path current = repository;
        Thread.startVirtualThread(() -> {
            try {
                var paths = repositories.conflictedFiles(current);
                Platform.runLater(() -> {
                    if (paths.isEmpty()) {
                        message.setText("No unresolved conflicts.");
                        return;
                    }
                    ChoiceDialog<String> select = new ChoiceDialog<>(paths.getFirst(), paths);
                    select.setTitle("Resolve Merge Conflict");
                    select.setHeaderText("Select a conflicted file");
                    select.showAndWait().ifPresent(path -> loadConflictEditor(current, path));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Conflicts: " + ex.getMessage()));
            }
        });
    }
    private void loadConflictEditor(Path current, String path) {
        Thread.startVirtualThread(() -> {
            try {
                String base = repositories.conflictVersion(current, path, 1);
                String ours = repositories.conflictVersion(current, path, 2);
                String theirs = repositories.conflictVersion(current, path, 3);
                String working = java.nio.file.Files.readString(current.resolve(path));
                Platform.runLater(() -> {
                    Dialog<String> dialog = new Dialog<>();
                    dialog.setTitle("Resolve: " + path);
                    dialog.setHeaderText("Review both versions and edit the resolved contents");
                    TextArea baseView = new TextArea(base);
                    baseView.setEditable(false);
                    baseView.setPrefRowCount(8);
                    TextArea oursView = new TextArea(ours);
                    TextArea theirsView = new TextArea(theirs);
                    TextArea result = new TextArea(working);
                    oursView.setEditable(false);
                    theirsView.setEditable(false);
                    oursView.setPrefRowCount(10);
                    theirsView.setPrefRowCount(10);
                    result.setPrefRowCount(14);
                    Button useOurs = new Button("Use Ours");
                    Button useTheirs = new Button("Use Theirs");
                    useOurs.setOnAction(e -> result.setText(ours));
                    useTheirs.setOnAction(e -> result.setText(theirs));
                    VBox content = new VBox(8, new Label("Common ancestor"), baseView,
                            new Label("Ours"), oursView,
                            new Label("Theirs"), theirsView,
                            new HBox(8, useOurs, useTheirs),
                            new Label("Resolution (editable)"), result);
                    dialog.getDialogPane().setContent(content);
                    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                    dialog.setResultConverter(button -> button == ButtonType.OK ? result.getText() : null);
                    dialog.showAndWait().ifPresent(contents ->
                            confirmOperation("Save and stage resolution for '" + path + "'?",
                                    () -> repositories.resolveConflict(current, path, contents)));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Conflict editor: " + ex.getMessage()));
            }
        });
    }
    private void showRebaseRecovery() {
        if (repository == null) return;
        Path current = repository;
        Thread.startVirtualThread(() -> {
            try {
                String progress = repositories.rebaseProgress(current);
                Platform.runLater(() -> {
                    Dialog<String> dialog = new Dialog<>();
                    dialog.setTitle("Rebase Recovery");
                    dialog.setHeaderText("Inspect and recover an interrupted rebase");
                    TextArea details = new TextArea(progress);
                    details.setEditable(false);
                    details.setWrapText(true);
                    details.setPrefRowCount(12);
                    details.setPrefColumnCount(72);
                    Label guidance = new Label(
                            "Resolve conflicts and stage the files before continuing. "
                                    + "Skip discards the current commit; Abort restores the pre-rebase state.");
                    guidance.setWrapText(true);
                    dialog.getDialogPane().setContent(new VBox(8, details, guidance));
                    ButtonType resolve = new ButtonType("Resolve Conflicts",
                            ButtonBar.ButtonData.OTHER);
                    ButtonType resume = new ButtonType("Continue",
                            ButtonBar.ButtonData.OTHER);
                    ButtonType skip = new ButtonType("Skip Commit",
                            ButtonBar.ButtonData.OTHER);
                    ButtonType abort = new ButtonType("Abort Rebase",
                            ButtonBar.ButtonData.OTHER);
                    dialog.getDialogPane().getButtonTypes().addAll(
                            resolve, resume, skip, abort, ButtonType.CANCEL);
                    boolean active = !progress.startsWith("No rebase in progress.");
                    for (ButtonType option : java.util.List.of(resolve, resume, skip, abort)) {
                        dialog.getDialogPane().lookupButton(option).setDisable(!active);
                    }
                    dialog.setResultConverter(button -> {
                        if (button == resolve) return "resolve";
                        if (button == resume) return "continue";
                        if (button == skip) return "skip";
                        if (button == abort) return "abort";
                        return null;
                    });
                    dialog.showAndWait().ifPresent(selection -> {
                        switch (selection) {
                            case "resolve" -> showConflictEditor();
                            case "continue" -> runBranchAction(() -> repositories.continueRebase(current));
                            case "skip" -> confirmOperation(
                                    "Skip the current commit? Its changes may be discarded.",
                                    () -> repositories.skipRebase(current));
                            case "abort" -> confirmOperation(
                                    "Abort this rebase and restore the branch to its previous state?",
                                    () -> repositories.abortRebase(current));
                            default -> { }
                        }
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Rebase recovery: " + ex.getMessage()));
            }
        });
    }

    private void showInteractiveRebase() {
        if (repository == null || branchSelector.getValue() == null) {
            message.setText("Choose a base branch before interactive rebase.");
            return;
        }
        Path current = repository;
        String base = branchSelector.getValue();
        Thread.startVirtualThread(() -> {
            try {
                String initial = repositories.interactiveRebasePlan(current, base);
                Platform.runLater(() -> {
                    if (initial.isBlank()) {
                        message.setText("No commits to rebase onto " + base);
                        return;
                    }
                    Dialog<String> dialog = new Dialog<>();
                    dialog.setTitle("Interactive Rebase onto " + base);
                    dialog.setHeaderText("Reorder commits, reword messages, squash, fixup, or drop");
                    javafx.collections.ObservableList<String> commits =
                            javafx.collections.FXCollections.observableArrayList(initial.lines().toList());
                    java.util.Map<String, String> replacementMessages = new java.util.HashMap<>();
                    ListView<String> commitList = new ListView<>(commits);
                    commitList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
                        @Override
                        protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty ? null : item);
                            setStyle(empty ? "" : "-fx-font-family: monospace;");
                        }
                        {
                            setOnDragDetected(event -> {
                                if (isEmpty()) return;
                                javafx.scene.input.Dragboard board =
                                        startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                                javafx.scene.input.ClipboardContent payload =
                                        new javafx.scene.input.ClipboardContent();
                                payload.putString(getItem().split(" ", 3)[1]);
                                board.setContent(payload);
                                event.consume();
                            });
                            setOnDragOver(event -> {
                                if (event.getGestureSource() != this
                                        && event.getDragboard().hasString()
                                        && commits.stream().anyMatch(line -> line.split(" ", 3)[1]
                                                .equals(event.getDragboard().getString()))) {
                                    event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                                }
                                event.consume();
                            });
                            setOnDragDropped(event -> {
                                boolean moved = false;
                                if (event.getDragboard().hasString()) {
                                    String hash = event.getDragboard().getString();
                                    int source = -1;
                                    for (int i = 0; i < commits.size(); i++) {
                                        if (commits.get(i).split(" ", 3)[1].equals(hash)) {
                                            source = i;
                                            break;
                                        }
                                    }
                                    int target = Math.min(getIndex(), commits.size());
                                    if (source >= 0 && source != target && source + 1 != target) {
                                        String movedCommit = commits.remove(source);
                                        if (source < target) target--;
                                        commits.add(target, movedCommit);
                                        view.getSelectionModel().select(target);
                                        moved = true;
                                    }
                                }
                                event.setDropCompleted(moved);
                                event.consume();
                            });
                        }
                    });
                    commitList.setPrefHeight(360);
                    commitList.setPrefWidth(750);
                    commitList.getSelectionModel().selectFirst();
                    ComboBox<String> action = new ComboBox<>(
                            javafx.collections.FXCollections.observableArrayList(
                                    "pick", "reword", "squash", "fixup", "drop"));
                    action.setValue("pick");
                    action.setOnAction(event -> {
                        int index = commitList.getSelectionModel().getSelectedIndex();
                        if (index < 0) return;
                        String line = commits.get(index);
                        String[] fields = line.split(" ", 3);
                        if (fields[0].equals(action.getValue())) return;
                        if (action.getValue().equals("reword")) {
                            Dialog<String> messageDialog = new Dialog<>();
                            messageDialog.setTitle("Reword Commit");
                            messageDialog.setHeaderText(
                                    "New commit message for " + fields[1].substring(0, 8));
                            TextArea messageEditor = new TextArea(
                                    fields.length > 2 ? fields[2] : "");
                            messageEditor.setPromptText("Subject, optionally followed by a blank line and body");
                            messageEditor.setPrefRowCount(7);
                            messageEditor.setPrefColumnCount(65);
                            messageEditor.setWrapText(true);
                            messageDialog.getDialogPane().setContent(messageEditor);
                            messageDialog.getDialogPane().getButtonTypes().addAll(
                                    ButtonType.OK, ButtonType.CANCEL);
                            messageDialog.setResultConverter(button ->
                                    button == ButtonType.OK ? messageEditor.getText() : null);
                            java.util.Optional<String> replacement = messageDialog.showAndWait();
                            if (replacement.isEmpty() || replacement.get().isBlank()) {
                                action.setValue(fields[0]);
                                return;
                            }
                            replacementMessages.put(fields[1], replacement.get().strip());
                        } else {
                            replacementMessages.remove(fields[1]);
                        }
                        commits.set(index, action.getValue() + line.substring(line.indexOf(' ')));
                        commitList.getSelectionModel().select(index);
                    });
                    commitList.getSelectionModel().selectedItemProperty().addListener(
                            (observable, previous, selected) -> {
                                if (selected != null) {
                                    action.setValue(selected.substring(0, selected.indexOf(' ')));
                                }
                            });
                    Button up = new Button("Move Up");
                    Button down = new Button("Move Down");
                    up.setOnAction(event -> {
                        int index = commitList.getSelectionModel().getSelectedIndex();
                        if (index > 0) {
                            java.util.Collections.swap(commits, index, index - 1);
                            commitList.getSelectionModel().select(index - 1);
                        }
                    });
                    down.setOnAction(event -> {
                        int index = commitList.getSelectionModel().getSelectedIndex();
                        if (index >= 0 && index < commits.size() - 1) {
                            java.util.Collections.swap(commits, index, index + 1);
                            commitList.getSelectionModel().select(index + 1);
                        }
                    });
                    Label instructions = new Label(
                            "Select a commit to choose its action (Reword opens a message editor). Drag to reorder. "
                                    + "Squash/fixup must follow a kept commit. "
                                    + "This rewrites local history.");
                    instructions.setWrapText(true);
                    HBox controls = new HBox(8, new Label("Action:"), action, up, down);
                    VBox content = new VBox(8, instructions, commitList, controls);
                    dialog.getDialogPane().setContent(content);
                    dialog.getDialogPane().getButtonTypes().addAll(
                            ButtonType.OK, ButtonType.CANCEL);
                    dialog.setResultConverter(button -> button == ButtonType.OK
                            ? String.join("\n", commits) + "\n" : null);
                    dialog.showAndWait().ifPresent(plan ->
                            Thread.startVirtualThread(() -> {
                                try {
                                    repositories.validateInteractiveRebasePlan(current, base, plan);
                                    Platform.runLater(() ->
                                            confirmOperation(
                                                    "Rewrite local commits using this interactive rebase plan?",
                                                    () -> repositories.interactiveRebase(current, base, plan, replacementMessages)));
                                } catch (Exception ex) {
                                    Platform.runLater(() -> {
                                        Alert alert = new Alert(Alert.AlertType.ERROR);
                                        alert.setTitle("Invalid interactive rebase plan");
                                        alert.setHeaderText("The rebase plan cannot be applied");
                                        alert.setContentText(ex.getMessage());
                                        alert.showAndWait();
                                    });
                                }
                            }));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Interactive rebase: " + ex.getMessage()));
            }
        });
    }

    private void showCommitGraph(Path current) {
        Thread.startVirtualThread(() -> {
            try {
                String graph = repositories.commitGraph(current, 500);
                Platform.runLater(() -> {
                    Dialog<Void> dialog = new Dialog<>();
                    dialog.setTitle("Commit Graph — All Branches");
                    dialog.setHeaderText("Recent commits and branch topology");
                    javafx.scene.layout.VBox rows = new javafx.scene.layout.VBox(2);
                    javafx.scene.control.TextField search = new javafx.scene.control.TextField();
                    search.setPromptText("Filter commits by hash, subject, or ref");
                    javafx.scene.control.Label count = new javafx.scene.control.Label();
                    javafx.scene.paint.Color[] palette = {
                            javafx.scene.paint.Color.DODGERBLUE,
                            javafx.scene.paint.Color.MEDIUMSEAGREEN,
                            javafx.scene.paint.Color.DARKORANGE,
                            javafx.scene.paint.Color.MEDIUMPURPLE,
                            javafx.scene.paint.Color.DEEPPINK,
                            javafx.scene.paint.Color.TEAL
                    };
                    for (String line : graph.split("\\R")) {
                        int boundary = 0;
                        while (boundary < line.length()
                                && "*|/\\ ".indexOf(line.charAt(boundary)) >= 0) {
                            boundary++;
                        }
                        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
                        for (int column = 0; column < boundary; column++) {
                            javafx.scene.text.Text lane = new javafx.scene.text.Text(
                                    String.valueOf(line.charAt(column)));
                            lane.setFill(palette[(column / 2) % palette.length]);
                            lane.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
                            flow.getChildren().add(lane);
                        }
                        javafx.scene.text.Text description =
                                new javafx.scene.text.Text(line.substring(boundary));
                        description.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
                        flow.getChildren().add(description);
                        java.util.regex.Matcher commitId =
                                java.util.regex.Pattern.compile(
                                        "(?<![0-9a-fA-F])[0-9a-fA-F]{7,40}(?![0-9a-fA-F])")
                                        .matcher(line.substring(boundary));
                        if (commitId.find()) {
                            String id = commitId.group();
                            flow.setOnMouseClicked(event -> {
                                dialog.close();
                                showTextReport("Commit " + id,
                                        () -> repositories.showCommit(current, id));
                            });
                            flow.setStyle("-fx-cursor: hand; -fx-padding: 2px 4px;");
                        }
                        flow.setUserData(line);
                        rows.getChildren().add(flow);
                    }
                    javafx.scene.control.ScrollPane scroll =
                            new javafx.scene.control.ScrollPane(rows);
                    search.textProperty().addListener((observable, oldValue, query) -> {
                        String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
                        int visible = 0;
                        for (javafx.scene.Node node : rows.getChildren()) {
                            boolean match = needle.isEmpty()
                                    || ((String) node.getUserData())
                                            .toLowerCase(java.util.Locale.ROOT).contains(needle);
                            node.setVisible(match);
                            node.setManaged(match);
                            if (match) visible++;
                        }
                        count.setText(visible + " matching graph rows");
                    });
                    count.setText(rows.getChildren().size() + " graph rows");
                    scroll.setFitToWidth(true);
                    scroll.setPrefViewportWidth(850);
                    scroll.setPrefViewportHeight(550);
                    javafx.scene.layout.VBox graphContent = new javafx.scene.layout.VBox(6, search, count, scroll);
                    dialog.getDialogPane().setContent(graphContent);
                    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    dialog.show();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Commit graph: " + ex.getMessage()));
            }
        });
    }

    private void chooseDiffLines(boolean unstage) {
        ChangedFile selected = status.getSelectionModel().getSelectedItem();
        if (repository == null || selected == null) {
            message.setText("Select a changed tracked file first.");
            return;
        }
        Path current = repository;
        Thread.startVirtualThread(() -> {
            try {
                var hunks = repositories.diffHunks(current, selected.path(), unstage);
                Platform.runLater(() -> {
                    if (hunks.isEmpty()) {
                        message.setText("No changed lines available.");
                        return;
                    }
                    ChoiceDialog<Integer> chooseHunk = new ChoiceDialog<>(0,
                            java.util.stream.IntStream.range(0, hunks.size()).boxed().toList());
                    chooseHunk.setTitle(unstage ? "Unstage Lines" : "Stage Lines");
                    chooseHunk.setHeaderText("Choose a hunk (zero-based index)");
                    chooseHunk.showAndWait().ifPresent(index -> {
                        String patch = hunks.get(index);
                        int marker = patch.indexOf("\n@@ ");
                        int body = patch.indexOf('\n', marker + 1);
                        if (body < 0) return;
                        Dialog<java.util.Set<Integer>> dialog = new Dialog<>();
                        dialog.setTitle(unstage ? "Unstage Lines" : "Stage Lines");
                        dialog.setHeaderText("Select the changed lines to "
                                + (unstage ? "unstage" : "stage"));
                        VBox choices = new VBox(4);
                        java.util.List<CheckBox> boxes = new java.util.ArrayList<>();
                        int changeIndex = 0;
                        for (String line : patch.substring(body + 1).split("\n")) {
                            if (line.startsWith("+") || line.startsWith("-")) {
                                CheckBox checkbox = new CheckBox(line);
                                checkbox.setUserData(changeIndex++);
                                boxes.add(checkbox);
                                choices.getChildren().add(checkbox);
                            }
                        }
                        ScrollPane scroll = new ScrollPane(choices);
                        scroll.setFitToWidth(true);
                        scroll.setPrefViewportHeight(350);
                        dialog.getDialogPane().setContent(scroll);
                        dialog.getDialogPane().getButtonTypes().addAll(
                                ButtonType.OK, ButtonType.CANCEL);
                        dialog.setResultConverter(button -> {
                            if (button != ButtonType.OK) return null;
                            java.util.Set<Integer> chosen = new java.util.HashSet<>();
                            for (CheckBox box : boxes) {
                                if (box.isSelected()) chosen.add((Integer) box.getUserData());
                            }
                            return chosen.isEmpty() ? null : chosen;
                        });
                        dialog.showAndWait().ifPresent(lines ->
                                runBranchAction(() -> repositories.applyIndexHunk(current,
                                        repositories.selectDiffLines(patch, lines), unstage)));
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Line selection: " + ex.getMessage()));
            }
        });
    }
    private void chooseDiffHunk(boolean unstage) {
        ChangedFile selected = status.getSelectionModel().getSelectedItem();
        if (repository == null || selected == null) {
            message.setText("Select a changed tracked file first.");
            return;
        }
        Path current = repository;
        Thread.startVirtualThread(() -> {
            try {
                var hunks = repositories.diffHunks(current, selected.path(), unstage);
                Platform.runLater(() -> {
                    if (hunks.isEmpty()) {
                        message.setText("No " + (unstage ? "staged" : "unstaged")
                                + " hunks for " + selected.path());
                        return;
                    }
                    Dialog<Integer> dialog = new Dialog<>();
                    dialog.setTitle(unstage ? "Unstage Hunk" : "Stage Hunk");
                    dialog.setHeaderText(selected.path());
                    ListView<String> list = new ListView<>();
                    list.getItems().setAll(hunks);
                    list.setCellFactory(view -> new ListCell<>() {
                        @Override protected void updateItem(String patch, boolean empty) {
                            super.updateItem(patch, empty);
                            if (empty || patch == null) {
                                setText(null);
                            } else {
                                int index = hunks.indexOf(patch);
                                int at = patch.indexOf("@@ ");
                                int end = patch.indexOf("\n", at);
                                setText("Hunk " + (index + 1) + ": "
                                        + patch.substring(at, end < 0 ? patch.length() : end));
                            }
                        }
                    });
                    list.getSelectionModel().selectFirst();
                    TextArea preview = new TextArea(hunks.getFirst());
                    preview.setEditable(false);
                    preview.setPrefRowCount(14);
                    list.getSelectionModel().selectedIndexProperty().addListener(
                            (obs, oldValue, newValue) -> {
                                int index = newValue.intValue();
                                if (index >= 0) preview.setText(hunks.get(index));
                            });
                    VBox content = new VBox(8, list, preview);
                    dialog.getDialogPane().setContent(content);
                    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                    dialog.setResultConverter(button -> button == ButtonType.OK
                            ? list.getSelectionModel().getSelectedIndex() : null);
                    dialog.showAndWait().ifPresent(index -> {
                        if (index >= 0) runBranchAction(
                                () -> repositories.applyIndexHunk(current, hunks.get(index), unstage));
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText("Hunk operation: " + ex.getMessage()));
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
        boolean amending = amend.isSelected();
        if (text.isBlank()) {
            message.setText("Enter a commit message.");
            return;
        }
        if (amending) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Amending rewrites the latest commit. Avoid amending commits already pushed to others.",
                    ButtonType.OK, ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        operationRunning.set(true);
        message.setText("Committing...");
        Thread.startVirtualThread(() -> {
            try {
                String id = amending ? repositories.amendCommit(current, text)
                        : repositories.commit(current, text);
                Platform.runLater(() -> {
                    operationRunning.set(false);
                    commitMessage.clear();
                    amend.setSelected(false);
                    commitButton.setText("Commit Staged");
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
    public javafx.beans.property.ReadOnlyStringProperty repositoryLocationProperty() {
        return location.textProperty();
    }
    public void openPath(Path path) {
        selectRepository(path);
    }
    private void selectRepository(Path path) {
        repository = path.toAbsolutePath().normalize();
        location.setText(repository.toString());
        refresh();
    }
    private void rememberRepository(Path root) {
        String value = root.toAbsolutePath().normalize().toString();
        java.util.List<String> recent = new java.util.ArrayList<>();
        recent.add(value);
        for (String entry : preferences.get("recentRepositories", "").split("\\n")) {
            if (!entry.isBlank() && !entry.equals(value) && recent.size() < 10) {
                recent.add(entry);
            }
        }
        preferences.put("recentRepositories", String.join("\n", recent));
        updateRecentRepositories();
    }
    private void updateRecentRepositories() {
        recentRepositories.getItems().clear();
        for (String entry : preferences.get("recentRepositories", "").split("\\n")) {
            if (entry.isBlank()) continue;
            MenuItem item = new MenuItem(entry);
            item.setOnAction(e -> selectRepository(Path.of(entry)));
            recentRepositories.getItems().add(item);
        }
        recentRepositories.setDisable(recentRepositories.getItems().isEmpty());
    }
    private void openRepository() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Git repository");
        java.io.File chosen = chooser.showDialog(getScene().getWindow());
        if (chosen == null) return;
        selectRepository(chosen.toPath());
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
                    rememberRepository(root);
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
