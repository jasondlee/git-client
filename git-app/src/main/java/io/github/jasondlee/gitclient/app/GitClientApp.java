package io.github.jasondlee.gitclient.app;
import io.github.jasondlee.gitclient.ui.WorkspaceView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
public final class GitClientApp extends Application {
    @Override public void start(Stage stage) {
        stage.setTitle("Git Client — Repository Explorer");
        stage.setScene(new Scene(new WorkspaceView(), 1100, 700));
        stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
