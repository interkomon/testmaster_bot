package main.testmasterbot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

public class TestData {
    public String testId;
    public String accessCode;
    public long creatorId;
    public String creatorName;
    public String title;
    public String description;
    public PublicationStatus status;
    public Boolean showCorrectAnswerImmediately = true;
    public List<Question> questions = new ArrayList<>();
    public List<TestResult> results = new ArrayList<>();

    @JsonIgnore
    public boolean isPublicVisible() {
        return status == PublicationStatus.APPROVED;
    }

    @JsonIgnore
    public boolean isPrivate() {
        return status == PublicationStatus.PRIVATE;
    }

    @JsonIgnore
    public double averagePercent() {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (TestResult result : results) {
            sum += result.getPercent();
        }
        return sum / results.size();
    }
}