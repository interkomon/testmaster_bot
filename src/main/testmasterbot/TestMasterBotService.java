package main.testmasterbot;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TestMasterBotService implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;

    private final Map<Long, BotState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, TestDraft> drafts = new ConcurrentHashMap<>();
    private final Map<Integer, TestData> tests = new ConcurrentHashMap<>();
    private final Map<Long, QuizSession> sessions = new ConcurrentHashMap<>();

    private final AtomicInteger testIdGenerator = new AtomicInteger(1);

    public TestMasterBotService(String botToken) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        seedDemoTest();
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }

            if (update.hasMessage() && update.getMessage().hasText()) {
                handleTextMessage(update.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();

            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendText(chatId, "Произошла ошибка при обработке команды. Попробуй ещё раз.", buildMainMenu());
            }
        }
    }

    private void handleTextMessage(Message message) {
        long chatId = message.getChatId();
        String text = message.getText().trim();
        String userName = getUserDisplayName(message.getFrom());

        if (text.equalsIgnoreCase("/start")) {
            userStates.put(chatId, BotState.IDLE);
            sendText(chatId,
                    "Привет, " + userName + "!\n" +
                            "Я TestMasterBot.\n\n" +
                            "Сейчас ты можешь:\n" +
                            "- создавать тесты\n" +
                            "- публиковать их\n" +
                            "- проходить тесты\n" +
                            "- смотреть простую статистику\n\n" +
                            "Выбирай действие в меню.",
                    buildMainMenu());
            return;
        }

        if (text.equalsIgnoreCase("/help") || text.equalsIgnoreCase("Помощь")) {
            sendHelp(chatId);
            return;
        }

        if (text.equalsIgnoreCase("Меню")) {
            sendText(chatId, "Главное меню:", buildMainMenu());
            return;
        }

        if (text.equalsIgnoreCase("Отмена")) {
            cancelCurrentFlow(chatId);
            return;
        }

        if (text.startsWith("/play")) {
            handlePlayCommand(chatId, text);
            return;
        }

        if (text.equalsIgnoreCase("Создать тест")) {
            startCreateTest(chatId);
            return;
        }

        if (text.equalsIgnoreCase("Мои тесты")) {
            sendMyTests(chatId);
            return;
        }

        if (text.equalsIgnoreCase("Опубликованные тесты") || text.equalsIgnoreCase("Пройти тест")) {
            sendPublishedTests(chatId);
            return;
        }

        BotState state = userStates.getOrDefault(chatId, BotState.IDLE);

        switch (state) {
            case WAITING_TEST_TITLE -> handleTestTitle(chatId, text);
            case WAITING_QUESTION_TEXT -> handleQuestionText(chatId, text);
            case WAITING_OPTION_A -> handleOption(chatId, text, 0, BotState.WAITING_OPTION_B, "Введи вариант ответа B:");
            case WAITING_OPTION_B -> handleOption(chatId, text, 1, BotState.WAITING_OPTION_C, "Введи вариант ответа C:");
            case WAITING_OPTION_C -> handleOption(chatId, text, 2, BotState.WAITING_OPTION_D, "Введи вариант ответа D:");
            case WAITING_OPTION_D -> handleOption(chatId, text, 3, BotState.WAITING_CORRECT_OPTION,
                    "Теперь укажи правильный вариант: A, B, C, D или 1, 2, 3, 4.");
            case WAITING_CORRECT_OPTION -> handleCorrectOption(chatId, text);
            default -> sendText(chatId,
                    "Не понял команду.\nВыбери кнопку из меню или напиши /help.",
                    buildMainMenu());
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        String userName = getUserDisplayName(callbackQuery.getFrom());

        if (data == null || data.isBlank()) {
            answerCallback(callbackQuery.getId(), "Пустое действие.");
            return;
        }

        if (data.equals("draft:add_question")) {
            userStates.put(chatId, BotState.WAITING_QUESTION_TEXT);
            answerCallback(callbackQuery.getId(), "Добавляем новый вопрос.");
            sendText(chatId, "Введи текст следующего вопроса:", buildMainMenu());
            return;
        }

        if (data.equals("draft:finish")) {
            TestDraft draft = drafts.get(chatId);

            if (draft == null || draft.questions.isEmpty()) {
                answerCallback(callbackQuery.getId(), "Сначала добавь хотя бы один вопрос.");
                return;
            }

            answerCallback(callbackQuery.getId(), "Выбери режим доступа.");
            sendText(chatId,
                    "Выбери, каким будет тест:\n" +
                            "- публичный — его увидят все\n" +
                            "- приватный — запуск только по ID владельцем",
                    buildVisibilityKeyboard());
            return;
        }

        if (data.equals("visibility:public")) {
            finishCreateTest(chatId, userName, true);
            answerCallback(callbackQuery.getId(), "Тест сохранён как публичный.");
            return;
        }

        if (data.equals("visibility:private")) {
            finishCreateTest(chatId, userName, false);
            answerCallback(callbackQuery.getId(), "Тест сохранён как приватный.");
            return;
        }

        if (data.startsWith("play:")) {
            String[] parts = data.split(":");
            if (parts.length != 2) {
                answerCallback(callbackQuery.getId(), "Некорректный ID теста.");
                return;
            }

            try {
                int testId = Integer.parseInt(parts[1]);
                answerCallback(callbackQuery.getId(), "Запускаем тест.");
                startTest(chatId, userName, testId);
            } catch (NumberFormatException e) {
                answerCallback(callbackQuery.getId(), "Некорректный ID теста.");
            }
            return;
        }

        if (data.startsWith("answer:")) {
            handleAnswerCallback(callbackQuery);
            return;
        }

        answerCallback(callbackQuery.getId(), "Неизвестное действие.");
    }

    private void startCreateTest(long chatId) {
        drafts.put(chatId, new TestDraft());
        userStates.put(chatId, BotState.WAITING_TEST_TITLE);

        sendText(chatId,
                "Создание теста.\n\n" +
                        "Шаг 1.\nВведи название теста.\n\n" +
                        "Для отмены нажми «Отмена».",
                buildMainMenu());
    }

    private void handleTestTitle(long chatId, String text) {
        if (text.isBlank()) {
            sendText(chatId, "Название не должно быть пустым. Введи название теста ещё раз.", buildMainMenu());
            return;
        }

        TestDraft draft = drafts.computeIfAbsent(chatId, id -> new TestDraft());
        draft.title = text;

        userStates.put(chatId, BotState.WAITING_QUESTION_TEXT);

        sendText(chatId,
                "Название сохранено: " + draft.title + "\n\n" +
                        "Теперь введи текст первого вопроса.",
                buildMainMenu());
    }

    private void handleQuestionText(long chatId, String text) {
        if (text.isBlank()) {
            sendText(chatId, "Текст вопроса не должен быть пустым. Введи вопрос ещё раз.", buildMainMenu());
            return;
        }

        TestDraft draft = drafts.get(chatId);
        if (draft == null) {
            startCreateTest(chatId);
            return;
        }

        draft.currentQuestionText = text;
        draft.currentOptions.clear();

        userStates.put(chatId, BotState.WAITING_OPTION_A);

        sendText(chatId, "Введи вариант ответа A:", buildMainMenu());
    }

    private void handleOption(long chatId, String text, int optionIndex, BotState nextState, String nextMessage) {
        if (text.isBlank()) {
            sendText(chatId, "Вариант ответа не должен быть пустым. Введи его ещё раз.", buildMainMenu());
            return;
        }

        TestDraft draft = drafts.get(chatId);
        if (draft == null) {
            startCreateTest(chatId);
            return;
        }

        while (draft.currentOptions.size() <= optionIndex) {
            draft.currentOptions.add("");
        }

        draft.currentOptions.set(optionIndex, text);
        userStates.put(chatId, nextState);

        sendText(chatId, nextMessage, buildMainMenu());
    }

    private void handleCorrectOption(long chatId, String text) {
        TestDraft draft = drafts.get(chatId);
        if (draft == null) {
            startCreateTest(chatId);
            return;
        }

        int correctIndex = parseCorrectOption(text);
        if (correctIndex == -1) {
            sendText(chatId,
                    "Нужно указать A, B, C, D или 1, 2, 3, 4.\nПопробуй ещё раз.",
                    buildMainMenu());
            return;
        }

        if (draft.currentQuestionText == null || draft.currentQuestionText.isBlank() || draft.currentOptions.size() < 4) {
            sendText(chatId, "Вопрос заполнен не полностью. Начни добавление вопроса заново.", buildMainMenu());
            userStates.put(chatId, BotState.WAITING_QUESTION_TEXT);
            return;
        }

        Question question = new Question(
                draft.currentQuestionText,
                new ArrayList<>(draft.currentOptions),
                correctIndex
        );

        draft.questions.add(question);
        draft.currentQuestionText = null;
        draft.currentOptions.clear();

        userStates.put(chatId, BotState.IDLE);

        sendText(chatId,
                "Вопрос добавлен.\n\n" +
                        "Всего вопросов в тесте: " + draft.questions.size() + "\n\n" +
                        "Выбери следующее действие:",
                buildDraftActionKeyboard());
    }

    private void finishCreateTest(long chatId, String userName, boolean isPublic) {
        TestDraft draft = drafts.get(chatId);

        if (draft == null || draft.title == null || draft.title.isBlank() || draft.questions.isEmpty()) {
            sendText(chatId, "Невозможно сохранить пустой тест.", buildMainMenu());
            return;
        }

        int newTestId = testIdGenerator.getAndIncrement();

        TestData testData = new TestData();
        testData.id = newTestId;
        testData.creatorId = chatId;
        testData.creatorName = userName;
        testData.title = draft.title;
        testData.isPublic = isPublic;
        testData.questions = new ArrayList<>(draft.questions);

        tests.put(newTestId, testData);

        drafts.remove(chatId);
        userStates.put(chatId, BotState.IDLE);

        sendText(chatId,
                "Тест сохранён.\n\n" +
                        "ID теста: " + testData.id + "\n" +
                        "Название: " + testData.title + "\n" +
                        "Вопросов: " + testData.questions.size() + "\n" +
                        "Доступ: " + (testData.isPublic ? "публичный" : "приватный") + "\n\n" +
                        "Для запуска можно использовать команду /play " + testData.id,
                buildMainMenu());
    }

    private void sendMyTests(long chatId) {
        List<TestData> myTests = tests.values().stream()
                .filter(test -> test.creatorId == chatId)
                .sorted(Comparator.comparingInt(test -> test.id))
                .toList();

        if (myTests.isEmpty()) {
            sendText(chatId,
                    "У тебя пока нет тестов.\nНажми «Создать тест», чтобы добавить первый.",
                    buildMainMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("Твои тесты:\n\n");

        for (TestData test : myTests) {
            sb.append("#").append(test.id).append(" — ").append(test.title).append("\n")
                    .append("Доступ: ").append(test.isPublic ? "публичный" : "приватный").append("\n")
                    .append("Вопросов: ").append(test.questions.size()).append("\n")
                    .append("Прохождений: ").append(test.results.size()).append("\n")
                    .append("Средний результат: ").append(formatDouble(test.getAveragePercent())).append("%\n");

            if (!test.results.isEmpty()) {
                sb.append("Последние результаты:\n");

                int start = Math.max(0, test.results.size() - 3);
                for (int i = test.results.size() - 1; i >= start; i--) {
                    TestResult result = test.results.get(i);
                    sb.append("- ")
                            .append(result.userName)
                            .append(": ")
                            .append(result.score)
                            .append("/")
                            .append(result.total)
                            .append(" (")
                            .append(result.getPercentText())
                            .append(")")
                            .append("\n");
                }
            }

            sb.append("Запуск: /play ").append(test.id).append("\n\n");
        }

        sendText(chatId, sb.toString(), buildMainMenu());
    }

    private void sendPublishedTests(long chatId) {
        List<TestData> publishedTests = tests.values().stream()
                .filter(test -> test.isPublic)
                .sorted(Comparator.comparingInt(test -> test.id))
                .toList();

        if (publishedTests.isEmpty()) {
            sendText(chatId, "Сейчас нет опубликованных тестов.", buildMainMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("Опубликованные тесты:\n\n");
        for (TestData test : publishedTests) {
            sb.append("#").append(test.id).append(" — ").append(test.title)
                    .append(" (вопросов: ").append(test.questions.size()).append(")\n");
        }

        sb.append("\nМожно нажать кнопку ниже или ввести команду вида /play ID");

        sendText(chatId, sb.toString(), buildPlayKeyboard(publishedTests));
    }

    private void handlePlayCommand(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 2) {
            sendText(chatId, "Используй формат: /play ID\nНапример: /play 1", buildMainMenu());
            return;
        }

        try {
            int testId = Integer.parseInt(parts[1]);
            startTest(chatId, "Пользователь", testId);
        } catch (NumberFormatException e) {
            sendText(chatId, "ID теста должен быть числом.", buildMainMenu());
        }
    }

    private void startTest(long chatId, String userName, int testId) {
        TestData test = tests.get(testId);

        if (test == null) {
            sendText(chatId, "Тест с таким ID не найден.", buildMainMenu());
            return;
        }

        if (!test.isPublic && test.creatorId != chatId) {
            sendText(chatId, "Это приватный тест. Доступ разрешён только владельцу.", buildMainMenu());
            return;
        }

        if (test.questions.isEmpty()) {
            sendText(chatId, "В этом тесте пока нет вопросов.", buildMainMenu());
            return;
        }

        QuizSession session = new QuizSession();
        session.testId = testId;
        session.currentQuestionIndex = 0;
        session.score = 0;
        session.userName = userName;

        sessions.put(chatId, session);

        sendQuestion(chatId, test, session);
    }

    private void sendQuestion(long chatId, TestData test, QuizSession session) {
        Question question = test.questions.get(session.currentQuestionIndex);

        StringBuilder sb = new StringBuilder();
        sb.append("Тест: ").append(test.title).append("\n")
                .append("Вопрос ").append(session.currentQuestionIndex + 1)
                .append("/").append(test.questions.size()).append("\n\n")
                .append(question.text).append("\n\n");

        for (int i = 0; i < question.options.size(); i++) {
            sb.append((char) ('A' + i)).append(". ").append(question.options.get(i)).append("\n");
        }

        sendText(chatId, sb.toString(), buildAnswerKeyboard(test.id, session.currentQuestionIndex, question.options));
    }

    private void handleAnswerCallback(CallbackQuery callbackQuery) {
        String[] parts = callbackQuery.getData().split(":");
        long chatId = callbackQuery.getMessage().getChatId();

        if (parts.length != 4) {
            answerCallback(callbackQuery.getId(), "Некорректный ответ.");
            return;
        }

        try {
            int testId = Integer.parseInt(parts[1]);
            int questionIndex = Integer.parseInt(parts[2]);
            int selectedIndex = Integer.parseInt(parts[3]);

            QuizSession session = sessions.get(chatId);
            TestData test = tests.get(testId);

            if (session == null || test == null) {
                answerCallback(callbackQuery.getId(), "Сессия теста не найдена.");
                return;
            }

            if (session.testId != testId || session.currentQuestionIndex != questionIndex) {
                answerCallback(callbackQuery.getId(), "Этот вопрос уже обработан.");
                return;
            }

            Question question = test.questions.get(questionIndex);
            boolean isCorrect = selectedIndex == question.correctIndex;

            if (isCorrect) {
                session.score++;
            }

            editAnsweredQuestion(callbackQuery, test, questionIndex, selectedIndex);
            answerCallback(callbackQuery.getId(), isCorrect ? "Верно!" : "Неверно.");

            session.currentQuestionIndex++;

            if (session.currentQuestionIndex < test.questions.size()) {
                sendQuestion(chatId, test, session);
            } else {
                finishQuiz(chatId, callbackQuery.getFrom(), test, session);
            }

        } catch (NumberFormatException e) {
            answerCallback(callbackQuery.getId(), "Ошибка формата ответа.");
        }
    }

    private void finishQuiz(long chatId, User telegramUser, TestData test, QuizSession session) {
        sessions.remove(chatId);

        TestResult result = new TestResult();
        result.userId = telegramUser != null ? telegramUser.getId() : chatId;
        result.userName = getUserDisplayName(telegramUser);
        result.score = session.score;
        result.total = test.questions.size();
        result.completedAt = LocalDateTime.now();

        test.results.add(result);

        double percent = result.getPercent();
        String mark = calculateMark(percent);

        sendText(chatId,
                "Тест завершён.\n\n" +
                        "Название: " + test.title + "\n" +
                        "Результат: " + result.score + "/" + result.total + "\n" +
                        "Процент: " + formatDouble(percent) + "%\n" +
                        "Оценка: " + mark + "\n\n" +
                        "Выбери следующее действие в меню.",
                buildMainMenu());
    }

    private void editAnsweredQuestion(CallbackQuery callbackQuery, TestData test, int questionIndex, int selectedIndex) {
        Question question = test.questions.get(questionIndex);

        StringBuilder sb = new StringBuilder();
        sb.append("Тест: ").append(test.title).append("\n")
                .append("Вопрос ").append(questionIndex + 1)
                .append("/").append(test.questions.size()).append("\n\n")
                .append(question.text).append("\n\n");

        for (int i = 0; i < question.options.size(); i++) {
            String prefix = (char) ('A' + i) + ". ";

            if (i == question.correctIndex) {
                prefix = "✅ " + prefix;
            } else if (i == selectedIndex) {
                prefix = "❌ " + prefix;
            }

            sb.append(prefix).append(question.options.get(i)).append("\n");
        }

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(callbackQuery.getMessage().getChatId())
                .messageId(callbackQuery.getMessage().getMessageId())
                .text(sb.toString())
                .build();

        try {
            telegramClient.execute(editMessageText);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendHelp(long chatId) {
        sendText(chatId,
                "Доступные возможности:\n\n" +
                        "1. Создать тест\n" +
                        "2. Посмотреть свои тесты\n" +
                        "3. Посмотреть опубликованные тесты\n" +
                        "4. Пройти тест\n\n" +
                        "Команды:\n" +
                        "/start — запуск бота\n" +
                        "/help — помощь\n" +
                        "/play ID — запустить тест по ID\n\n" +
                        "Во время создания теста можно нажать «Отмена».",
                buildMainMenu());
    }

    private void cancelCurrentFlow(long chatId) {
        drafts.remove(chatId);
        userStates.put(chatId, BotState.IDLE);

        sendText(chatId, "Текущее действие отменено.", buildMainMenu());
    }

    private ReplyKeyboardMarkup buildMainMenu() {
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Создать тест");
        row1.add("Мои тесты");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Опубликованные тесты");
        row2.add("Пройти тест");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("Помощь");
        row3.add("Отмена");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        return ReplyKeyboardMarkup.builder()
                .keyboard(rows)
                .resizeKeyboard(true)
                .build();
    }

    private InlineKeyboardMarkup buildDraftActionKeyboard() {
        InlineKeyboardButton addQuestion = InlineKeyboardButton.builder()
                .text("Добавить ещё вопрос")
                .callbackData("draft:add_question")
                .build();

        InlineKeyboardButton finishTest = InlineKeyboardButton.builder()
                .text("Завершить тест")
                .callbackData("draft:finish")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(addQuestion));
        rows.add(new InlineKeyboardRow(finishTest));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildVisibilityKeyboard() {
        InlineKeyboardButton publicButton = InlineKeyboardButton.builder()
                .text("Сделать публичным")
                .callbackData("visibility:public")
                .build();

        InlineKeyboardButton privateButton = InlineKeyboardButton.builder()
                .text("Оставить приватным")
                .callbackData("visibility:private")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(publicButton));
        rows.add(new InlineKeyboardRow(privateButton));

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildPlayKeyboard(List<TestData> publishedTests) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (TestData test : publishedTests) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text("Пройти #" + test.id + " — " + shorten(test.title, 24))
                    .callbackData("play:" + test.id)
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardMarkup buildAnswerKeyboard(int testId, int questionIndex, List<String> options) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (int i = 0; i < options.size(); i++) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(String.valueOf((char) ('A' + i)))
                    .callbackData("answer:" + testId + ":" + questionIndex + ":" + i)
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private void sendText(long chatId, String text, ReplyKeyboard replyKeyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(replyKeyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void answerCallback(String callbackId, String text) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text(text)
                .build();

        try {
            telegramClient.execute(answer);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private int parseCorrectOption(String text) {
        String normalized = text.trim().toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "A", "1" -> 0;
            case "B", "2" -> 1;
            case "C", "3" -> 2;
            case "D", "4" -> 3;
            default -> -1;
        };
    }

    private String getUserDisplayName(User user) {
        if (user == null) {
            return "Пользователь";
        }

        if (user.getUserName() != null && !user.getUserName().isBlank()) {
            return "@" + user.getUserName();
        }

        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }

        return "Пользователь";
    }

    private Long extractChatId(Update update) {
        if (update == null) {
            return null;
        }

        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }

        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }

        return null;
    }

    private String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        if (text.length() <= maxLength) {
            return text;
        }

        return text.substring(0, maxLength - 3) + "...";
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String calculateMark(double percent) {
        if (percent >= 90) {
            return "5";
        }
        if (percent >= 75) {
            return "4";
        }
        if (percent >= 60) {
            return "3";
        }
        return "2";
    }

    private void seedDemoTest() {
        TestData demo = new TestData();
        demo.id = testIdGenerator.getAndIncrement();
        demo.creatorId = 0L;
        demo.creatorName = "System";
        demo.title = "Демо-тест по Java";
        demo.isPublic = true;
        demo.questions = new ArrayList<>();

        demo.questions.add(new Question(
                "Какой тип данных используется для целых чисел в Java?",
                List.of("String", "int", "boolean", "double"),
                1
        ));

        demo.questions.add(new Question(
                "Как называется точка входа в Java-программу?",
                List.of("run()", "start()", "main()", "init()"),
                2
        ));

        demo.questions.add(new Question(
                "Какой оператор используется для сравнения значений?",
                List.of("=", "==", "!=", "=>"),
                1
        ));

        tests.put(demo.id, demo);
    }

    private enum BotState {
        IDLE,
        WAITING_TEST_TITLE,
        WAITING_QUESTION_TEXT,
        WAITING_OPTION_A,
        WAITING_OPTION_B,
        WAITING_OPTION_C,
        WAITING_OPTION_D,
        WAITING_CORRECT_OPTION
    }

    private static class TestDraft {
        String title;
        String currentQuestionText;
        List<String> currentOptions = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
    }

    private static class Question {
        String text;
        List<String> options;
        int correctIndex;

        Question(String text, List<String> options, int correctIndex) {
            this.text = text;
            this.options = options;
            this.correctIndex = correctIndex;
        }
    }

    private static class TestData {
        int id;
        long creatorId;
        String creatorName;
        String title;
        boolean isPublic;
        List<Question> questions = new ArrayList<>();
        List<TestResult> results = new ArrayList<>();

        double getAveragePercent() {
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

    private static class QuizSession {
        int testId;
        int currentQuestionIndex;
        int score;
        String userName;
    }

    private static class TestResult {
        long userId;
        String userName;
        int score;
        int total;
        LocalDateTime completedAt;

        double getPercent() {
            if (total == 0) {
                return 0.0;
            }
            return (score * 100.0) / total;
        }

        String getPercentText() {
            return String.format(Locale.US, "%.1f%%", getPercent());
        }

        @SuppressWarnings("unused")
        String getCompletedAtText() {
            return completedAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        }
    }
}