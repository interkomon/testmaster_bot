package main.testmasterbot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TestResult {
    public String resultId;
    public long userId;
    public String userName;
    public int score;
    public int total;
    public LocalDateTime completedAt;
    public List<QuestionAnswerDetail> details = new ArrayList<>();

    @JsonIgnore
    public double getPercent() {
        return total == 0 ? 0.0 : (score * 100.0) / total;
    }

    @JsonIgnore
    public String getPercentText() {
        return String.format(Locale.US, "%.1f%%", getPercent());
    }

    @JsonIgnore
    public String getCompletedAtText() {
        return completedAt == null ? "-" : completedAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}