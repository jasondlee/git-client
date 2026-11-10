package io.github.jasondlee.gitclient.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Native JavaFX visualization of Git's --graph output. Connector-only lines
 * remain visible during searches so that filtering cannot distort topology.
 */
public final class CommitGraphView extends VBox {
    private static final Color[] COLORS = {
            Color.DODGERBLUE, Color.MEDIUMSEAGREEN, Color.DARKORANGE,
            Color.MEDIUMPURPLE, Color.DEEPPINK, Color.TEAL
    };
    private static final double CELL = 12;
    private static final double HEIGHT = 24;
    private final ObservableList<GraphLine> lines = FXCollections.observableArrayList();
    private final ListView<GraphLine> list = new ListView<>(lines);
    private final TextField search = new TextField();
    private final Label matches = new Label("No commits loaded");
    private final Consumer<String> openCommit;
    private final Consumer<String> copyCommit;
    private final Consumer<String> cherryPick;
    private final Consumer<String> revert;
    private final Consumer<String> branchAt;
    private final Consumer<String> tagAt;
    private final Consumer<String> compare;
    private int width = 1;

    public CommitGraphView(Consumer<String> openCommit, Consumer<String> copyCommit,
            Consumer<String> cherryPick, Consumer<String> revert,
            Consumer<String> branchAt, Consumer<String> tagAt,
            Consumer<String> compare) {
        super(6);
        this.openCommit = openCommit;
        this.copyCommit = copyCommit;
        this.cherryPick = cherryPick;
        this.revert = revert;
        this.branchAt = branchAt;
        this.tagAt = tagAt;
        this.compare = compare;
        search.setPromptText("Find commit by SHA, subject, or ref");
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            updateMatches();
            list.refresh();
        });
        list.setFixedCellSize(HEIGHT);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(GraphLine line, boolean empty) {
                super.updateItem(line, empty);
                if (empty || line == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                setText(null);
                setPadding(javafx.geometry.Insets.EMPTY);
                Canvas canvas = new Canvas(width * CELL, HEIGHT);
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.setLineWidth(2.2);
                for (int column = 0; column < line.lanes().length(); column++) {
                    char symbol = line.lanes().charAt(column);
                    double x = column * CELL + CELL / 2;
                    double mid = HEIGHT / 2;
                    Color color = COLORS[(column / 2) % COLORS.length];
                    gc.setStroke(color);
                    gc.setFill(color);
                    switch (symbol) {
                        case '|', ':' -> gc.strokeLine(x, 0, x, HEIGHT);
                        case '/' -> gc.strokeLine(x + CELL / 2, 0, x - CELL / 2, HEIGHT);
                        case '\\' -> gc.strokeLine(x - CELL / 2, 0, x + CELL / 2, HEIGHT);
                        case '_' -> gc.strokeLine(x - CELL / 2, mid, x + CELL / 2, mid);
                        case '*', 'o' -> {
                            gc.strokeLine(x, 0, x, HEIGHT);
                            gc.fillOval(x - 4.5, mid - 4.5, 9, 9);
                        }
                        default -> { }
                    }
                }
                Label description = new Label(line.description());
                description.setStyle("-fx-font-family: monospace;");
                description.setMaxWidth(Double.MAX_VALUE);
                boolean match = search.getText().isBlank()
                        || line.description().toLowerCase(Locale.ROOT)
                                .contains(search.getText().strip().toLowerCase(Locale.ROOT));
                description.setOpacity(match ? 1 : 0.35);
                HBox row = new HBox(8, canvas, description);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                String hash = line.hash();
                if (hash == null) {
                    setContextMenu(null);
                } else {
                    MenuItem inspect = new MenuItem("Show Commit");
                    inspect.setOnAction(event -> openCommit.accept(hash));
                    MenuItem copy = new MenuItem("Copy Commit Hash");
                    copy.setOnAction(event -> copyCommit.accept(hash));
                    MenuItem diff = new MenuItem("Compare Commit to HEAD");
                    diff.setOnAction(event -> compare.accept(hash));
                    MenuItem pick = new MenuItem("Cherry-pick Commit");
                    pick.setOnAction(event -> cherryPick.accept(hash));
                    MenuItem undo = new MenuItem("Revert Commit");
                    undo.setOnAction(event -> revert.accept(hash));
                    MenuItem createBranch = new MenuItem("Create Branch Here");
                    createBranch.setOnAction(event -> branchAt.accept(hash));
                    MenuItem createTag = new MenuItem("Create Tag Here");
                    createTag.setOnAction(event -> tagAt.accept(hash));
                    setContextMenu(new ContextMenu(inspect, copy, diff,
                            new SeparatorMenuItem(), pick, undo,
                            new SeparatorMenuItem(), createBranch, createTag));
                }
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) openSelected();
        });
        Button previous = new Button("Previous match");
        Button next = new Button("Next match");
        previous.setOnAction(event -> selectMatch(-1));
        next.setOnAction(event -> selectMatch(1));
        search.setOnAction(event -> selectMatch(1));
        list.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) openSelected();
            if (event.getCode() == javafx.scene.input.KeyCode.F
                    && event.isShortcutDown()) focusSearch();
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(search, new HBox(8, matches, previous, next), list);
    }

    public void setGraph(String graph) {
        width = 1;
        var parsed = graph.lines().map(GraphLine::parse).toList();
        for (GraphLine line : parsed) width = Math.max(width, line.lanes().length());
        lines.setAll(parsed);
        updateMatches();
    }

    public void focusSearch() {
        search.requestFocus();
    }

    private void selectMatch(int direction) {
        if (lines.isEmpty()) return;
        String needle = search.getText().strip().toLowerCase(Locale.ROOT);
        int current = list.getSelectionModel().getSelectedIndex();
        for (int offset = 1; offset <= lines.size(); offset++) {
            int index = Math.floorMod(current + direction * offset, lines.size());
            GraphLine line = lines.get(index);
            if (line.hash() != null && (needle.isEmpty()
                    || line.description().toLowerCase(Locale.ROOT).contains(needle))) {
                list.getSelectionModel().select(index);
                list.scrollTo(index);
                return;
            }
        }
    }

    private void openSelected() {
        GraphLine line = list.getSelectionModel().getSelectedItem();
        if (line != null && line.hash() != null) openCommit.accept(line.hash());
    }

    private void updateMatches() {
        String needle = search.getText().strip().toLowerCase(Locale.ROOT);
        long count = lines.stream().filter(line -> line.hash() != null
                && (needle.isEmpty() || line.description().toLowerCase(Locale.ROOT)
                        .contains(needle))).count();
        matches.setText(count + " matching commits (topology preserved)");
    }

    record GraphLine(String lanes, String description, String hash) {
        private static final java.util.regex.Pattern HASH =
                java.util.regex.Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{40,64}(?![0-9a-fA-F])");

        static GraphLine parse(String line) {
            int end = 0;
            while (end < line.length() && "*|/\\ _o:".indexOf(line.charAt(end)) >= 0) end++;
            String description = line.substring(end);
            var matcher = HASH.matcher(description);
            return new GraphLine(line.substring(0, end), description,
                    matcher.find() ? matcher.group() : null);
        }
    }
}
