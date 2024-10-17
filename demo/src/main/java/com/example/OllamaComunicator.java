package com.example;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import org.json.JSONObject;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Label;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Base64;
import java.util.ArrayList;
import java.util.stream.Collectors;

enum ResponmseType {
    Stream,
    Complete
}

enum MessageRole {
    user,
    assistant
}

class Message implements Serializable {
    private MessageRole role;
    private String content;
    private String[] images;

    public Message(MessageRole role, String content, String[] images) {
        this.role = role;
        content = content.replaceAll("\\\\", "\\\\\\\\");
        content = content.replaceAll("\n", "\\n");
        content = content.replaceAll("\"", "\\\\\""); // turn " into \"
        this.content = content;
        this.images = images;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String[] getImages() {
        return images;
    }

    public String toJson() {
        return "{\"role\": \"" + role + "\", \"content\": \"" + content + "\" "+ (images.length > 0 ? ",\"images\": [" + String.join(",", images) + "] " : "") + "}";
    }   
}

class ChatHistory {
    private ArrayList<Message> messages = new ArrayList<>();

    public void addMessage(Message message) {
        messages.add(message);
    }

    public void removeMessage(Message message) {
        messages.remove(message);
    }

    public void removeMessage(int index) {
        messages.remove(index);
    }

    public void removeLastMessage() {
        messages.remove(messages.size() - 1);
    }

    public Message getLastMessage() {
        return messages.get(messages.size() - 1);
    }

    public void clear() {
        messages.clear();
    }

    public boolean HasImages() {
        return messages.stream().anyMatch(message -> message.getImages().length > 0);
    }

    public String toJson() {
        return messages.stream().map(Message::toJson).collect(Collectors.joining(","));
    }
}

public class OllamaComunicator {

    private static final String TextModel = "llama3.2:1b";
    private static final String ImageModel = "qnguyen3/nanollava";

    private static OllamaComunicator instance;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private CompletableFuture<HttpResponse<String>> completeRequest;
    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private AtomicBoolean isCancelled = new AtomicBoolean(false);
    private InputStream currentInputStream;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;
    private boolean isFirst = true;
    private ChatHistory chatHistory = new ChatHistory();


    private OllamaComunicator() {
        //Open ollama
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ollama serve");
        try {
            pb.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static OllamaComunicator getInstance() {
        if (instance == null) {
            instance = new OllamaComunicator();
        }
        return instance;
    }

    public void RegenerateLastMessage(Label LastTextInfo, Runnable setRunning, Runnable setIdle, ResponmseType type) {
        chatHistory.removeLastMessage();//Remove the last from the assistant
        String lastMessageContent = chatHistory.getLastMessage().getContent();
        String[] lastMessageImages = chatHistory.getLastMessage().getImages();
        chatHistory.removeLastMessage();//Remove the last from the user
        //resend the last message
        sendMessage(lastMessageContent, lastMessageImages, LastTextInfo, setRunning, setIdle, type);
    }

    public void sendMessage(String message, String[] images, Label textInfo, Runnable setRunning, Runnable setIdle, ResponmseType type) {
        Message userMessage = new Message(MessageRole.user, message, images);
        if (type == ResponmseType.Stream) {
            callStream(userMessage, textInfo, setRunning, setIdle);
        } 
        else {
            callComplete(userMessage, textInfo, setRunning, setIdle);
        }
    }

    public void sendMessage(String message, ArrayList<File> images, Label textInfo, Runnable setRunning, Runnable setIdle, ResponmseType type) {
        //Transform images to base64
        String[] imagesBase64 = new String[images.size()];
        for (int i = 0; i < images.size(); i++) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                try (FileInputStream fis = new FileInputStream(images.get(i))) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imagesBase64[i] = "\"" + Base64.getEncoder().encodeToString(baos.toByteArray()) + "\"";
                }
            } 
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        sendMessage(message, imagesBase64, textInfo, setRunning, setIdle, type);
    }

