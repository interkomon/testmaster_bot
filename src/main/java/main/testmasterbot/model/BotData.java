package main.testmasterbot.model;

import java.util.HashMap;
import java.util.Map;

public class BotData {
    public Map<String, TestData> tests = new HashMap<>();
    public Map<Long, Role> roles = new HashMap<>();
}
