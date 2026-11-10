package io.github.jasondlee.gitclient.app;

import io.github.jasondlee.gitclient.ui.WorkspaceView;
import javafx.application.Application;
import javafx.scene.Scene;
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
        repositories.getTabs().addListener(
                (javafx.collections.ListChangeListener<Tab>) change -> {
                    if (repositories.getTabs().isEmpty()) addRepositoryTab(null);
                });
        if (getParameters().getRaw().isEmpty()) {
            addRepositoryTab(null);
        } else {
            for (String argument : getParameters().getRaw()) {
                addRepositoryTab(Path.of(argument));
            }
        }
        stage.setScene(new Scene(layout, 1100, 700));
        stage.show();
    }

    private void addRepositoryTab(Path path) {
        WorkspaceView workspace = new WorkspaceView();
        Tab tab = new Tab(path == null ? "New Repository" : path.getFileName().toString(),
                workspace);
        tab.setClosable(true);
        repositories.getTabs().add(tab);
        repositories.getSelectionModel().select(tab);
        if (path != null) workspace.openPath(path);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
