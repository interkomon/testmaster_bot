package main.testmasterbot.model;

import java.util.ArrayList;
import java.util.List;

public class Question {
    public String text;
    public QuestionType type;
    public List<String> options = new ArrayList<>();
    public Integer correctOptionIndex;
    public String correctTextAnswer;
    public Double correctNumberAnswer;
    public String photoFileId;
}
