package main.testmasterbot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

public class TestData {
    public String testId;
    public long creatorId;
    public String creatorName;
    public String title;
    public String description;
    public PublicationStatus status;

    /** Старое поле оставлено для совместимости со старыми JSON-файлами. */
    public Boolean showCorrectAnswerImmediately = true;

    public AnswerRevealMode answerRevealMode = AnswerRevealMode.IMMEDIATE;
    public Integer totalTimeLimitSeconds;

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
    public AnswerRevealMode getEffectiveAnswerRevealMode() {
        if (answerRevealMode != null) {
            return answerRevealMode;
        }
        return Boolean.TRUE.equals(showCorrectAnswerImmediately) ? AnswerRevealMode.IMMEDIATE : AnswerRevealMode.END_ONLY;
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
