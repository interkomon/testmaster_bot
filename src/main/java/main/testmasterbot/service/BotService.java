package main.testmasterbot.service;

import main.testmasterbot.model.*;
import main.testmasterbot.repository.DataStore;
import main.testmasterbot.util.CodeGenerator;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

public class BotService {
    private final DataStore store;
    private final CodeGenerator codeGenerator = new CodeGenerator();

    public BotService(DataStore store) {
        this.store = store;
        ensureSeedData();
    }

    public synchronized void bootstrapRolesFromEnv(String adminIds, String moderatorIds) {
        BotData data = store.load();
        applyRoleList(data, moderatorIds, Role.MODERATOR);
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

    public synchronized void rememberUser(User user) {
        if (user == null) {
            return;
        }
        BotData data = store.load();
        KnownUser knownUser = data.knownUsers.getOrDefault(user.getId(), new KnownUser());
        knownUser.userId = user.getId();
        knownUser.username = user.getUserName();
        knownUser.firstName = user.getFirstName();
        knownUser.lastName = user.getLastName();
        data.knownUsers.put(knownUser.userId, knownUser);
        store.save();
    }

    public synchronized KnownUser getKnownUser(long userId) {
        return store.load().knownUsers.get(userId);
    }

    public synchronized List<KnownUser> getKnownUsers() {
        return store.load().knownUsers.values().stream()
                .sorted(Comparator.comparing(user -> displayUser(user.userId).toLowerCase(Locale.ROOT)))
                .toList();
    }

    public synchronized String displayUser(long userId) {
        KnownUser user = store.load().knownUsers.get(userId);
        if (user == null) {
            return String.valueOf(userId);
        }
        return user.displayName();
    }

    public synchronized Long resolveKnownUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("@")) {
            String username = value.substring(1).toLowerCase(Locale.ROOT);
            for (KnownUser user : store.load().knownUsers.values()) {
                if (user.username != null && user.username.equalsIgnoreCase(username)) {
                    return user.userId;
                }
            }
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
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
                .sorted(Comparator.comparing(entry -> displayUser(entry.getKey()).toLowerCase(Locale.ROOT)))
                .toList();
    }

    public synchronized boolean isCreationBlocked(long userId) {
        UserRestriction restriction = store.load().restrictions.get(userId);
        if (restriction == null || !restriction.creationBlocked) {
            return false;
        }
        if (restriction.blockedUntil == null) {
            return true;
        }
        if (restriction.blockedUntil.isAfter(LocalDateTime.now())) {
            return true;
        }
        store.load().restrictions.remove(userId);
        store.save();
        return false;
    }

    public synchronized UserRestriction getRestriction(long userId) {
        return store.load().restrictions.get(userId);
    }

    public synchronized void blockCreation(long userId, int days, String reason) {
        BotData data = store.load();
        UserRestriction restriction = new UserRestriction();
        restriction.creationBlocked = true;
        restriction.blockedUntil = LocalDateTime.now().plusDays(Math.max(1, days));
        restriction.reason = reason;
        data.restrictions.put(userId, restriction);
        store.save();
    }

    public synchronized void blockCreationForever(long userId, String reason) {
        BotData data = store.load();
        UserRestriction restriction = new UserRestriction();
        restriction.creationBlocked = true;
        restriction.blockedUntil = null;
        restriction.reason = reason == null || reason.isBlank() ? "Блокировка без срока" : reason;
        data.restrictions.put(userId, restriction);
        store.save();
    }

    public synchronized void unblockCreation(long userId) {
        BotData data = store.load();
        data.restrictions.remove(userId);
        store.save();
    }

    public synchronized List<Map.Entry<Long, UserRestriction>> getRestrictions() {
        return store.load().restrictions.entrySet().stream()
                .sorted(Comparator.comparing(entry -> displayUser(entry.getKey()).toLowerCase(Locale.ROOT)))
                .toList();
    }

