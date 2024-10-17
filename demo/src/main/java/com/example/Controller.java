package com.example;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.io.File;
import javafx.scene.control.Label;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.control.ChoiceBox;
import javafx.stage.FileChooser;
import java.util.ArrayList;
import javafx.geometry.Insets;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;


class ImageFile {
    private String name;
    private File file;
    private Controller controller;

    public ImageFile(String name, File file, Controller controller) {
        this.name = name;
        this.file = file;
        this.controller = controller;
    }

    public Node getNode() {
        HBox container = new HBox();
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(5));
        container.setSpacing(5);
        container.getStyleClass().add("image-file");
        
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("image-file-name");
        container.getChildren().add(nameLabel);

        ImageView imageView = new ImageView(new Image(file.toURI().toString()));
        imageView.setFitWidth(20);
        imageView.setFitHeight(20);
        imageView.setPreserveRatio(true);
        imageView.getStyleClass().add("image-file-image");

        Button deleteButton = new Button();
        ImageView deleteImage = new ImageView(new Image(getClass().getResource("delete.png").toExternalForm()));
        deleteImage.setFitWidth(20);
        deleteImage.setFitHeight(20);
        deleteImage.setPreserveRatio(true);
        deleteButton.setGraphic(deleteImage);
        deleteButton.getStyleClass().add("image-file-delete");
        deleteButton.setOnAction(event -> {
            controller.removeImage(file, this);
        });
        container.getChildren().add(deleteButton);
        return container;
    }
}

public class Controller implements Initializable {
    @FXML
    private VBox ChatBox;
    @FXML
    private Button LoadFileButton;
    @FXML
    private TextField UserMessage;
    @FXML
    private Button SendButton;
    @FXML
    private Button RegenerateButton;
    @FXML
    private Button ClearButton;
    @FXML
    private ChoiceBox<String> ResponseType;
    @FXML
    private HBox FilesBox;


    private Label LastLLMResponse;

    private ArrayList<File> images = new ArrayList<>();
    private ResponmseType responmseType = ResponmseType.Stream;
    private OllamaComunicator ollamaComunicator;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        //Set Images to buttons

        RegenerateButton.setGraphic(getBackgroundImage("regenerate.png"));
        LoadFileButton.setGraphic(getBackgroundImage("loadFiles.png"));
        ClearButton.setGraphic(getBackgroundImage("clear.png"));
        SendButton.setGraphic(getBackgroundImage("send.png"));

        ollamaComunicator = OllamaComunicator.getInstance();
        UserMessage.setOnKeyPressed(event -> {
            if (event.getCode().equals(KeyCode.ENTER)) {
                sendMessage();
            }
        });
        SetIdle();

        //Set ChoiceBox
        ResponseType.getItems().addAll("Stream", "Complete");
        ResponseType.setValue("Stream");
        ResponseType.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            responmseType = ResponmseType.values()[newValue.intValue()];
        });


    }   

    public void removeImage(File file, ImageFile imageFile) {
        images.remove(file);
        FilesBox.getChildren().remove(imageFile);
    }

    private Node getBackgroundImage(String path) {
        ImageView imageView = new ImageView(new Image(getClass().getResource(path).toExternalForm()));
        imageView.setFitWidth(20);
        imageView.setFitHeight(20);
        imageView.setPreserveRatio(true);
        return imageView;
    }

    public void loadImages() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Images");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Images", "*.jpg", "*.png", "*.gif", "*.bmp", "*.tiff"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = fileChooser.showOpenMultipleDialog(null);
        if (files != null) {
            images.addAll(files);
            for (File file : files) {
                ImageFile imageFile = new ImageFile(file.getName(), file, this);
                FilesBox.getChildren().add(imageFile.getNode());
            }
        }
    }

    public void sendMessage() {
        String message = UserMessage.getText();
        UserMessage.clear();
        Label userMessage = new Label(message);
        userMessage.setWrapText(true);
        userMessage.getStyleClass().addAll("user-message", "message");
        userMessage.setMinHeight(Label.USE_PREF_SIZE);
        VBox userContainer = new VBox(userMessage);
        userContainer.getStyleClass().add("user-container");
        ChatBox.getChildren().add(userContainer);
        Label LLMResponse = new Label();
        LLMResponse.setWrapText(true);
        LLMResponse.getStyleClass().addAll("llm-response", "message");
        LLMResponse.setMinHeight(Label.USE_PREF_SIZE);
        VBox LLMContainer = new VBox(LLMResponse);
        LLMContainer.getStyleClass().add("llm-container");
        ChatBox.getChildren().add(LLMContainer);
        if (LastLLMResponse != null && LastLLMResponse.getText().equals("Request cancelled"))
            ChatBox.getChildren().remove(LastLLMResponse);
        LastLLMResponse = LLMResponse;
        ollamaComunicator.sendMessage(message, images, LLMResponse, this::SetRunning, this::SetIdle, responmseType);
        ChatBox.getChildren().add(LLMResponse);
        images.clear();
        FilesBox.getChildren().clear();
    }

    public void regenerateLastMessage() {
        ollamaComunicator.RegenerateLastMessage(LastLLMResponse, this::SetRunning, this::SetIdle, responmseType);
    }

    public void ClearChatHistory() {
        ollamaComunicator.ClearChatHistory();
        ChatBox.getChildren().clear();
        images.clear();
        FilesBox.getChildren().clear();
    }

    public void SetIdle() {
        LoadFileButton.setDisable(false);
        SendButton.setGraphic(getBackgroundImage("send.png"));
        RegenerateButton.setDisable(false);
        ClearButton.setDisable(false);
        SendButton.setOnAction(event -> sendMessage());
        //On Enter
        UserMessage.setEditable(true);
    }

    public void SetRunning() {
        LoadFileButton.setDisable(true);
        RegenerateButton.setDisable(true);
        ClearButton.setDisable(true);
        SendButton.setGraphic(getBackgroundImage("stop.png"));
        SendButton.setOnAction(event -> ollamaComunicator.cancelRequest(LastLLMResponse, this::SetIdle));
        UserMessage.setEditable(false);
    }
}
