package main.testmasterbot.service;

import main.testmasterbot.model.*;
import main.testmasterbot.repository.JsonDataStore;
import main.testmasterbot.util.CodeGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BotService {
    private final JsonDataStore store;
    private final CodeGenerator codeGenerator = new CodeGenerator();

    public BotService(JsonDataStore store) {
        this.store = store;
        ensureSeedData();
    }

    public synchronized void bootstrapRolesFromEnv(String adminIds, String moderatorIds) {
        BotData data = store.load();


        applyRoleList(data, moderatorIds, Role.MODERATOR);

        //  админ - приоритет
        applyRoleList(data, adminIds, Role.ADMIN);

        store.save();
    }

    private void applyRoleList(BotData data, String rawIds, Role role) {
        if (rawIds == null || rawIds.isBlank()) {
            return;
        }

        String[] parts = rawIds.split(",");
        for (String part : parts) {
            try {
                long id = Long.parseLong(part.trim());

                Role existing = data.roles.get(id);


                if (existing == Role.ADMIN && role == Role.MODERATOR) {
                    continue;
                }

                data.roles.put(id, role);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized Role getRole(long userId) {
        return store.load().roles.getOrDefault(userId, Role.USER);
    }

    public synchronized boolean isModeratorOrAdmin(long userId) {
        Role role = getRole(userId);
        return role == Role.MODERATOR || role == Role.ADMIN;
    }

    public synchronized boolean isAdmin(long userId) {
        return getRole(userId) == Role.ADMIN;
    }

    public synchronized void setRole(long userId, Role role) {
        BotData data = store.load();
        data.roles.put(userId, role);
        store.save();
    }

    public synchronized void removeRole(long userId) {
        BotData data = store.load();
        data.roles.remove(userId);
        store.save();
    }

    public synchronized List<Map.Entry<Long, Role>> getAllRoles() {
        return store.load().roles.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .toList();
    }

    public synchronized TestData saveNewTest(TestData draft, long creatorId, String creatorName, boolean makePublic) {
        BotData data = store.load();
        draft.testId = codeGenerator.generateTestId(data);
        draft.accessCode = codeGenerator.generateAccessCode(data);
        draft.creatorId = creatorId;
        draft.creatorName = creatorName;

        if (draft.showCorrectAnswerImmediately == null) {
            draft.showCorrectAnswerImmediately = true;
        }

        if (makePublic) {
            draft.status = isModeratorOrAdmin(creatorId)
                    ? PublicationStatus.APPROVED
                    : PublicationStatus.PENDING_MODERATION;
        } else {
            draft.status = PublicationStatus.PRIVATE;
        }

        data.tests.put(draft.testId, draft);
        store.save();
        return draft;
    }

    public synchronized List<TestData> getUserTests(long userId) {
        return store.load().tests.values().stream()
                .filter(test -> test.creatorId == userId)
                .sorted(Comparator.comparing(test -> safeLower(test.title)))
                .toList();
    }

    public synchronized List<TestData> getPublishedTests() {
        return store.load().tests.values().stream()
                .filter(TestData::isPublicVisible)
                .sorted(Comparator.comparing(test -> safeLower(test.title)))
                .toList();
    }

    public synchronized List<TestData> getPendingTests() {
        return store.load().tests.values().stream()
                .filter(test -> test.status == PublicationStatus.PENDING_MODERATION)
                .sorted(Comparator.comparing(test -> safeLower(test.title)))
                .toList();
    }

    public synchronized TestData getTestById(String testId) {
        return store.load().tests.get(testId);
    }

    public synchronized TestData resolveTestByCode(long requesterId, String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (TestData test : store.load().tests.values()) {
            if (test.isPublicVisible() && normalized.equalsIgnoreCase(test.testId)) {
                return test;
            }
            if (normalized.equalsIgnoreCase(test.accessCode)) {
                return test;
            }
            if (test.creatorId == requesterId && normalized.equalsIgnoreCase(test.testId)) {
                return test;
            }
        }
        return null;
    }

    public synchronized void updateTestTitle(String testId, String title) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.title = title;
            store.save();
        }
    }

    public synchronized void updateTestDescription(String testId, String description) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.description = description;
            store.save();
        }
    }

    public synchronized void toggleTestAccess(String testId, long actorId) {
        TestData test = store.load().tests.get(testId);
        if (test == null) {
            return;
        }

        if (test.status == PublicationStatus.PRIVATE) {
            test.status = isModeratorOrAdmin(actorId)
                    ? PublicationStatus.APPROVED
                    : PublicationStatus.PENDING_MODERATION;
        } else {
            test.status = PublicationStatus.PRIVATE;
        }
        store.save();
    }

    public synchronized void sendToModeration(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.status = PublicationStatus.PENDING_MODERATION;
            store.save();
        }
    }

    public synchronized void approveTest(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.status = PublicationStatus.APPROVED;
            store.save();
        }
    }

    public synchronized void rejectTest(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.status = PublicationStatus.REJECTED;
            store.save();
        }
    }

    public synchronized void deleteTest(String testId) {
        store.load().tests.remove(testId);
        store.save();
    }

    public synchronized void toggleShowCorrectAnswerImmediately(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            boolean current = Boolean.TRUE.equals(test.showCorrectAnswerImmediately);
            test.showCorrectAnswerImmediately = !current;
            store.save();
        }
    }

    public synchronized TestResult appendResult(String testId, long userId, String userName, int score,
                                                int total, List<QuestionAnswerDetail> details) {
        TestData test = store.load().tests.get(testId);
        if (test == null) {
            return null;
        }
        TestResult result = new TestResult();
        result.resultId = codeGenerator.generateResultId();
        result.userId = userId;
        result.userName = userName;
        result.score = score;
        result.total = total;
        result.completedAt = LocalDateTime.now();
        result.details = new ArrayList<>(details);
        test.results.add(result);
        store.save();
        return result;
    }

    public synchronized List<TestResult> getResults(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test == null) {
            return List.of();
        }
        return test.results.stream()
                .sorted(Comparator.comparing((TestResult result) -> result.completedAt).reversed())
                .toList();
    }

    public synchronized TestResult getResultById(String testId, String resultId) {
        TestData test = store.load().tests.get(testId);
        if (test == null) {
            return null;
        }
        return test.results.stream()
                .filter(r -> r.resultId.equals(resultId))
                .findFirst()
                .orElse(null);
    }

    private void ensureSeedData() {
        BotData data = store.load();
        if (!data.tests.isEmpty()) {
            return;
        }

        TestData demo = new TestData();
        demo.testId = codeGenerator.generateTestId(data);
        demo.accessCode = codeGenerator.generateAccessCode(data);
        demo.creatorId = 0L;
        demo.creatorName = "System";
        demo.title = "Демо-тест по Java";
        demo.description = "Демонстрационный тест с разными типами вопросов.";
        demo.status = PublicationStatus.APPROVED;
        demo.showCorrectAnswerImmediately = true;
        demo.questions = new ArrayList<>();

        Question q1 = new Question();
        q1.text = "Какой тип данных используется для целых чисел в Java?";
        q1.type = QuestionType.SINGLE_CHOICE;
        q1.options = List.of("String", "int", "boolean", "double");
        q1.correctOptionIndex = 1;
        demo.questions.add(q1);

        Question q2 = new Question();
        q2.text = "Как называется точка входа в Java-программу?";
        q2.type = QuestionType.TEXT_INPUT;
        q2.correctTextAnswer = "main";
        demo.questions.add(q2);

        Question q3 = new Question();
        q3.text = "Сколько будет 2 + 2?";
        q3.type = QuestionType.NUMBER_INPUT;
        q3.correctNumberAnswer = 4.0;
        demo.questions.add(q3);

        data.tests.put(demo.testId, demo);
        store.save();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
