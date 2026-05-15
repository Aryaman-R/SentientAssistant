package com.sentient.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProfileManager {

    private static final String PROFILE_PATH = "user_profile.json";
    private static ProfileManager instance;
    private UserProfile userProfile;
    private final Gson gson;

    private ProfileManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadProfile();
    }

    public static synchronized ProfileManager getInstance() {
        if (instance == null) {
            instance = new ProfileManager();
        }
        return instance;
    }

    public void loadProfile() {
        try (Reader reader = new FileReader(PROFILE_PATH)) {
            // Read raw JSON first for migration check
            String raw = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(PROFILE_PATH)));
            userProfile = gson.fromJson(raw, UserProfile.class);
            if (userProfile == null) {
                userProfile = new UserProfile();
            }

            // Migration: if old flat "tasks" array exists and taskLists is empty, migrate
            JsonObject rawJson = JsonParser.parseString(raw).getAsJsonObject();
            if (rawJson.has("tasks") && rawJson.get("tasks").isJsonArray()
                    && (userProfile.taskLists == null || userProfile.taskLists.isEmpty())) {
                JsonArray oldTasks = rawJson.getAsJsonArray("tasks");
                if (oldTasks.size() > 0) {
                    TaskList defaultList = new TaskList();
                    defaultList.name = "My Tasks";
                    for (JsonElement elem : oldTasks) {
                        JsonObject t = elem.getAsJsonObject();
                        TaskItem item = new TaskItem();
                        item.title = t.has("title") ? t.get("title").getAsString() : "";
                        item.description = t.has("description") ? t.get("description").getAsString() : "";
                        item.dueDate = t.has("dueDate") ? t.get("dueDate").getAsString() : "";
                        if (!item.title.isEmpty()) {
                            defaultList.items.add(item);
                        }
                    }
                    if (userProfile.taskLists == null)
                        userProfile.taskLists = new ArrayList<>();
                    userProfile.taskLists.add(defaultList);
                    System.out.println(
                            "[ProfileManager] Migrated " + defaultList.items.size() + " tasks to 'My Tasks' list.");
                    saveProfile();
                }
            }

            // Ensure at least one task list exists
            if (userProfile.taskLists == null)
                userProfile.taskLists = new ArrayList<>();
            if (userProfile.taskLists.isEmpty()) {
                TaskList defaultList = new TaskList();
                defaultList.name = "My Tasks";
                userProfile.taskLists.add(defaultList);
            }

        } catch (IOException e) {
            System.err.println("Error loading profile: " + e.getMessage());
            userProfile = new UserProfile();
            saveProfile();
        } catch (Exception e) {
            System.err.println("Error parsing profile: " + e.getMessage());
            userProfile = new UserProfile();
            saveProfile();
        }
    }

    public void saveProfile() {
        try (Writer writer = new FileWriter(PROFILE_PATH)) {
            gson.toJson(userProfile, writer);
        } catch (IOException e) {
            System.err.println("Error saving profile: " + e.getMessage());
        }
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    // ── Setters (mutate + auto-save) ────────────────────

    public void setUsername(String name) {
        userProfile.username = name;
        saveProfile();
    }

    public void addHabit(String habit) {
        String lower = habit.toLowerCase().trim();
        if (!userProfile.habits.stream().anyMatch(h -> h.equalsIgnoreCase(lower))) {
            userProfile.habits.add(habit.trim());
            saveProfile();
        }
    }

    public void removeHabit(String habit) {
        userProfile.habits.removeIf(h -> h.equalsIgnoreCase(habit.trim()));
        saveProfile();
    }

    public void addPreference(String preference) {
        String trimmed = preference.trim();
        if (!userProfile.preferences.stream().anyMatch(p -> p.equalsIgnoreCase(trimmed))) {
            userProfile.preferences.add(trimmed);
            saveProfile();
        }
    }

    public void removePreference(String preference) {
        userProfile.preferences.removeIf(p -> p.equalsIgnoreCase(preference.trim()));
        saveProfile();
    }

    public void addDislike(String dislike) {
        String trimmed = dislike.trim();
        if (!trimmed.isEmpty() && userProfile.dislikes.stream().noneMatch(d -> d.equalsIgnoreCase(trimmed))) {
            userProfile.dislikes.add(trimmed);
            saveProfile();
        }
    }

    public void removeDislike(String dislike) {
        userProfile.dislikes.removeIf(d -> d.equalsIgnoreCase(dislike.trim()));
        saveProfile();
    }

    public void addGoal(String goal) {
        String trimmed = goal.trim();
        if (!trimmed.isEmpty() && userProfile.goals.stream().noneMatch(g -> g.equalsIgnoreCase(trimmed))) {
            userProfile.goals.add(trimmed);
            saveProfile();
        }
    }

    public void removeGoal(String goal) {
        userProfile.goals.removeIf(g -> g.equalsIgnoreCase(goal.trim()));
        saveProfile();
    }

    public void addNickname(String nickname) {
        String trimmed = nickname.trim();
        if (!trimmed.isEmpty() && userProfile.nicknames.stream().noneMatch(n -> n.equalsIgnoreCase(trimmed))) {
            userProfile.nicknames.add(trimmed);
            saveProfile();
        }
    }

    public void removeNickname(String nickname) {
        userProfile.nicknames.removeIf(n -> n.equalsIgnoreCase(nickname.trim()));
        saveProfile();
    }

    public void addNote(String note) {
        String trimmed = note.trim();
        if (!trimmed.isEmpty() && userProfile.notes.stream().noneMatch(n -> n.equalsIgnoreCase(trimmed))) {
            userProfile.notes.add(trimmed);
            saveProfile();
        }
    }

    public void removeNote(String note) {
        userProfile.notes.removeIf(n -> n.equalsIgnoreCase(note.trim()));
        saveProfile();
    }

    public void setRestrictionMode(boolean enabled) {
        userProfile.restriction_mode = enabled;
        saveProfile();
    }

    // ── Task List helpers ────────────────────────────────

    /** Get all task lists. */
    public List<TaskList> getTaskLists() {
        return userProfile.taskLists;
    }

    /** Create a new named task list. */
    public void addTaskList(String name) {
        String trimmed = name.trim();
        if (!trimmed.isEmpty() && userProfile.taskLists.stream().noneMatch(tl -> tl.name.equalsIgnoreCase(trimmed))) {
            TaskList list = new TaskList();
            list.name = trimmed;
            userProfile.taskLists.add(list);
            saveProfile();
        }
    }

    /** Delete a task list by name. */
    public void removeTaskList(String name) {
        userProfile.taskLists.removeIf(tl -> tl.name.equalsIgnoreCase(name.trim()));
        saveProfile();
    }

    /** Add a task to a specific list. */
    public void addTaskToList(String listName, String title, String description, String dueDate) {
        String trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty())
            return;
        for (TaskList tl : userProfile.taskLists) {
            if (tl.name.equalsIgnoreCase(listName.trim())) {
                if (tl.items.stream().noneMatch(t -> t.title.equalsIgnoreCase(trimmedTitle))) {
                    TaskItem item = new TaskItem();
                    item.title = trimmedTitle;
                    item.description = description != null ? description.trim() : "";
                    item.dueDate = dueDate != null ? dueDate.trim() : "";
                    tl.items.add(item);
                    saveProfile();
                }
                return;
            }
        }
    }

    /** Remove a task from a specific list. */
    public void removeTaskFromList(String listName, String title) {
        for (TaskList tl : userProfile.taskLists) {
            if (tl.name.equalsIgnoreCase(listName.trim())) {
                tl.items.removeIf(t -> t.title.equalsIgnoreCase(title.trim()));
                saveProfile();
                return;
            }
        }
    }

    // ── Legacy task helpers (backward compat for AI commands) ────

    /** Add task to the first/default list. */
    public void addTask(String title, String description, String dueDate) {
        if (userProfile.taskLists.isEmpty()) {
            TaskList defaultList = new TaskList();
            defaultList.name = "My Tasks";
            userProfile.taskLists.add(defaultList);
        }
        addTaskToList(userProfile.taskLists.get(0).name, title, description, dueDate);
    }

    /** Remove task from any list. */
    public void removeTask(String title) {
        for (TaskList tl : userProfile.taskLists) {
            tl.items.removeIf(t -> t.title.equalsIgnoreCase(title.trim()));
        }
        saveProfile();
    }

    /** Find a TaskItem by list name + title. Returns null if not found. */
    public TaskItem findTask(String listName, String title) {
        for (TaskList tl : userProfile.taskLists) {
            if (tl.name.equalsIgnoreCase(listName == null ? "" : listName.trim())) {
                for (TaskItem t : tl.items) {
                    if (t.title.equalsIgnoreCase(title == null ? "" : title.trim())) return t;
                }
            }
        }
        return null;
    }

    /** Find a TaskItem by title across every list (legacy AI commands). */
    public TaskItem findTaskAnywhere(String title) {
        for (TaskList tl : userProfile.taskLists) {
            for (TaskItem t : tl.items) {
                if (t.title.equalsIgnoreCase(title == null ? "" : title.trim())) return t;
            }
        }
        return null;
    }

    /** Return the name of the list that contains this task, or null. */
    public String listNameForTask(TaskItem item) {
        for (TaskList tl : userProfile.taskLists) {
            if (tl.items.contains(item)) return tl.name;
        }
        return null;
    }

    /** Flat list of all tasks (for AI context). */
    public List<TaskItem> getAllTasks() {
        List<TaskItem> all = new ArrayList<>();
        for (TaskList tl : userProfile.taskLists) {
            all.addAll(tl.items);
        }
        return all;
    }

    // ── Commitment / Alarm helpers ────────────────────────

    public void addCommitment(String commitment) {
        String trimmed = commitment.trim();
        if (!trimmed.isEmpty() && !userProfile.commitments.stream().anyMatch(c -> c.equalsIgnoreCase(trimmed))) {
            userProfile.commitments.add(trimmed);
            saveProfile();
        }
    }

    public void removeCommitment(String commitment) {
        userProfile.commitments.removeIf(c -> c.equalsIgnoreCase(commitment.trim()));
        saveProfile();
    }

    public void addAlarm(String time) {
        String trimmed = time.trim();
        if (!trimmed.isEmpty() && !userProfile.alarms.contains(trimmed)) {
            userProfile.alarms.add(trimmed);
            saveProfile();
        }
    }

    public void removeAlarm(String time) {
        userProfile.alarms.removeIf(a -> a.equalsIgnoreCase(time.trim()));
        saveProfile();
    }

    // ── Event helpers ────────────────────────────────────

    public void addEvent(EventItem event) {
        if (event.id == null || event.id.isEmpty()) {
            event.id = UUID.randomUUID().toString();
        }
        userProfile.events.add(event);
        saveProfile();
    }

    public void removeEvent(String id) {
        userProfile.events.removeIf(e -> e.id.equals(id));
        saveProfile();
    }

    // ── Data classes ─────────────────────────────────────

    public static class TaskItem {
        public String title = "";
        public String description = "";
        public String dueDate = "";
        /** Google Tasks ID if this task has been synced to Google. Empty if local-only. */
        public String googleId = "";
        /** Google Tasks list ID where this task lives. Empty if local-only. */
        public String googleListId = "";
        /** Marked completed in Google. Local UI may choose to hide these. */
        public boolean completed = false;
    }

    public static class TaskList {
        public String name = "";
        public List<TaskItem> items = new ArrayList<>();
    }

    public static class EventItem {
        public String id = "";
        public String title = "";
        public String description = "";
        public String start = "";
        public String end = "";
        public boolean allDay = false;
    }

    // Inner class for the JSON structure
    public static class UserProfile {
        public String username = "Owner";
        public List<String> habits = new ArrayList<>();
        public List<String> preferences = new ArrayList<>();
        public List<String> dislikes = new ArrayList<>();
        public List<String> goals = new ArrayList<>();
        public List<String> nicknames = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        public boolean restriction_mode = false;
        public List<String> past_conversations_summary = new ArrayList<>();
        public List<TaskList> taskLists = new ArrayList<>();
        public List<String> commitments = new ArrayList<>();
        public List<String> alarms = new ArrayList<>();
        public List<EventItem> events = new ArrayList<>();
    }
}