    public synchronized TestData saveNewTest(TestData draft, long creatorId, String creatorName, boolean makePublic) {
        BotData data = store.load();
        draft.testId = codeGenerator.generateTestId(data);
        draft.creatorId = creatorId;
        draft.creatorName = creatorName;

        if (draft.answerRevealMode == null) {
            draft.answerRevealMode = Boolean.TRUE.equals(draft.showCorrectAnswerImmediately)
                    ? AnswerRevealMode.IMMEDIATE
                    : AnswerRevealMode.END_ONLY;
        }
        draft.showCorrectAnswerImmediately = draft.answerRevealMode == AnswerRevealMode.IMMEDIATE;

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

    public synchronized List<TestData> getAllTests() {
        return store.load().tests.values().stream()
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
            if (!normalized.equalsIgnoreCase(test.testId)) {
                continue;
            }

            // Один код запуска = TestId.
            // Опубликованные и приватные тесты можно пройти по коду запуска.
            if (test.status == PublicationStatus.APPROVED || test.status == PublicationStatus.PRIVATE) {
                return test;
            }

            // На модерации/отклонённые тесты доступны только автору, модератору или администратору.
            if (test.creatorId == requesterId || isModeratorOrAdmin(requesterId)) {
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

    public synchronized void updateAnswerRevealMode(String testId, AnswerRevealMode mode) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.answerRevealMode = mode;
            test.showCorrectAnswerImmediately = mode == AnswerRevealMode.IMMEDIATE;
            store.save();
        }
    }

    public synchronized void updateTotalTimeLimit(String testId, Integer seconds) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.totalTimeLimitSeconds = seconds;
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

    public synchronized TestData approveTest(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.status = PublicationStatus.APPROVED;
            store.save();
        }
        return test;
    }

