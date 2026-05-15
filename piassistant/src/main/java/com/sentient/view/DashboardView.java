package com.sentient.view;

import com.sentient.service.CameraService;
import com.sentient.service.GroqService;
import com.sentient.service.Listener;
import com.sentient.service.TextToSpeech;
import com.sentient.util.ProfileManager;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DashboardView {

    private final BorderPane root;

    // Views
    private VBox homeView;
    private VBox studyView;
    private VBox sleepView;
    private HBox tasksView;

    // Home view elements
    private TextArea chatArea;
    private TextField inputField;

    // Study view elements
    private Label timerLabel;
    private TextField timerEditField;
    private StackPane timerContainer;
    private ProgressBar progressBar;
    private Label quoteLabel;
    private Label sessionLabel;
    private Button playPauseBtn;
    private Button cancelBtn;

    // Timer state
    private long totalSeconds = 25 * 60; // default 25 minutes
    private long remainingSeconds = totalSeconds;
    private boolean timerRunning = false;
    private boolean timerStarted = false; // distinguishes "paused" from "never started"
    private AnimationTimer countdownTimer;
    private long lastTickNanos = 0;
    private int sessionCount = 0;
    private final Random random = new Random();

    // Camera
    private ImageView cameraFeed;

    // Services
    private final GroqService groq;
    private final CameraService cameraService;
    private Listener listener;
    private ScheduledExecutorService cameraThread;

    // Voice UI
    private Button recordBtn;

    // Response control — for stop/interrupt
    private volatile boolean responseActive = false;
    private volatile Thread activeStreamThread;
    private volatile Thread activeAudioThread;

    // Tasks UI
    private VBox taskListContainer;
    private VBox commitmentListContainer;
    private VBox studyTaskContainer;
    private final Set<String> sessionSelectedTasks = new HashSet<>();

    // Quotes
    private static final String[] FOCUS_QUOTES = {
            "\"The secret of getting ahead is getting started.\" — Mark Twain",
            "\"Focus is the art of knowing what to ignore.\" — James Clear",
            "\"Deep work is the superpower of the 21st century.\" — Cal Newport",
            "\"Silence is the language of God, all else is poor translation.\" — Rumi",
            "\"Where focus goes, energy flows.\" — Tony Robbins",
            "\"Do what you can, with what you have, where you are.\" — Theodore Roosevelt",
            "\"The mind is everything. What you think you become.\" — Buddha",
            "\"Discipline is the bridge between goals and accomplishment.\" — Jim Rohn",
            "\"You will never reach your destination if you stop and throw stones at every dog that barks.\" — Winston Churchill",
            "\"Concentrate all your thoughts upon the work at hand.\" — Alexander Graham Bell",
            "\"It is during our darkest moments that we must focus to see the light.\" — Aristotle",
            "\"The successful warrior is the average man, with laser-like focus.\" — Bruce Lee"
    };

    public DashboardView() {
        this.groq = new GroqService();
        this.cameraService = new CameraService();

        this.root = new BorderPane();
        root.getStyleClass().add("root-pane");

        root.setLeft(buildSidebar());
        root.setCenter(buildContentArea());

        // Initialize
        showHome();
        startCameraFeed();
        initListener();

        String user = ProfileManager.getInstance().getUserProfile().username;
        chatArea.appendText("System: Welcome back, " + user + "\n\n");
    }

    /** Initialize the voice listener with UI callbacks. */
    private void initListener() {
        try {
            listener = new Listener();
            listener.setCallback(new Listener.ListenerCallback() {
                @Override
                public void onPartialResult(String text) {
                    Platform.runLater(() -> inputField.setText(text));
                }

                @Override
                public void onFinalResult(String text) {
                    Platform.runLater(() -> {
                        inputField.setText(text);
                        handleSendMessage();
                    });
                }

                @Override
                public void onListeningStateChanged(boolean listening) {
                    Platform.runLater(() -> {
                        if (listening) {
                            recordBtn.setText("⏹ STOP");
                            recordBtn.getStyleClass().add("recording");
                        } else {
                            recordBtn.setText("🎤 RECORD");
                            recordBtn.getStyleClass().remove("recording");
                        }
                    });
                }
            });
            listener.start();
            System.out.println("[DashboardView] Listener started.");
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) because Vosk native loading
            // may throw UnsatisfiedLinkError which extends Error, not Exception
            System.err.println("[DashboardView] Could not start Listener: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public BorderPane getRoot() {
        return root;
    }

    // ── Sidebar ─────────────────────────────────────────

    private VBox buildSidebar() {
        VBox sidebar = new VBox(20);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(20, 10, 20, 10));

        Label brand = new Label("SENTIENT");
        brand.getStyleClass().add("brand-label");

        Button homeBtn = createNavButton("HOME", e -> showHome());
        Button studyBtn = createNavButton("STUDY", e -> showStudy());
        Button tasksBtn = createNavButton("TASKS", e -> showTasks());
        Button sleepBtn = createNavButton("SLEEP", e -> showSleep());

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // Camera feed container
        VBox cameraContainer = new VBox();
        cameraContainer.getStyleClass().add("camera-container");

        Label visionLabel = new Label("VISION");
        visionLabel.getStyleClass().add("section-header");

        cameraFeed = new ImageView();
        cameraFeed.setFitWidth(120);
        cameraFeed.setFitHeight(90);
        cameraFeed.setPreserveRatio(true);

        cameraContainer.getChildren().addAll(visionLabel, cameraFeed);

        sidebar.getChildren().addAll(brand, homeBtn, studyBtn, tasksBtn, sleepBtn, spacer, cameraContainer);
        return sidebar;
    }

    private Button createNavButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button btn = new Button(text);
        btn.setMnemonicParsing(false);
        btn.getStyleClass().add("nav-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(handler);
        return btn;
    }

    // ── Content Area ────────────────────────────────────

    private StackPane buildContentArea() {
        StackPane contentArea = new StackPane();

        homeView = buildHomeView();
        studyView = buildStudyView();
        tasksView = buildTasksView();
        sleepView = buildSleepView();

        contentArea.getChildren().addAll(homeView, studyView, tasksView, sleepView);
        return contentArea;
    }

    private VBox buildHomeView() {
        VBox view = new VBox(10);
        view.getStyleClass().add("content-view");
        view.setPadding(new Insets(20));

        Label header = new Label("TERMINAL LINK");
        header.getStyleClass().add("view-header");

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.getStyleClass().add("console-output");
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        inputField = new TextField();
        inputField.setPromptText("Enter command...");
        inputField.getStyleClass().add("console-input");
        inputField.setOnAction(e -> handleSendMessage());
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button sendBtn = new Button("SEND");
        sendBtn.getStyleClass().add("action-button");
        sendBtn.setOnAction(e -> handleSendMessage());

        recordBtn = new Button("🎤 RECORD");
        recordBtn.getStyleClass().add("action-button");
        recordBtn.setOnAction(e -> {
            if (listener != null) {
                listener.startListening();
            }
        });

        Button stopBtn = new Button("⏹ STOP");
        stopBtn.getStyleClass().addAll("action-button", "cancel-button");
        stopBtn.setOnAction(e -> stopResponse());

        HBox inputRow = new HBox(10, inputField, recordBtn, stopBtn, sendBtn);

        view.getChildren().addAll(header, chatArea, inputRow);
        return view;
    }

    private VBox buildStudyView() {
        VBox view = new VBox(15);
        view.getStyleClass().add("content-view");
        view.getStyleClass().add("study-view");
        view.setAlignment(Pos.CENTER);
        view.setVisible(false);

        // ── Header ──
        Label header = new Label("FOCUS MODE");
        header.getStyleClass().add("view-header");

        // ── Quote (above timer) ──
        quoteLabel = new Label(getRandomQuote());
        quoteLabel.setWrapText(true);
        quoteLabel.getStyleClass().add("quote-label");
        quoteLabel.setMaxWidth(500);

        // ── Timer Display (StackPane with label + edit field) ──
        timerLabel = new Label(formatTime(remainingSeconds));
        timerLabel.getStyleClass().add("huge-timer");

        timerEditField = new TextField();
        timerEditField.getStyleClass().add("timer-edit-field");
        timerEditField.setVisible(false);
        timerEditField.setMaxWidth(280);
        timerEditField.setAlignment(Pos.CENTER);

        timerContainer = new StackPane(timerLabel, timerEditField);
        timerContainer.setMaxWidth(400);

        // Double-click to edit
        timerLabel.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !timerRunning) {
                enterEditMode();
            }
        });

        // Confirm edit on Enter
        timerEditField.setOnAction(e -> confirmEdit());

        // Cancel edit on focus lost
        timerEditField.focusedProperty().addListener((obs, wasFocused, isNow) -> {
            if (!isNow && timerEditField.isVisible()) {
                confirmEdit();
            }
        });

        // ── Status indicator ──
        Label statusHint = new Label("DOUBLE-CLICK TIMER TO EDIT");
        statusHint.getStyleClass().add("status-hint");

        // ── Progress Bar ──
        progressBar = new ProgressBar(1.0);
        progressBar.getStyleClass().add("focus-progress");
        progressBar.setMaxWidth(400);
        progressBar.setPrefHeight(8);

        // ── Control Buttons ──
        playPauseBtn = new Button("▶  START");
        playPauseBtn.getStyleClass().add("timer-button");
        playPauseBtn.getStyleClass().add("play-button");
        playPauseBtn.setOnAction(e -> togglePlayPause());

        cancelBtn = new Button("■  CANCEL");
        cancelBtn.getStyleClass().add("timer-button");
        cancelBtn.getStyleClass().add("cancel-button");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> cancelTimer());

        HBox controls = new HBox(15, playPauseBtn, cancelBtn);
        controls.setAlignment(Pos.CENTER);

        // ── Session counter ──
        sessionLabel = new Label("SESSIONS COMPLETED: 0");
        sessionLabel.getStyleClass().add("session-label");

        view.getChildren().addAll(header, quoteLabel, timerContainer, statusHint, progressBar, controls, sessionLabel,
                buildStudyTaskPanel());
        return view;
    }

    /** Build a task selection panel for the study page. */
    private VBox buildStudyTaskPanel() {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("study-task-panel");
        panel.setPadding(new Insets(12));

        Label panelHeader = new Label("SESSION TASKS");
        panelHeader.getStyleClass().add("panel-header");

        studyTaskContainer = new VBox(4);
        ScrollPane scroll = new ScrollPane(studyTaskContainer);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("task-scroll");
        scroll.setMaxHeight(200);

        panel.getChildren().addAll(panelHeader, scroll);
        VBox.setVgrow(panel, Priority.NEVER);
        return panel;
    }

    /** Refresh the study page task checkboxes. */
    private void refreshStudyTasks() {
        if (studyTaskContainer == null)
            return;
        studyTaskContainer.getChildren().clear();
        List<ProfileManager.TaskItem> tasks = ProfileManager.getInstance().getAllTasks();
        if (tasks.isEmpty()) {
            Label empty = new Label("No tasks. Add tasks in the TASKS tab.");
            empty.getStyleClass().add("status-hint");
            studyTaskContainer.getChildren().add(empty);
            return;
        }
        for (ProfileManager.TaskItem task : tasks) {
            CheckBox cb = new CheckBox(task.title + (task.dueDate.isEmpty() ? "" : "  📅 " + task.dueDate));
            cb.getStyleClass().add("study-task-checkbox");
            cb.setSelected(sessionSelectedTasks.contains(task.title));
            cb.selectedProperty().addListener((obs, wasSelected, isNow) -> {
                if (isNow) {
                    sessionSelectedTasks.add(task.title);
                } else {
                    sessionSelectedTasks.remove(task.title);
                }
            });
            studyTaskContainer.getChildren().add(cb);
        }
    }

    // ── Tasks View ──────────────────────────────────────

    private HBox buildTasksView() {
        HBox view = new HBox(15);
        view.getStyleClass().add("content-view");
        view.setPadding(new Insets(20));
        view.setVisible(false);

        // ── Left column: Tasks ──
        VBox tasksColumn = new VBox(10);
        tasksColumn.getStyleClass().add("task-column");
        HBox.setHgrow(tasksColumn, Priority.ALWAYS);

        Label tasksHeader = new Label("TASKS");
        tasksHeader.getStyleClass().add("panel-header");

        // Add task form
        TextField taskTitleField = new TextField();
        taskTitleField.setPromptText("Task title...");
        taskTitleField.getStyleClass().add("console-input");

        TextField taskDescField = new TextField();
        taskDescField.setPromptText("Description (optional)...");
        taskDescField.getStyleClass().add("console-input");

        TextField taskDateField = new TextField();
        taskDateField.setPromptText("Due date YYYY-MM-DD (optional)...");
        taskDateField.getStyleClass().add("console-input");

        Button addTaskBtn = new Button("+ ADD TASK");
        addTaskBtn.getStyleClass().addAll("timer-button", "play-button");

        Label dateError = new Label();
        dateError.getStyleClass().add("date-error");
        dateError.setVisible(false);

        addTaskBtn.setOnAction(e -> {
            String title = taskTitleField.getText().trim();
            String dateText = taskDateField.getText().trim();

            // Validate date if provided
            if (!dateText.isEmpty()) {
                try {
                    LocalDate.parse(dateText); // strict YYYY-MM-DD
                    dateError.setVisible(false);
                } catch (DateTimeParseException ex) {
                    dateError.setText("Invalid date. Use YYYY-MM-DD format.");
                    dateError.setVisible(true);
                    return;
                }
            } else {
                dateError.setVisible(false);
            }

            if (!title.isEmpty()) {
                ProfileManager.getInstance().addTask(title, taskDescField.getText(), dateText);
                taskTitleField.clear();
                taskDescField.clear();
                taskDateField.clear();
                refreshTaskList();
                refreshStudyTasks();
            }
        });

        HBox addForm = new HBox(8, taskTitleField, taskDescField, taskDateField, addTaskBtn);
        HBox.setHgrow(taskTitleField, Priority.ALWAYS);
        HBox.setHgrow(taskDescField, Priority.SOMETIMES);

        taskListContainer = new VBox(6);
        ScrollPane taskScroll = new ScrollPane(taskListContainer);
        taskScroll.setFitToWidth(true);
        taskScroll.getStyleClass().add("task-scroll");
        VBox.setVgrow(taskScroll, Priority.ALWAYS);

        tasksColumn.getChildren().addAll(tasksHeader, addForm, dateError, taskScroll);

        // ── Right column: Commitments ──
        VBox commitmentsColumn = new VBox(10);
        commitmentsColumn.getStyleClass().add("task-column");
        commitmentsColumn.setMinWidth(250);
        commitmentsColumn.setPrefWidth(300);

        Label commitmentsHeader = new Label("COMMITMENTS");
        commitmentsHeader.getStyleClass().add("panel-header");

        TextField commitmentField = new TextField();
        commitmentField.setPromptText("Add commitment...");
        commitmentField.getStyleClass().add("console-input");

        Button addCommitBtn = new Button("+ ADD");
        addCommitBtn.getStyleClass().addAll("timer-button", "play-button");
        addCommitBtn.setOnAction(e -> {
            String text = commitmentField.getText().trim();
            if (!text.isEmpty()) {
                ProfileManager.getInstance().addCommitment(text);
                commitmentField.clear();
                refreshCommitmentList();
            }
        });

        HBox commitForm = new HBox(8, commitmentField, addCommitBtn);
        HBox.setHgrow(commitmentField, Priority.ALWAYS);

        commitmentListContainer = new VBox(6);
        ScrollPane commitScroll = new ScrollPane(commitmentListContainer);
        commitScroll.setFitToWidth(true);
        commitScroll.getStyleClass().add("task-scroll");
        VBox.setVgrow(commitScroll, Priority.ALWAYS);

        commitmentsColumn.getChildren().addAll(commitmentsHeader, commitForm, commitScroll);

        view.getChildren().addAll(tasksColumn, commitmentsColumn);

        // Initial load
        Platform.runLater(() -> {
            refreshTaskList();
            refreshCommitmentList();
        });

        return view;
    }

    private void refreshTaskList() {
        taskListContainer.getChildren().clear();
        List<ProfileManager.TaskItem> tasks = ProfileManager.getInstance().getAllTasks();
        if (tasks.isEmpty()) {
            Label empty = new Label("No tasks yet. Add one above!");
            empty.getStyleClass().add("status-hint");
            taskListContainer.getChildren().add(empty);
            return;
        }
        for (ProfileManager.TaskItem task : tasks) {
            VBox itemBox = new VBox(2);
            itemBox.getStyleClass().add("task-item");

            Label titleLabel = new Label(task.title);
            titleLabel.getStyleClass().add("task-title");

            HBox topRow = new HBox(8);
            topRow.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button removeBtn = new Button("✕");
            removeBtn.getStyleClass().add("remove-btn");
            removeBtn.setOnAction(e -> {
                ProfileManager.getInstance().removeTask(task.title);
                refreshTaskList();
                refreshStudyTasks();
            });

            topRow.getChildren().addAll(titleLabel, spacer, removeBtn);
            itemBox.getChildren().add(topRow);

            if (!task.description.isEmpty()) {
                Label descLabel = new Label(task.description);
                descLabel.getStyleClass().add("task-description");
                descLabel.setWrapText(true);
                itemBox.getChildren().add(descLabel);
            }
            if (!task.dueDate.isEmpty()) {
                Label dateLabel = new Label("📅 " + task.dueDate);
                dateLabel.getStyleClass().add("task-date");
                itemBox.getChildren().add(dateLabel);
            }

            taskListContainer.getChildren().add(itemBox);
        }
    }

    private void refreshCommitmentList() {
        commitmentListContainer.getChildren().clear();
        List<String> commitments = ProfileManager.getInstance().getUserProfile().commitments;
        if (commitments.isEmpty()) {
            Label empty = new Label("No commitments yet.");
            empty.getStyleClass().add("status-hint");
            commitmentListContainer.getChildren().add(empty);
            return;
        }
        for (String commitment : commitments) {
            HBox itemBox = new HBox(8);
            itemBox.getStyleClass().add("task-item");
            itemBox.setAlignment(Pos.CENTER_LEFT);

            Label label = new Label(commitment);
            label.getStyleClass().add("commitment-label");
            label.setWrapText(true);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button removeBtn = new Button("✕");
            removeBtn.getStyleClass().add("remove-btn");
            removeBtn.setOnAction(e -> {
                ProfileManager.getInstance().removeCommitment(commitment);
                refreshCommitmentList();
            });

            itemBox.getChildren().addAll(label, spacer, removeBtn);
            commitmentListContainer.getChildren().add(itemBox);
        }
    }

    private VBox buildSleepView() {
        VBox view = new VBox(20);
        view.getStyleClass().add("content-view");
        view.setAlignment(Pos.CENTER);
        view.setVisible(false);

        Label header = new Label("SYSTEM STANDBY");
        header.getStyleClass().add("view-header");

        Label clock = new Label("03:42 AM"); // Dynamic clock to be implemented
        clock.getStyleClass().add("huge-clock");

        Button wakeBtn = new Button("WAKE SYSTEM");
        wakeBtn.getStyleClass().add("wake-button");
        wakeBtn.setOnAction(e -> showHome());

        view.getChildren().addAll(header, clock, wakeBtn);
        return view;
    }

    // ── Timer Logic ────────────────────────────────────

    private void enterEditMode() {
        timerLabel.setVisible(false);
        timerEditField.setVisible(true);
        long mins = remainingSeconds / 60;
        long secs = remainingSeconds % 60;
        timerEditField.setText(String.format("%02d:%02d", mins, secs));
        timerEditField.requestFocus();
        timerEditField.selectAll();
    }

    private void confirmEdit() {
        String text = timerEditField.getText().trim();
        timerEditField.setVisible(false);
        timerLabel.setVisible(true);

        // Parse MM:SS or just a number (treated as minutes)
        try {
            if (text.contains(":")) {
                String[] parts = text.split(":");
                long mins = Long.parseLong(parts[0]);
                long secs = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                totalSeconds = mins * 60 + secs;
            } else {
                totalSeconds = Long.parseLong(text) * 60;
            }
            if (totalSeconds <= 0)
                totalSeconds = 25 * 60;
        } catch (NumberFormatException ex) {
            // Keep previous value
        }

        remainingSeconds = totalSeconds;
        timerLabel.setText(formatTime(remainingSeconds));
        progressBar.setProgress(1.0);
    }

    private void togglePlayPause() {
        if (timerRunning) {
            // Pause
            pauseTimer();
        } else {
            // Start / Resume
            startTimer();
        }
    }

    private void startTimer() {
        if (!timerStarted) {
            // Fresh start — show a new quote
            animateQuote();
        }

        timerRunning = true;
        timerStarted = true;
        cancelBtn.setDisable(false);
        playPauseBtn.setText("⏸  PAUSE");
        playPauseBtn.getStyleClass().remove("play-button");
        if (!playPauseBtn.getStyleClass().contains("pause-button")) {
            playPauseBtn.getStyleClass().add("pause-button");
        }

        lastTickNanos = System.nanoTime();

        countdownTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long elapsed = now - lastTickNanos;
                if (elapsed >= 1_000_000_000L) { // 1 second
                    lastTickNanos = now;
                    remainingSeconds--;

                    if (remainingSeconds <= 0) {
                        remainingSeconds = 0;
                        timerLabel.setText(formatTime(0));
                        progressBar.setProgress(0);
                        timerComplete();
                        stop();
                        return;
                    }

                    timerLabel.setText(formatTime(remainingSeconds));
                    double progress = (double) remainingSeconds / totalSeconds;
                    progressBar.setProgress(progress);
                }
            }
        };
        countdownTimer.start();
    }

    private void pauseTimer() {
        timerRunning = false;
        if (countdownTimer != null) {
            countdownTimer.stop();
        }
        playPauseBtn.setText("▶  RESUME");
        playPauseBtn.getStyleClass().remove("pause-button");
        if (!playPauseBtn.getStyleClass().contains("play-button")) {
            playPauseBtn.getStyleClass().add("play-button");
        }
    }

    private void cancelTimer() {
        timerRunning = false;
        timerStarted = false;
        if (countdownTimer != null) {
            countdownTimer.stop();
        }
        remainingSeconds = totalSeconds;
        timerLabel.setText(formatTime(remainingSeconds));
        progressBar.setProgress(1.0);
        cancelBtn.setDisable(true);
        playPauseBtn.setText("▶  START");
        playPauseBtn.getStyleClass().remove("pause-button");
        if (!playPauseBtn.getStyleClass().contains("play-button")) {
            playPauseBtn.getStyleClass().add("play-button");
        }
    }

    private void timerComplete() {
        timerRunning = false;
        timerStarted = false;
        sessionCount++;
        sessionLabel.setText("SESSIONS COMPLETED: " + sessionCount);
        cancelBtn.setDisable(true);

        // Reset for next session
        remainingSeconds = totalSeconds;
        timerLabel.setText("DONE!");
        timerLabel.getStyleClass().add("timer-done");

        playPauseBtn.setText("▶  START");
        playPauseBtn.getStyleClass().remove("pause-button");
        if (!playPauseBtn.getStyleClass().contains("play-button")) {
            playPauseBtn.getStyleClass().add("play-button");
        }

        // After 2 seconds reset the display
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(() -> {
                timerLabel.setText(formatTime(remainingSeconds));
                timerLabel.getStyleClass().remove("timer-done");
                progressBar.setProgress(1.0);
                animateQuote();
            });
        }).start();
    }

    private void animateQuote() {
        quoteLabel.setText(getRandomQuote());
        FadeTransition fade = new FadeTransition(javafx.util.Duration.millis(600), quoteLabel);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private String getRandomQuote() {
        return FOCUS_QUOTES[random.nextInt(FOCUS_QUOTES.length)];
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    /**
     * Programmatically set the timer duration in minutes.
     * Resets the timer to the new value (must not be running).
     */
    public void setTimerMinutes(int minutes) {
        if (timerRunning) {
            cancelTimer();
        }
        totalSeconds = minutes * 60L;
        remainingSeconds = totalSeconds;
        timerLabel.setText(formatTime(remainingSeconds));
        progressBar.setProgress(1.0);
    }

    // ── Navigation ──────────────────────────────────────

    private void showHome() {
        homeView.setVisible(true);
        studyView.setVisible(false);
        tasksView.setVisible(false);
        sleepView.setVisible(false);
    }

    private void showStudy() {
        homeView.setVisible(false);
        studyView.setVisible(true);
        tasksView.setVisible(false);
        sleepView.setVisible(false);
        refreshStudyTasks();
    }

    private void showTasks() {
        homeView.setVisible(false);
        studyView.setVisible(false);
        tasksView.setVisible(true);
        sleepView.setVisible(false);
        refreshTaskList();
        refreshCommitmentList();
    }

    private void showSleep() {
        homeView.setVisible(false);
        studyView.setVisible(false);
        tasksView.setVisible(false);
        sleepView.setVisible(true);
    }

    // ── Event Handlers ──────────────────────────────────

    private static final Pattern CMD_PATTERN = Pattern.compile("\\[CMD:(\\w+)(?::([^\\]]+))?\\]");

    private void handleSendMessage() {
        String text = inputField.getText();
        if (text == null || text.trim().isEmpty())
            return;

        chatArea.appendText("You: " + text + "\n\n");
        inputField.clear();

        groq.processCommand(text).thenAccept(response -> {
            List<String[]> commands = extractCommands(response);
            String cleanResponse = CMD_PATTERN.matcher(response).replaceAll("").trim()
                    .replaceAll("\\s{2,}", " ").trim();
            final String finalResponse = cleanResponse;

            // Check if AI wants to continue conversation
            boolean shouldContinue = commands.stream()
                    .anyMatch(cmd -> "CONTINUE_CONVERSATION".equals(cmd[0]));

            Platform.runLater(() -> executeCommands(commands));

            // Mark response as active (for stop/interrupt)
            responseActive = true;

            // Generate audio first to get duration (on this async thread)
            TextToSpeech tts = new TextToSpeech();
            TextToSpeech.AudioResult audioResult = tts.generateAudio(finalResponse);

            // Calculate per-word delay to sync text with audio
            String[] words = finalResponse.split("\\s+");
            long msPerWord = 50;
            if (audioResult != null && words.length > 0) {
                msPerWord = Math.max(20, Math.min(200, audioResult.durationMs / words.length));
            }
            final long delay = msPerWord;

            // Stream text word-by-word
            Thread streamThread = new Thread(() -> {
                Platform.runLater(() -> chatArea.appendText("Assistant: "));
                for (String word : words) {
                    if (!responseActive)
                        break;
                    final String w = word;
                    Platform.runLater(() -> chatArea.appendText(w + " "));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                Platform.runLater(() -> chatArea.appendText("\n\n"));
            });
            streamThread.setDaemon(true);
            activeStreamThread = streamThread;
            streamThread.start();

            // Play audio simultaneously, then trigger mic if continuing
            if (audioResult != null) {
                Thread audioThread = new Thread(() -> {
                    if (listener != null)
                        listener.setTtsSpeaking(true);
                    try {
                        tts.playFile(audioResult.file);
                    } finally {
                        if (listener != null)
                            listener.setTtsSpeaking(false);
                    }
                    boolean wasStopped = !responseActive; // true if stopResponse() was called
                    responseActive = false;
                    if (shouldContinue && !wasStopped && listener != null) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                        System.out.println("[DashboardView] AI wants to continue conversation — activating mic");
                        listener.startListening();
                    }
                });
                audioThread.setDaemon(true);
                activeAudioThread = audioThread;
                audioThread.start();
            } else {
                responseActive = false;
                if (shouldContinue && listener != null) {
                    // No audio — still continue conversation after a short delay
                    Thread contThread = new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        System.out.println("[DashboardView] AI wants to continue conversation — activating mic");
                        listener.startListening();
                    });
                    contThread.setDaemon(true);
                    contThread.start();
                }
            }
        });
    }

    /**
     * Stop the current AI response — halts TTS playback and text streaming.
     * Called by the STOP button or by voice interruption.
     */
    private void stopResponse() {
        responseActive = false;
        TextToSpeech.stopPlayback();
        Thread st = activeStreamThread;
        if (st != null)
            st.interrupt();
        if (listener != null)
            listener.cancelTranscription();
        System.out.println("[DashboardView] Response stopped.");
    }

    /**
     * Extract [CMD:ACTION] or [CMD:ACTION:PARAM] tags from Gemini response.
     * Returns a list of String[] where [0]=action, [1]=param (or null).
     */
    private List<String[]> extractCommands(String response) {
        List<String[]> commands = new ArrayList<>();
        Matcher matcher = CMD_PATTERN.matcher(response);
        while (matcher.find()) {
            String action = matcher.group(1);
            String param = matcher.group(2); // may be null
            commands.add(new String[] { action, param });
        }
        return commands;
    }

    /**
     * Execute a list of parsed commands in order.
     */
    private void executeCommands(List<String[]> commands) {
        for (String[] cmd : commands) {
            String action = cmd[0];
            String param = cmd[1];

            switch (action) {
                case "SWITCH_STUDY":
                    showStudy();
                    chatArea.appendText("System: ⚡ Switched to Focus Mode\n\n");
                    break;
                case "SWITCH_HOME":
                    showHome();
                    chatArea.appendText("System: ⚡ Switched to Home\n\n");
                    break;
                case "SWITCH_SLEEP":
                    showSleep();
                    chatArea.appendText("System: ⚡ Switched to Sleep Mode\n\n");
                    break;
                case "SWITCH_TASKS":
                    showTasks();
                    chatArea.appendText("System: ⚡ Switched to Tasks\n\n");
                    break;
                case "SET_TIMER":
                    if (param != null) {
                        try {
                            int minutes = Integer.parseInt(param);
                            setTimerMinutes(minutes);
                            chatArea.appendText("System: ⏱ Timer set to " + minutes + " minutes\n\n");
                        } catch (NumberFormatException e) {
                            chatArea.appendText("System: ⚠ Could not parse timer value: " + param + "\n\n");
                        }
                    }
                    break;
                case "START_TIMER":
                    if (!timerRunning) {
                        startTimer();
                        chatArea.appendText("System: ▶ Timer started\n\n");
                    }
                    break;
                case "PAUSE_TIMER":
                    if (timerRunning) {
                        pauseTimer();
                        chatArea.appendText("System: ⏸ Timer paused\n\n");
                    }
                    break;
                case "CANCEL_TIMER":
                    cancelTimer();
                    chatArea.appendText("System: ■ Timer cancelled\n\n");
                    break;
                case "SET_USERNAME":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().setUsername(param.trim());
                        chatArea.appendText("System: 👤 Username changed to \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_HABIT":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addHabit(param.trim());
                        chatArea.appendText("System: ✚ Habit added: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_HABIT":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeHabit(param.trim());
                        chatArea.appendText("System: ✖ Habit removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_PREFERENCE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addPreference(param.trim());
                        chatArea.appendText("System: ✚ Preference added: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_PREFERENCE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removePreference(param.trim());
                        chatArea.appendText("System: ✖ Preference removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_DISLIKE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addDislike(param.trim());
                        chatArea.appendText("System: ✚ Dislike added: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_DISLIKE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeDislike(param.trim());
                        chatArea.appendText("System: ✖ Dislike removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_GOAL":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addGoal(param.trim());
                        chatArea.appendText("System: 🎯 Goal added: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_GOAL":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeGoal(param.trim());
                        chatArea.appendText("System: ✖ Goal removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_NICKNAME":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addNickname(param.trim());
                        chatArea.appendText("System: 👤 Nickname added: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_NICKNAME":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeNickname(param.trim());
                        chatArea.appendText("System: ✖ Nickname removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "ADD_NOTE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addNote(param.trim());
                        chatArea.appendText("System: 📝 Note saved: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "REMOVE_NOTE":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeNote(param.trim());
                        chatArea.appendText("System: ✖ Note removed: \"" + param.trim() + "\"\n\n");
                    }
                    break;
                case "SET_RESTRICTION":
                    if (param != null) {
                        boolean enabled = param.trim().equalsIgnoreCase("ON");
                        ProfileManager.getInstance().setRestrictionMode(enabled);
                        chatArea.appendText(
                                "System: 🔒 Restriction mode " + (enabled ? "ENABLED" : "DISABLED") + "\n\n");
                    }
                    break;
                case "ADD_TASK":
                    if (param != null && !param.trim().isEmpty()) {
                        // Parse title|description|date format
                        String[] parts = param.split("\\|", 3);
                        String title = parts[0].trim();
                        String desc = parts.length > 1 ? parts[1].trim() : "";
                        String date = parts.length > 2 ? parts[2].trim() : "";
                        // Validate date if provided
                        if (!date.isEmpty()) {
                            try {
                                LocalDate.parse(date);
                            } catch (DateTimeParseException ex) {
                                chatArea.appendText("System: ⚠ Invalid date format: " + date + "\n\n");
                                date = "";
                            }
                        }
                        ProfileManager.getInstance().addTask(title, desc, date);
                        chatArea.appendText("System: ✚ Task added: \"" + title + "\"\n\n");
                        refreshTaskList();
                        refreshStudyTasks();
                    }
                    break;
                case "REMOVE_TASK":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeTask(param.trim());
                        chatArea.appendText("System: ✔ Task completed: \"" + param.trim() + "\"\n\n");
                        refreshTaskList();
                        refreshStudyTasks();
                    }
                    break;
                case "ADD_COMMITMENT":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addCommitment(param.trim());
                        chatArea.appendText("System: ✚ Commitment added: \"" + param.trim() + "\"\n\n");
                        refreshCommitmentList();
                    }
                    break;
                case "REMOVE_COMMITMENT":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeCommitment(param.trim());
                        chatArea.appendText("System: ✖ Commitment removed: \"" + param.trim() + "\"\n\n");
                        refreshCommitmentList();
                    }
                    break;
                case "SET_ALARM":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().addAlarm(param.trim());
                        chatArea.appendText("System: ⏰ Alarm set for " + param.trim() + "\n\n");
                    }
                    break;
                case "DELETE_ALARM":
                    if (param != null && !param.trim().isEmpty()) {
                        ProfileManager.getInstance().removeAlarm(param.trim());
                        chatArea.appendText("System: ⏰ Alarm deleted: " + param.trim() + "\n\n");
                    }
                    break;
                case "CONTINUE_CONVERSATION":
                    // Handled in handleSendMessage — no UI action needed here
                    break;
                default:
                    chatArea.appendText("System: ⚠ Unknown command: " + action + "\n\n");
                    break;
            }
        }
    }

    // ── Camera ──────────────────────────────────────────

    private void startCameraFeed() {
        cameraThread = Executors.newSingleThreadScheduledExecutor();
        cameraThread.scheduleAtFixedRate(() -> {
            javafx.scene.image.Image frame = cameraService.getLatestFrame();
            if (frame != null) {
                Platform.runLater(() -> cameraFeed.setImage(frame));
            }
        }, 0, 33, TimeUnit.MILLISECONDS); // ~30 FPS
    }

    public void shutdown() {
        if (countdownTimer != null)
            countdownTimer.stop();
        if (cameraThread != null)
            cameraThread.shutdown();
        cameraService.stop();
        if (listener != null)
            listener.stop();
    }
}
