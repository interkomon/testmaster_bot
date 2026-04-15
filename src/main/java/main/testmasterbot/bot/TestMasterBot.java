package main.testmasterbot.bot;

import main.testmasterbot.model.*;
import main.testmasterbot.service.BotService;
import main.testmasterbot.state.SessionState;
import main.testmasterbot.state.UserSession;
import main.testmasterbot.util.TextUtils;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestMasterBot implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final BotService botService;
    private final String botUsername;

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, QuizRuntime> quizSessions = new ConcurrentHashMap<>();

    public TestMasterBot(String botToken, BotService botService) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.botService = botService;
        this.botUsername = System.getenv("TELEGRAM_BOT_USERNAME");
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }

            if (update.hasMessage()) {
                Message message = update.getMessage();
                if (message.hasPhoto()) {
                    handlePhoto(message);
                    return;
                }
                if (message.hasText()) {
                    handleText(message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendText(chatId, "Произошла ошибка при обработке команды.", MenuFactory.mainMenu(botService.getRole(chatId)));
            }
        }
    }

    private void handleText(Message message) {
        long chatId = message.getChatId();
        String text = message.getText().trim();
        User user = message.getFrom();
        String userName = displayName(user);
        Role role = botService.getRole(chatId);
        UserSession session = sessions.computeIfAbsent(chatId, id -> new UserSession());

        if (handleQuizTextIfNeeded(chatId, user, text)) {
            return;
        }

        if (text.startsWith("/start")) {
            handleStart(chatId, text, role, userName);
            return;
        }
        if (text.equalsIgnoreCase("/help") || text.equals("❓ Помощь")) {
            sendHelp(chatId, role);
            return;
        }
        if (text.equals("🏠 Меню")) {
            session.state = SessionState.IDLE;
            sendText(chatId, welcomeText(userName, role), MenuFactory.mainMenu(role));
            return;
        }
        if (text.equals("✋ Отмена")) {
            sessions.remove(chatId);
            quizSessions.remove(chatId);
            sendText(chatId, "Действие отменено.", MenuFactory.mainMenu(role));
            return;
        }
        if (text.startsWith("/play ")) {
            startTestByCode(chatId, userName, text.substring(6).trim().toUpperCase(Locale.ROOT));
            return;
        }
        if (text.equals("/id")) {
            sendText(chatId, "Твой Telegram ID: " + chatId, MenuFactory.mainMenu(role));
            return;
        }

        if (text.equals("➕ Создать тест")) {
            session.prepareNewTest();
            session.state = SessionState.WAITING_TEST_TITLE;
            sendInputPrompt(chatId, "Введи название теста.");
            return;
        }
        if (text.equals("📚 Мои тесты")) {
            showMyTests(chatId);
            return;
        }
        if (text.equals("🌍 Опубликованные тесты")) {
            showPublishedTests(chatId);
            return;
        }
        if (text.equals("🛡 Очередь модерации")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                sendText(chatId, "У тебя нет доступа к модерации.", MenuFactory.mainMenu(role));
                return;
            }
            showModerationQueue(chatId);
            return;
        }

        if (text.equals("⚙️ Админ-панель")) {
            if (!botService.isAdmin(chatId)) {
                sendText(chatId, "У тебя нет доступа к панели администратора.", MenuFactory.mainMenu(role));
                return;
            }
            showAdminPanel(chatId);
            return;
        }

        switch (session.state) {
            case WAITING_TEST_TITLE -> handleTestTitle(chatId, text);
            case WAITING_TEST_DESCRIPTION -> handleTestDescription(chatId, text);
            case WAITING_QUESTION_TEXT -> handleQuestionText(chatId, text);
            case WAITING_OPTION_TEXT -> handleOptionText(chatId, text);
            case WAITING_CORRECT_TEXT_ANSWER -> handleCorrectText(chatId, text);
            case WAITING_CORRECT_NUMBER_ANSWER -> handleCorrectNumber(chatId, text);
            case WAITING_QUESTION_PHOTO -> sendInputPromptWithMenu(chatId, "Пришли фото одним сообщением или нажми кнопку «Без фото» ниже.", MenuFactory.skipPhotoMenu());
            case WAITING_EDIT_TITLE -> finishEditTitle(chatId, text);
            case WAITING_EDIT_DESCRIPTION -> finishEditDescription(chatId, text);
            case WAITING_ADMIN_ADD_MODERATOR -> finishSetRole(chatId, text, Role.MODERATOR);
            case WAITING_ADMIN_ADD_ADMIN -> finishSetRole(chatId, text, Role.ADMIN);
            case WAITING_ADMIN_REMOVE_ROLE -> finishRemoveRole(chatId, text);
            default -> sendText(chatId, "Выбери действие через меню.", MenuFactory.mainMenu(role));
        }
    }

    private void handlePhoto(Message message) {
        long chatId = message.getChatId();
        UserSession session = sessions.computeIfAbsent(chatId, id -> new UserSession());
        if (session.state != SessionState.WAITING_QUESTION_PHOTO) {
            sendText(chatId, "Сейчас фото не ожидается.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        List<PhotoSize> photos = message.getPhoto();
        if (photos == null || photos.isEmpty()) {
            sendInputPromptWithMenu(chatId, "Не удалось получить фото. Попробуй ещё раз.", MenuFactory.skipPhotoMenu());
            return;
        }
        String fileId = photos.get(photos.size() - 1).getFileId();
        session.draftQuestion.photoFileId = fileId;
        completeQuestion(chatId);
    }

    private void handleStart(long chatId, String text, Role role, String userName) {
        if (text.equalsIgnoreCase("/start")) {
            sendText(chatId, welcomeText(userName, role), MenuFactory.mainMenu(role));
            return;
        }
        String payload = text.substring("/start".length()).trim();
        if (payload.startsWith("test_")) {
            startTestByCode(chatId, userName, payload.substring(5).trim().toUpperCase(Locale.ROOT));
            return;
        }
        sendText(chatId, welcomeText(userName, role), MenuFactory.mainMenu(role));
    }

    private String welcomeText(String userName, Role role) {
        String roleText = switch (role) {
            case USER -> "Пользователь";
            case MODERATOR -> "Модератор";
            case ADMIN -> "Администратор";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("Привет, ").append(userName).append("!\n\n")
                .append("Твоя роль: ").append(roleText).append("\n\n")
                .append("Возможности:\n")
                .append("• Создание тестов\n")
                .append("• Описание теста\n")
                .append("• Вопросы с вариантами, текстом и числами\n")
                .append("• Фото к вопросам\n")
                .append("• Приватные коды и ссылки\n")
                .append("• Подробные результаты прохождения\n");
        if (role == Role.MODERATOR || role == Role.ADMIN) {
            sb.append("• Меню модератора доступно\n");
        }
        if (role == Role.ADMIN) {
            sb.append("• Меню администратора доступно\n");
        }
        sb.append("\nДля своего ID напиши /id.");
        return sb.toString();
    }

    private void sendHelp(long chatId, Role role) {
        sendText(chatId,
                "Справка\n\n" +
                        "1. Нажми «Создать тест».\n" +
                        "2. Введи название и описание.\n" +
                        "3. Выбери, показывать ли правильный ответ сразу при ошибке.\n" +
                        "4. Добавь вопросы через кнопки и текстовый ввод.\n" +
                        "5. Заверши тест и выбери режим доступа.\n" +
                        "6. Просматривай результаты в разделе «Мои тесты».\n\n" +
                        "Во время текстового ввода большое меню скрывается. Открыть меню можно маленькой кнопкой под сообщением.",
                MenuFactory.mainMenu(role));
    }

    private void handleTestTitle(long chatId, String text) {
        if (text.isBlank()) {
            sendInputPrompt(chatId, "Название не должно быть пустым. Введи название теста ещё раз.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftTest.title = text.trim();
        session.state = SessionState.WAITING_TEST_DESCRIPTION;
        sendInputPrompt(chatId, "Введи описание теста.");
    }

    private void handleTestDescription(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        session.draftTest.description = text.trim();
        session.state = SessionState.IDLE;
        sendInline(chatId,
                "Показывать правильный ответ сразу, если пользователь ошибся?",
                MenuFactory.answerPolicyMenu(Boolean.TRUE.equals(session.draftTest.showCorrectAnswerImmediately)));
    }

    private void startFirstQuestion(long chatId) {
        UserSession session = sessions.get(chatId);
        session.resetCurrentQuestion();
        session.state = SessionState.WAITING_QUESTION_TEXT;
        sendInputPrompt(chatId, "Введи текст вопроса.");
    }

    private void handleQuestionText(long chatId, String text) {
        if (text.isBlank()) {
            sendInputPrompt(chatId, "Текст вопроса не должен быть пустым. Введи вопрос ещё раз.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftQuestion.text = text.trim();
        session.state = SessionState.IDLE;
        sendInline(chatId, "Выбери тип вопроса:", MenuFactory.questionTypeMenu());
    }

    private void handleOptionText(long chatId, String text) {
        if (text.isBlank()) {
            sendInputPrompt(chatId, "Вариант ответа не должен быть пустым.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftQuestion.options.add(text.trim());
        session.currentOptionIndex++;
        if (session.currentOptionIndex < session.expectedOptionsCount) {
            sendInputPrompt(chatId, "Введи текст варианта " + (session.currentOptionIndex + 1) + ".");
        } else {
            session.state = SessionState.IDLE;
            sendInline(chatId, buildOptionsPreview(session.draftQuestion.options), MenuFactory.correctOptionMenu(session.expectedOptionsCount));
        }
    }

    private String buildOptionsPreview(List<String> options) {
        StringBuilder sb = new StringBuilder("Варианты сохранены.\n\nВыбери правильный вариант:\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append(i + 1).append(". ").append(options.get(i)).append("\n");
        }
        return sb.toString();
    }

    private void handleCorrectText(long chatId, String text) {
        if (text.isBlank()) {
            sendInputPrompt(chatId, "Ответ не должен быть пустым.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftQuestion.correctTextAnswer = text.trim();
        askAddPhoto(chatId);
    }

    private void handleCorrectNumber(long chatId, String text) {
        Double value = TextUtils.parseDouble(text);
        if (value == null) {
            sendInputPrompt(chatId, "Нужно ввести корректное число.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftQuestion.correctNumberAnswer = value;
        askAddPhoto(chatId);
    }

    private void askAddPhoto(long chatId) {
        UserSession session = sessions.get(chatId);
        if (session != null) {
            session.state = SessionState.IDLE;
        }
        sendInline(chatId, "Добавить фото к вопросу?", MenuFactory.photoDecisionMenu());
    }

    private void completeQuestion(long chatId) {
        UserSession session = sessions.get(chatId);
        session.draftTest.questions.add(session.draftQuestion);
        session.resetCurrentQuestion();
        session.state = SessionState.IDLE;
        sendInline(chatId,
                "Вопрос добавлен.\nВсего вопросов: " + session.draftTest.questions.size() + "\n\nЧто дальше?",
                MenuFactory.afterQuestionMenu());
    }

    private void finishEditTitle(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        if (session.selectedTestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Тест не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        botService.updateTestTitle(session.selectedTestId, text);
        session.state = SessionState.IDLE;
        openMyTest(chatId, session.selectedTestId);
    }

    private void finishEditDescription(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        if (session.selectedTestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Тест не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        botService.updateTestDescription(session.selectedTestId, text);
        session.state = SessionState.IDLE;
        openMyTest(chatId, session.selectedTestId);
    }

    private void finishSetRole(long chatId, String text, Role roleToSet) {
        try {
            long userId = Long.parseLong(text.trim());
            botService.setRole(userId, roleToSet);
            sessions.get(chatId).state = SessionState.IDLE;
            sendText(chatId,
                    "Роль обновлена.\nПользователь " + userId + " теперь " + roleToSet + ".",
                    MenuFactory.mainMenu(botService.getRole(chatId)));
        } catch (NumberFormatException e) {
            sendInputPrompt(chatId, "Нужно ввести числовой Telegram ID.");
        }
    }

    private void finishRemoveRole(long chatId, String text) {
        try {
            long userId = Long.parseLong(text.trim());
            botService.removeRole(userId);
            sessions.get(chatId).state = SessionState.IDLE;
            sendText(chatId,
                    "Специальная роль снята с пользователя " + userId + ".",
                    MenuFactory.mainMenu(botService.getRole(chatId)));
        } catch (NumberFormatException e) {
            sendInputPrompt(chatId, "Нужно ввести числовой Telegram ID.");
        }
    }

    private void showMyTests(long chatId) {
        List<TestData> tests = botService.getUserTests(chatId);
        Role role = botService.getRole(chatId);
        if (tests.isEmpty()) {
            sendText(chatId, "У тебя пока нет тестов.", MenuFactory.mainMenu(role));
            return;
        }
        StringBuilder sb = new StringBuilder("Твои тесты\n\n");
        for (TestData test : tests) {
            sb.append(test.title)
                    .append("\nID: ").append(test.testId)
                    .append("\nСтатус: ").append(statusText(test.status))
                    .append("\nПоказ ответа сразу: ").append(Boolean.TRUE.equals(test.showCorrectAnswerImmediately) ? "Да" : "Нет")
                    .append("\nВопросов: ").append(test.questions.size())
                    .append("\nПрохождений: ").append(test.results.size())
                    .append("\nСредний результат: ").append(TextUtils.formatPercent(test.averagePercent())).append("%\n\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.myTestsList(tests));
    }

    private void openMyTest(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null || test.creatorId != chatId) {
            sendText(chatId, "Тест не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        sessions.computeIfAbsent(chatId, id -> new UserSession()).selectedTestId = testId;
        String shareLink = shareLink(test);
        String playableCode = test.isPublicVisible() ? test.testId : test.accessCode;
        StringBuilder sb = new StringBuilder();
        sb.append("Тест\n\n")
                .append("Название: ").append(test.title).append("\n")
                .append("Описание: ").append(test.description == null || test.description.isBlank() ? "-" : test.description).append("\n")
                .append("ID: ").append(test.testId).append("\n")
                .append("Статус: ").append(statusText(test.status)).append("\n")
                .append("Показывать правильный ответ сразу: ").append(Boolean.TRUE.equals(test.showCorrectAnswerImmediately) ? "Да" : "Нет").append("\n")
                .append("Код запуска: ").append(playableCode).append("\n")
                .append("Вопросов: ").append(test.questions.size()).append("\n")
                .append("Прохождений: ").append(test.results.size()).append("\n")
                .append("Средний результат: ").append(TextUtils.formatPercent(test.averagePercent())).append("%\n");
        if (shareLink != null) {
            sb.append("Ссылка: ").append(shareLink).append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.myTestActions(test));
    }

    private void showPublishedTests(long chatId) {
        List<TestData> tests = botService.getPublishedTests();
        if (tests.isEmpty()) {
            sendText(chatId, "Сейчас нет опубликованных тестов.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder("Опубликованные тесты\n\n");
        for (TestData test : tests) {
            sb.append(test.title)
                    .append("\nОписание: ").append(test.description == null ? "-" : TextUtils.shorten(test.description, 80))
                    .append("\nКод: ").append(test.testId)
                    .append("\nВопросов: ").append(test.questions.size())
                    .append("\n\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.playList(tests));
    }

    private void showModerationQueue(long chatId) {
        List<TestData> tests = botService.getPendingTests();
        if (tests.isEmpty()) {
            sendText(chatId, "Меню модератора\n\nОчередь модерации пуста.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder("Меню модератора\n\nОчередь модерации\n\n");
        for (TestData test : tests) {
            sb.append(test.title)
                    .append("\nАвтор: ").append(test.creatorName)
                    .append("\nВопросов: ").append(test.questions.size())
                    .append("\n\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.moderationList(tests));
    }

    private void showAdminPanel(long chatId) {
        StringBuilder sb = new StringBuilder("Меню администратора\n\n")
                .append("Твоя роль: Администратор\n")
                .append("Используй кнопки ниже для управления ролями.\n");
        sendInline(chatId, sb.toString(), MenuFactory.adminMenu());
    }

    private void showRolesList(long chatId) {
        List<Map.Entry<Long, Role>> roles = botService.getAllRoles();
        StringBuilder sb = new StringBuilder("Список ролей\n\n");
        if (roles.isEmpty()) {
            sb.append("Специальных ролей пока нет.");
        } else {
            for (Map.Entry<Long, Role> entry : roles) {
                sb.append(entry.getKey()).append(" — ").append(entry.getValue()).append("\n");
            }
        }
        sendInline(chatId, sb.toString(), MenuFactory.adminMenu());
    }

    private void startTestByCode(long chatId, String userName, String code) {
        TestData test = botService.resolveTestByCode(chatId, code);
        if (test == null) {
            sendText(chatId, "Тест по такому коду не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        if (test.questions.isEmpty()) {
            sendText(chatId, "У теста нет вопросов.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        QuizRuntime runtime = new QuizRuntime();
        runtime.testId = test.testId;
        runtime.userName = userName;
        quizSessions.put(chatId, runtime);
        sendQuestion(chatId, test, runtime);
    }

    private void sendQuestion(long chatId, TestData test, QuizRuntime runtime) {
        Question question = test.questions.get(runtime.questionIndex);
        StringBuilder sb = new StringBuilder();
        sb.append("Тест: ").append(test.title)
                .append("\nВопрос ").append(runtime.questionIndex + 1).append("/").append(test.questions.size())
                .append("\n\n")
                .append(question.text)
                .append("\n\n");

        if (question.type == QuestionType.SINGLE_CHOICE) {
            for (int i = 0; i < question.options.size(); i++) {
                sb.append(i + 1).append(". ").append(question.options.get(i)).append("\n");
            }
            if (question.photoFileId != null && !question.photoFileId.isBlank()) {
                sendPhoto(chatId, question.photoFileId, sb.toString(), MenuFactory.answerButtons(test.testId, runtime.questionIndex, question.options.size()));
            } else {
                sendInline(chatId, sb.toString(), MenuFactory.answerButtons(test.testId, runtime.questionIndex, question.options.size()));
            }
            return;
        }

        if (question.type == QuestionType.TEXT_INPUT) {
            sb.append("Ответь текстом одним сообщением.");
        } else {
            sb.append("Ответь числом одним сообщением.");
        }

        if (question.photoFileId != null && !question.photoFileId.isBlank()) {
            sendPhoto(chatId, question.photoFileId, sb.toString(), MenuFactory.compactMenuButton());
        } else {
            sendInputPromptWithMenu(chatId, sb.toString(), MenuFactory.compactMenuButton());
        }
    }

    private boolean handleQuizTextIfNeeded(long chatId, User user, String text) {
        QuizRuntime runtime = quizSessions.get(chatId);
        if (runtime == null) {
            return false;
        }
        TestData test = botService.getTestById(runtime.testId);
        if (test == null) {
            quizSessions.remove(chatId);
            return false;
        }
        Question question = test.questions.get(runtime.questionIndex);
        if (question.type == QuestionType.SINGLE_CHOICE) {
            return false;
        }

        QuestionAnswerDetail detail = new QuestionAnswerDetail();
        detail.questionText = question.text;
        detail.questionType = question.type;

        boolean correct;
        if (question.type == QuestionType.TEXT_INPUT) {
            detail.userAnswer = text;
            detail.correctAnswer = question.correctTextAnswer;
            correct = TextUtils.normalize(text).equals(TextUtils.normalize(question.correctTextAnswer));
        } else {
            Double value = TextUtils.parseDouble(text);
            if (value == null) {
                sendInputPrompt(chatId, "Нужно ввести число.");
                return true;
            }
            detail.userAnswer = TextUtils.formatNumber(value);
            detail.correctAnswer = TextUtils.formatNumber(question.correctNumberAnswer);
            correct = Math.abs(value - question.correctNumberAnswer) < 0.000001;
        }
        detail.correct = correct;
        runtime.details.add(detail);
        if (correct) {
            runtime.score++;
            sendText(chatId, "✅ Верно!", MenuFactory.mainMenu(botService.getRole(chatId)));
        } else if (Boolean.TRUE.equals(test.showCorrectAnswerImmediately)) {
            sendText(chatId, "❌ Неверно.\nПравильный ответ: " + detail.correctAnswer, MenuFactory.mainMenu(botService.getRole(chatId)));
        } else {
            sendText(chatId, "❌ Неверно.", MenuFactory.mainMenu(botService.getRole(chatId)));
        }
        runtime.questionIndex++;
        if (runtime.questionIndex < test.questions.size()) {
            sendQuestion(chatId, test, runtime);
        } else {
            finishQuiz(chatId, user, test, runtime);
        }
        return true;
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();
        String userName = displayName(callback.getFrom());
        Role role = botService.getRole(chatId);
        UserSession session = sessions.computeIfAbsent(chatId, id -> new UserSession());

        if (data == null || data.isBlank()) {
            answerCallback(callback.getId(), "Пустое действие.");
            return;
        }

        if (data.equals("menu:open")) {
            sendText(chatId, welcomeText(userName, role), MenuFactory.mainMenu(role));
            answerCallback(callback.getId(), "Меню открыто.");
            return;
        }
        if (data.equals("policy:show")) {
            if (session.draftTest != null) {
                session.draftTest.showCorrectAnswerImmediately = true;
                startFirstQuestion(chatId);
            }
            answerCallback(callback.getId(), "Показывать ответ сразу: Да");
            return;
        }
        if (data.equals("policy:hide")) {
            if (session.draftTest != null) {
                session.draftTest.showCorrectAnswerImmediately = false;
                startFirstQuestion(chatId);
            }
            answerCallback(callback.getId(), "Показывать ответ сразу: Нет");
            return;
        }
        if (data.equals("draft:add_question")) {
            startFirstQuestion(chatId);
            answerCallback(callback.getId(), "Добавляем вопрос.");
            return;
        }
        if (data.equals("draft:finish")) {
            if (session.draftTest == null || session.draftTest.questions.isEmpty()) {
                answerCallback(callback.getId(), "Добавь хотя бы один вопрос.");
                return;
            }
            sendInline(chatId,
                    "Выбери режим доступа:\n🌍 Публичный — тест увидят все после модерации.\n🔒 Приватный — доступ по коду и ссылке.",
                    MenuFactory.visibilityMenu());
            answerCallback(callback.getId(), "Выбери режим доступа.");
            return;
        }
        if (data.equals("visibility:public") || data.equals("visibility:private")) {
            if (session.draftTest == null) {
                answerCallback(callback.getId(), "Черновик не найден.");
                return;
            }
            boolean makePublic = data.endsWith("public");
            TestData saved = botService.saveNewTest(session.draftTest, chatId, userName, makePublic);
            sessions.remove(chatId);

            String playableCode = saved.isPublicVisible() ? saved.testId : saved.accessCode;
            StringBuilder sb = new StringBuilder();
            sb.append("Тест сохранён.\n\n")
                    .append("Название: ").append(saved.title).append("\n")
                    .append("Описание: ").append(saved.description == null ? "-" : saved.description).append("\n")
                    .append("ID теста: ").append(saved.testId).append("\n")
                    .append("Статус: ").append(statusText(saved.status)).append("\n")
                    .append("Показывать ответ сразу: ").append(Boolean.TRUE.equals(saved.showCorrectAnswerImmediately) ? "Да" : "Нет").append("\n")
                    .append("Код запуска: ").append(playableCode).append("\n");
            if (saved.isPrivate()) {
                sb.append("Код доступа для других: ").append(saved.accessCode).append("\n");
            }
            String link = shareLink(saved);
            if (link != null) {
                sb.append("Ссылка: ").append(link).append("\n");
            }
            sendText(chatId, sb.toString(), MenuFactory.mainMenu(role));
            answerCallback(callback.getId(), "Готово.");
            return;
        }
        if (data.startsWith("qtype:")) {
            if (session.draftQuestion == null) {
                answerCallback(callback.getId(), "Вопрос не найден.");
                return;
            }
            String type = data.substring(6);
            switch (type) {
                case "single" -> {
                    session.draftQuestion.type = QuestionType.SINGLE_CHOICE;
                    sendInline(chatId, "Выбери количество вариантов ответа:", MenuFactory.optionsCountMenu());
                }
                case "text" -> {
                    session.draftQuestion.type = QuestionType.TEXT_INPUT;
                    session.state = SessionState.WAITING_CORRECT_TEXT_ANSWER;
                    sendInputPrompt(chatId, "Введи правильный текстовый ответ.");
                }
                case "number" -> {
                    session.draftQuestion.type = QuestionType.NUMBER_INPUT;
                    session.state = SessionState.WAITING_CORRECT_NUMBER_ANSWER;
                    sendInputPrompt(chatId, "Введи правильный числовой ответ.");
                }
            }
            answerCallback(callback.getId(), "Тип вопроса выбран.");
            return;
        }
        if (data.startsWith("optcount:")) {
            try {
                int count = Integer.parseInt(data.substring(9));
                session.expectedOptionsCount = count;
                session.currentOptionIndex = 0;
                session.draftQuestion.options.clear();
                session.state = SessionState.WAITING_OPTION_TEXT;
                sendInputPrompt(chatId, "Введи текст варианта 1.");
                answerCallback(callback.getId(), "Количество вариантов выбрано.");
            } catch (NumberFormatException e) {
                answerCallback(callback.getId(), "Некорректное количество вариантов.");
            }
            return;
        }
        if (data.startsWith("correctopt:")) {
            try {
                int selected = Integer.parseInt(data.substring(11));
                session.draftQuestion.correctOptionIndex = selected - 1;
                askAddPhoto(chatId);
                answerCallback(callback.getId(), "Правильный вариант выбран.");
            } catch (NumberFormatException e) {
                answerCallback(callback.getId(), "Некорректный номер варианта.");
            }
            return;
        }
        if (data.equals("photo:yes")) {
            session.state = SessionState.WAITING_QUESTION_PHOTO;
            sendInputPromptWithMenu(chatId, "Пришли фотографию одним сообщением.", MenuFactory.skipPhotoMenu());
            answerCallback(callback.getId(), "Жду фото.");
            return;
        }
        if (data.equals("photo:no")) {
            completeQuestion(chatId);
            answerCallback(callback.getId(), "Фото пропущено.");
            return;
        }
        if (data.startsWith("play:")) {
            startTestByCode(chatId, userName, data.substring(5));
            answerCallback(callback.getId(), "Запускаем тест.");
            return;
        }
        if (data.startsWith("answer:")) {
            handleAnswerCallback(callback);
            return;
        }
        if (data.startsWith("myopen:")) {
            String testId = data.substring(7);
            openMyTest(chatId, testId);
            answerCallback(callback.getId(), "Открываем тест.");
            return;
        }
        if (data.startsWith("results:")) {
            showResults(chatId, data.substring(8));
            answerCallback(callback.getId(), "Открываем результаты.");
            return;
        }
        if (data.startsWith("result:")) {
            String[] parts = data.split(":");
            if (parts.length == 3) {
                showResultDetails(chatId, parts[1], parts[2]);
                answerCallback(callback.getId(), "Открываем прохождение.");
            }
            return;
        }
        if (data.startsWith("share:")) {
            String testId = data.substring(6);
            TestData test = botService.getTestById(testId);
            if (test == null) {
                answerCallback(callback.getId(), "Тест не найден.");
                return;
            }
            String link = shareLink(test);
            String playableCode = test.isPublicVisible() ? test.testId : test.accessCode;
            sendText(chatId,
                    "Код запуска: " + playableCode + "\n" + (link == null ? "Ссылка недоступна" : "Ссылка: " + link),
                    MenuFactory.mainMenu(role));
            answerCallback(callback.getId(), "Отправил ссылку.");
            return;
        }
        if (data.startsWith("edit_title:")) {
            session.selectedTestId = data.substring(11);
            session.state = SessionState.WAITING_EDIT_TITLE;
            sendInputPrompt(chatId, "Введи новое название теста.");
            answerCallback(callback.getId(), "Редактирование названия.");
            return;
        }
        if (data.startsWith("edit_desc:")) {
            session.selectedTestId = data.substring(10);
            session.state = SessionState.WAITING_EDIT_DESCRIPTION;
            sendInputPrompt(chatId, "Введи новое описание теста.");
            answerCallback(callback.getId(), "Редактирование описания.");
            return;
        }
        if (data.startsWith("toggle_policy:")) {
            String testId = data.substring(14);
            botService.toggleShowCorrectAnswerImmediately(testId);
            openMyTest(chatId, testId);
            answerCallback(callback.getId(), "Настройка обновлена.");
            return;
        }
        if (data.startsWith("toggle_access:")) {
            String testId = data.substring(14);
            botService.toggleTestAccess(testId, chatId);
            openMyTest(chatId, testId);
            answerCallback(callback.getId(), "Доступ обновлён.");
            return;
        }
        if (data.startsWith("submit:")) {
            String testId = data.substring(7);
            botService.sendToModeration(testId);
            openMyTest(chatId, testId);
            answerCallback(callback.getId(), "Тест отправлен на модерацию.");
            return;
        }
        if (data.startsWith("delete:")) {
            String testId = data.substring(7);
            botService.deleteTest(testId);
            showMyTests(chatId);
            answerCallback(callback.getId(), "Тест удалён.");
            return;
        }
        if (data.startsWith("mod_open:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            String testId = data.substring(9);
            openModerationItem(chatId, testId);
            answerCallback(callback.getId(), "Открываем тест.");
            return;
        }
        if (data.startsWith("mod_approve:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            String testId = data.substring(12);
            botService.approveTest(testId);
            showModerationQueue(chatId);
            answerCallback(callback.getId(), "Тест одобрен.");
            return;
        }
        if (data.startsWith("mod_reject:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            String testId = data.substring(11);
            botService.rejectTest(testId);
            showModerationQueue(chatId);
            answerCallback(callback.getId(), "Тест отклонён.");
            return;
        }
        if (data.equals("admin:list_roles")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showRolesList(chatId);
            answerCallback(callback.getId(), "Показываю роли.");
            return;
        }
        if (data.equals("admin:add_mod")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_ADMIN_ADD_MODERATOR;
            sendInputPrompt(chatId, "Введи Telegram ID пользователя, которого нужно сделать модератором.");
            answerCallback(callback.getId(), "Жду ID.");
            return;
        }
        if (data.equals("admin:add_admin")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_ADMIN_ADD_ADMIN;
            sendInputPrompt(chatId, "Введи Telegram ID пользователя, которого нужно сделать администратором.");
            answerCallback(callback.getId(), "Жду ID.");
            return;
        }
        if (data.equals("admin:remove_role")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_ADMIN_REMOVE_ROLE;
            sendInputPrompt(chatId, "Введи Telegram ID пользователя, у которого нужно снять специальную роль.");
            answerCallback(callback.getId(), "Жду ID.");
            return;
        }

        answerCallback(callback.getId(), "Неизвестное действие.");
    }

    private void handleAnswerCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":");
        long chatId = callback.getMessage().getChatId();
        if (parts.length != 4) {
            answerCallback(callback.getId(), "Некорректный ответ.");
            return;
        }
        String testId = parts[1];
        int questionIndex = Integer.parseInt(parts[2]);
        int selectedIndex = Integer.parseInt(parts[3]);
        QuizRuntime runtime = quizSessions.get(chatId);
        TestData test = botService.getTestById(testId);
        if (runtime == null || test == null || !runtime.testId.equals(testId) || runtime.questionIndex != questionIndex) {
            answerCallback(callback.getId(), "Этот вопрос уже обработан.");
            return;
        }
        Question question = test.questions.get(questionIndex);
        boolean correct = selectedIndex == question.correctOptionIndex;

        QuestionAnswerDetail detail = new QuestionAnswerDetail();
        detail.questionText = question.text;
        detail.questionType = question.type;
        detail.userAnswer = question.options.get(selectedIndex);
        detail.correctAnswer = question.options.get(question.correctOptionIndex);
        detail.correct = correct;
        runtime.details.add(detail);
        if (correct) {
            runtime.score++;
        }

        if (Boolean.TRUE.equals(test.showCorrectAnswerImmediately)) {
            editAnsweredQuestion(callback, test, questionIndex, selectedIndex, true);
        } else {
            editAnsweredQuestion(callback, test, questionIndex, selectedIndex, false);
        }
        answerCallback(callback.getId(), correct ? "Верно!" : "Ответ принят.");

        runtime.questionIndex++;
        if (runtime.questionIndex < test.questions.size()) {
            sendQuestion(chatId, test, runtime);
        } else {
            finishQuiz(chatId, callback.getFrom(), test, runtime);
        }
    }

    private void editAnsweredQuestion(CallbackQuery callback, TestData test, int questionIndex, int selectedIndex, boolean revealCorrect) {
        Question question = test.questions.get(questionIndex);
        StringBuilder sb = new StringBuilder();
        sb.append("Тест: ").append(test.title)
                .append("\nВопрос ").append(questionIndex + 1).append("/").append(test.questions.size())
                .append("\n\n")
                .append(question.text)
                .append("\n\n");
        for (int i = 0; i < question.options.size(); i++) {
            String prefix = (i + 1) + ". ";
            if (revealCorrect) {
                if (i == question.correctOptionIndex) {
                    prefix = "✅ " + prefix;
                } else if (i == selectedIndex) {
                    prefix = "❌ " + prefix;
                }
            } else if (i == selectedIndex) {
                prefix = "☑️ " + prefix;
            }
            sb.append(prefix).append(question.options.get(i)).append("\n");
        }

        EditMessageText edit = EditMessageText.builder()
                .chatId(callback.getMessage().getChatId())
                .messageId(callback.getMessage().getMessageId())
                .text(TextUtils.trimTelegramText(sb.toString()))
                .build();
        try {
            telegramClient.execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void finishQuiz(long chatId, User user, TestData test, QuizRuntime runtime) {
        quizSessions.remove(chatId);
        TestResult saved = botService.appendResult(test.testId,
                user == null ? chatId : user.getId(),
                displayName(user),
                runtime.score,
                test.questions.size(),
                runtime.details);

        StringBuilder sb = new StringBuilder();
        sb.append("Тест завершён.\n\n")
                .append("Название: ").append(test.title).append("\n")
                .append("Результат: ").append(saved.score).append("/").append(saved.total).append("\n")
                .append("Процент: ").append(saved.getPercentText()).append("\n");
        sendText(chatId, sb.toString(), MenuFactory.mainMenu(botService.getRole(chatId)));
    }

    private void showResults(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null || test.creatorId != chatId) {
            sendText(chatId, "Нет доступа к результатам этого теста.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        List<TestResult> results = botService.getResults(testId);
        if (results.isEmpty()) {
            sendText(chatId, "Этот тест ещё никто не проходил.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Результаты теста\n\n")
                .append(test.title).append("\n\n");
        for (TestResult result : results) {
            sb.append(result.userName)
                    .append(" — ")
                    .append(result.score).append("/").append(result.total)
                    .append(" (").append(result.getPercentText()).append(")")
                    .append(" — ").append(result.getCompletedAtText())
                    .append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.resultsList(testId, results));
    }

    private void showResultDetails(long chatId, String testId, String resultId) {
        TestData test = botService.getTestById(testId);
        if (test == null || test.creatorId != chatId) {
            sendText(chatId, "Нет доступа к деталям этого прохождения.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        TestResult result = botService.getResultById(testId, resultId);
        if (result == null) {
            sendText(chatId, "Прохождение не найдено.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Детали прохождения\n\n")
                .append("Тест: ").append(test.title).append("\n")
                .append("Пользователь: ").append(result.userName).append("\n")
                .append("Результат: ").append(result.score).append("/").append(result.total)
                .append(" (").append(result.getPercentText()).append(")\n")
                .append("Дата: ").append(result.getCompletedAtText()).append("\n\n");
        for (int i = 0; i < result.details.size(); i++) {
            QuestionAnswerDetail detail = result.details.get(i);
            sb.append(i + 1).append(". ").append(detail.questionText).append("\n")
                    .append(detail.correct ? "✅ Верно\n" : "❌ Неверно\n")
                    .append("Ответ пользователя: ").append(detail.userAnswer).append("\n")
                    .append("Правильный ответ: ").append(detail.correctAnswer).append("\n\n");
        }
        sendText(chatId, TextUtils.trimTelegramText(sb.toString()), MenuFactory.mainMenu(botService.getRole(chatId)));
    }

    private void openModerationItem(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null) {
            sendText(chatId, "Тест не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Тест на модерации\n\n")
                .append("Название: ").append(test.title).append("\n")
                .append("Автор: ").append(test.creatorName).append("\n")
                .append("Описание: ").append(test.description == null ? "-" : test.description).append("\n")
                .append("Вопросов: ").append(test.questions.size()).append("\n")
                .append("Показ ответа сразу: ").append(Boolean.TRUE.equals(test.showCorrectAnswerImmediately) ? "Да" : "Нет").append("\n");
        sendInline(chatId, sb.toString(), MenuFactory.moderationActions(testId));
    }

    private String shareLink(TestData test) {
        if (botUsername == null || botUsername.isBlank()) {
            return null;
        }
        String username = botUsername.startsWith("@") ? botUsername.substring(1) : botUsername;
        String playableCode = test.isPublicVisible() ? test.testId : test.accessCode;
        return "https://t.me/" + username + "?start=test_" + playableCode;
    }

    private String statusText(PublicationStatus status) {
        return switch (status) {
            case PRIVATE -> "Приватный";
            case PENDING_MODERATION -> "На модерации";
            case APPROVED -> "Опубликован";
            case REJECTED -> "Отклонён";
        };
    }

    private String displayName(User user) {
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

    private void sendText(long chatId, String text, ReplyKeyboard keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendInline(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendInputPrompt(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(MenuFactory.compactReplyMenu())
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendInputPromptWithMenu(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendPhoto(long chatId, String photoFileId, String caption, Object keyboard) {
        SendPhoto.SendPhotoBuilder<?, ?> builder = SendPhoto.builder()
                .chatId(chatId)
                .photo(new InputFile(photoFileId))
                .caption(TextUtils.trimCaption(caption));

        if (keyboard instanceof InlineKeyboardMarkup inlineKeyboardMarkup) {
            builder.replyMarkup(inlineKeyboardMarkup);
        } else if (keyboard instanceof ReplyKeyboard replyKeyboard) {
            builder.replyMarkup(replyKeyboard);
        }

        try {
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void answerCallback(String callbackId, String text) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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

    private static class QuizRuntime {
        String testId;
        String userName;
        int questionIndex = 0;
        int score = 0;
        List<QuestionAnswerDetail> details = new ArrayList<>();
    }
}