    public synchronized TestData rejectTest(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            test.status = PublicationStatus.REJECTED;
            store.save();
        }
        return test;
    }

    public synchronized void deleteTest(String testId) {
        store.load().tests.remove(testId);
        store.save();
    }

    /** Старый метод оставлен для совместимости с уже имеющимися кнопками. */
    public synchronized void toggleShowCorrectAnswerImmediately(String testId) {
        TestData test = store.load().tests.get(testId);
        if (test != null) {
            AnswerRevealMode current = test.getEffectiveAnswerRevealMode();
            test.answerRevealMode = current == AnswerRevealMode.IMMEDIATE ? AnswerRevealMode.END_ONLY : AnswerRevealMode.IMMEDIATE;
            test.showCorrectAnswerImmediately = test.answerRevealMode == AnswerRevealMode.IMMEDIATE;
            store.save();
        }
    }

    public synchronized TestResult appendResult(String testId, long userId, String userName, int score,
                                                int total, List<QuestionAnswerDetail> details) {
        return appendResult(testId, userId, userName, score, total, details, false, "Завершён");
    }

    public synchronized TestResult appendResult(String testId, long userId, String userName, int score,
                                                int total, List<QuestionAnswerDetail> details,
                                                boolean aborted, String finishReason) {
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
        result.aborted = aborted;
        result.finishReason = finishReason;
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

    public synchronized int getTotalResultsCount() {
        int count = 0;
        for (TestData test : store.load().tests.values()) {
            count += test.results.size();
        }
        return count;
    }

    public synchronized String buildStatisticsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("test_id;title;creator;status;questions;attempts;finished;aborted;avg_percent\n");
        for (TestData test : getAllTests()) {
            long aborted = test.results.stream().filter(result -> result.aborted).count();
            long finished = test.results.size() - aborted;
            sb.append(test.testId).append(';')
                    .append(escapeCsv(test.title)).append(';')
                    .append(escapeCsv(displayUser(test.creatorId))).append(';')
                    .append(test.status).append(';')
                    .append(test.questions.size()).append(';')
                    .append(test.results.size()).append(';')
                    .append(finished).append(';')
                    .append(aborted).append(';')
                    .append(String.format(Locale.US, "%.1f", test.averagePercent()))
                    .append('\n');
        }
        return sb.toString();
    }

    public synchronized String buildUsersCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("user_id;username;name;role;creation_blocked;blocked_until;reason\n");
        for (KnownUser user : getKnownUsers()) {
            UserRestriction restriction = store.load().restrictions.get(user.userId);
            sb.append(user.userId).append(';')
                    .append(escapeCsv(user.username == null ? "" : "@" + user.username)).append(';')
                    .append(escapeCsv(user.displayName())).append(';')
                    .append(getRole(user.userId)).append(';')
                    .append(isCreationBlocked(user.userId)).append(';')
                    .append(restriction == null || restriction.blockedUntil == null ? "" : restriction.blockedUntil).append(';')
                    .append(escapeCsv(restriction == null ? "" : restriction.reason))
                    .append('\n');
        }
        return sb.toString();
    }

    public synchronized String buildResultsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("test_id;test_title;user;score;total;percent;status;finish_reason;completed_at\n");
        for (TestData test : getAllTests()) {
            for (TestResult result : test.results) {
                sb.append(test.testId).append(';')
                        .append(escapeCsv(test.title)).append(';')
                        .append(escapeCsv(result.userName)).append(';')
                        .append(result.score).append(';')
                        .append(result.total).append(';')
                        .append(String.format(Locale.US, "%.1f", result.getPercent())).append(';')
                        .append(result.aborted ? "aborted" : "finished").append(';')
                        .append(escapeCsv(result.finishReason)).append(';')
                        .append(result.completedAt == null ? "" : result.completedAt)
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized String buildAnswerDetailsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("test_id;test_title;user;question;user_answer;correct_answer;correct\n");
        for (TestData test : getAllTests()) {
            for (TestResult result : test.results) {
                for (QuestionAnswerDetail detail : result.details) {
                    sb.append(test.testId).append(';')
                            .append(escapeCsv(test.title)).append(';')
                            .append(escapeCsv(result.userName)).append(';')
                            .append(escapeCsv(detail.questionText)).append(';')
                            .append(escapeCsv(detail.userAnswer)).append(';')
                            .append(escapeCsv(detail.correctAnswer)).append(';')
                            .append(detail.correct)
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    public synchronized String exportTestAsText(String testId) {
        TestData test = getTestById(testId);
        if (test == null) {
            return "Тест не найден";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TEST_MASTER_BOT_EXPORT\n");
        sb.append("Название: ").append(test.title).append("\n");
        sb.append("Описание: ").append(test.description == null ? "" : test.description).append("\n");
        sb.append("Код запуска: ").append(test.testId).append("\n");
        sb.append("Статус: ").append(test.status).append("\n");
        sb.append("Время теста: ").append(test.totalTimeLimitSeconds == null ? "без ограничения" : test.totalTimeLimitSeconds + " сек.").append("\n");
        sb.append("Показ ответов: ").append(test.getEffectiveAnswerRevealMode()).append("\n\n");
        for (int i = 0; i < test.questions.size(); i++) {
            Question q = test.questions.get(i);
            sb.append("Вопрос ").append(i + 1).append(": ").append(q.text).append("\n");
            sb.append("Тип: ").append(q.type).append("\n");
            if (q.type == QuestionType.SINGLE_CHOICE) {
                for (int j = 0; j < q.options.size(); j++) {
                    sb.append(j + 1).append(") ").append(q.options.get(j)).append("\n");
                }
                sb.append("Правильный вариант: ").append(q.correctOptionIndex == null ? "" : q.correctOptionIndex + 1).append("\n");
            } else if (q.type == QuestionType.TEXT_INPUT) {
                sb.append("Правильный ответ: ").append(q.correctTextAnswer).append("\n");
            } else {
                sb.append("Правильное число: ").append(q.correctNumberAnswer).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public synchronized SupportRequest createSupportRequest(long userId, String userName, String text) {
        BotData data = store.load();
        SupportRequest request = new SupportRequest();
        request.requestId = codeGenerator.generateResultId();
        request.userId = userId;
        request.userName = userName;
        request.text = text;
        request.createdAt = LocalDateTime.now();
        request.answered = false;
        data.supportRequests.put(request.requestId, request);
        store.save();
        return request;
    }

    public synchronized List<SupportRequest> getSupportRequests() {
        return store.load().supportRequests.values().stream()
                .sorted(Comparator.comparing((SupportRequest request) -> request.createdAt == null ? LocalDateTime.MIN : request.createdAt).reversed())
                .toList();
    }

    public synchronized SupportRequest getSupportRequest(String requestId) {
        return store.load().supportRequests.get(requestId);
    }

    public synchronized SupportRequest answerSupportRequest(String requestId, long responderId, String responderName, String answer) {
        BotData data = store.load();
        SupportRequest request = data.supportRequests.get(requestId);
        if (request == null) {
            return null;
        }
        request.answered = true;
        request.responderId = responderId;
        request.responderName = responderName;
        request.answer = answer;
        request.answeredAt = LocalDateTime.now();
        store.save();
        return request;
    }

    public synchronized SupportRequest getLatestAnsweredSupportForUser(long userId) {
        return store.load().supportRequests.values().stream()
                .filter(request -> request.userId == userId && request.answered)
                .sorted(Comparator.comparing((SupportRequest request) -> request.answeredAt == null ? LocalDateTime.MIN : request.answeredAt).reversed())
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
        demo.creatorId = 0L;
        demo.creatorName = "System";
        demo.title = "Демо-тест по Java";
        demo.description = "Демонстрационный тест с разными типами вопросов.";
        demo.status = PublicationStatus.APPROVED;
        demo.answerRevealMode = AnswerRevealMode.IMMEDIATE;
        demo.showCorrectAnswerImmediately = true;
        demo.totalTimeLimitSeconds = null;
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

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(";") || escaped.contains("\n") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