    private void callStream(Message message, Label textInfo, Runnable setRunning, Runnable setIdle) {
        textInfo.setText(""); // Clear the textInfo
        setRunning.run();
        isCancelled.set(false);
        chatHistory.addMessage(message);
        //System.out.println(chatHistory.toJson());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/chat"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"model\": \"" + (chatHistory.HasImages() ? ImageModel : TextModel) + "\",\"messages\": [" + chatHistory.toJson() + "], " + "\"stream\": true}"))
                .build();
        System.out.println("{\"model\": \"" + (chatHistory.HasImages() ? ImageModel : TextModel) + "\",\"messages\": [" + chatHistory.toJson() + "], " + "\"stream\": true}");
        Platform.runLater(() -> textInfo.setText("Wait stream ..."));
        isFirst = true;
        streamRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    System.out.println("Stream response");
                    currentInputStream = response.body();
                    streamReadingTask = executorService.submit(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentInputStream))) {
                            String line;
                            String fullText = "";
                            while ((line = reader.readLine()) != null) {
                                if (isCancelled.get()) {
                                    System.out.println("Stream cancelled");
                                    chatHistory.addMessage(new Message(MessageRole.assistant, fullText + "User cancelled the stream", new String[0]));
                                    break;
                                }
                                JSONObject jsonResponse = new JSONObject(line);
                                System.out.println(jsonResponse.toString());
                                JSONObject messageJson = jsonResponse.getJSONObject("message");
                                String responseText = messageJson.getString("content");
                                fullText += responseText;
                                if (isFirst) {
                                    Platform.runLater(() -> textInfo.setText(responseText));
                                    isFirst = false;
                                } else {
                                    Platform.runLater(() -> textInfo.setText(textInfo.getText() + responseText));
                                }
                            }
                            chatHistory.addMessage(new Message(MessageRole.assistant, fullText, new String[0]));
                        } catch (Exception e) {
                            e.printStackTrace();
                            Platform.runLater(() -> {
                                textInfo.setText("Error during streaming.");
                                setIdle.run();
                            });
                        } finally {
                            try {
                                if (currentInputStream != null) {
                                    System.out.println("Cancelling InputStream in finally");
                                    currentInputStream.close();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            Platform.runLater(() -> setIdle.run());
                        }
                    });
                    return response;
                })
                .exceptionally(e -> {
                    if (!isCancelled.get()) {
                        e.printStackTrace();
                    }
                    Platform.runLater(() -> setIdle.run());
                    return null;
                });
    }

    private void callComplete(Message message, Label textInfo, Runnable setRunning, Runnable setIdle) {
        textInfo.setText(""); // Clear the textInfo
        setRunning.run();
        isCancelled.set(false);
        chatHistory.addMessage(message);
        System.out.println(chatHistory.toJson());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/chat"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"model\": \"" + (chatHistory.HasImages() ? ImageModel : TextModel) + "\",\"messages\": [" + chatHistory.toJson() + "], " + "\"stream\": false}"))
                .build();

        Platform.runLater(() -> textInfo.setText("Wait complete ..."));
        isFirst = true;
        streamRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    currentInputStream = response.body();
                    streamReadingTask = executorService.submit(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentInputStream))) {
                            String line;
                            String fullText = "";
                            while ((line = reader.readLine()) != null) {
                                if (isCancelled.get()) {
                                    System.out.println("Complete cancelled");
                                    break;
                                }
                                JSONObject jsonResponse = new JSONObject(line);
                                JSONObject messageJson = jsonResponse.getJSONObject("message");
                                String responseText = messageJson.getString("content");
                                fullText += responseText;
                            }
                            final String responseText = fullText;
                            Platform.runLater(() -> textInfo.setText(responseText));
                            chatHistory.addMessage(new Message(MessageRole.assistant, responseText, new String[0]));
                        } catch (Exception e) {
                            e.printStackTrace();
                            Platform.runLater(() -> {
                                textInfo.setText("Error during complete.");
                                setIdle.run();
                            });
                        } finally {
                            try {
                                if (currentInputStream != null) {
                                    currentInputStream.close();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            Platform.runLater(() -> setIdle.run());
                        }
                    });
                    return response;
                })
                .exceptionally(e -> {
                    if (!isCancelled.get()) {
                        e.printStackTrace();
                    }
                    Platform.runLater(() -> setIdle.run());
                    return null;
                });
    }

    public void cancelRequest(Label textInfo, Runnable setIdle) {
        isCancelled.set(true);
        cancelStreamRequest();
        cancelCompleteRequest();
        Platform.runLater(() -> {
            textInfo.setText("Request cancelled.");
            setIdle.run();
        });
    }
    
    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            try {
                if (currentInputStream != null) {
                    System.out.println("Cancelling InputStream");
                    currentInputStream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("Cancelling StreamRequest");
            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }
            streamRequest.cancel(true);
        }
    }

    private void cancelCompleteRequest() {
        if (completeRequest != null && !completeRequest.isDone()) {
            System.out.println("Cancelling CompleteRequest");
            completeRequest.cancel(true);
        }
    }

    public void ClearChatHistory() {
        chatHistory.clear();
    }
}
