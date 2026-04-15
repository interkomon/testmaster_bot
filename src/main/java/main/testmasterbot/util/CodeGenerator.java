package main.testmasterbot.util;

import main.testmasterbot.model.BotData;
import main.testmasterbot.model.TestData;

import java.security.SecureRandom;

public class CodeGenerator {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    public String generateTestId(BotData data) {
        return uniqueCode(data, "TST", 8);
    }

    public String generateAccessCode(BotData data) {
        return uniqueCode(data, "ACC", 10);
    }

    public String generateResultId() {
        return "RES" + randomString(8);
    }

    private String uniqueCode(BotData data, String prefix, int randomLength) {
        while (true) {
            String code = prefix + randomString(randomLength);
            boolean exists = false;
            for (TestData test : data.tests.values()) {
                if (code.equalsIgnoreCase(test.testId) || code.equalsIgnoreCase(test.accessCode)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                return code;
            }
        }
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
