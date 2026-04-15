package main.testmasterbot.state;

import main.testmasterbot.model.Question;
import main.testmasterbot.model.TestData;

import java.util.ArrayList;

public class UserSession {
    public SessionState state = SessionState.IDLE;
    public TestData draftTest;
    public Question draftQuestion;
    public int expectedOptionsCount;
    public int currentOptionIndex;
    public String selectedTestId;

    public void resetCurrentQuestion() {
        draftQuestion = new Question();
        draftQuestion.options = new ArrayList<>();
        expectedOptionsCount = 0;
        currentOptionIndex = 0;
    }

    public void prepareNewTest() {
        draftTest = new TestData();
        draftTest.questions = new ArrayList<>();
        draftTest.showCorrectAnswerImmediately = true;
        resetCurrentQuestion();
    }
}
