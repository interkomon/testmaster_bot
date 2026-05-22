package main.testmasterbot.bot;

import main.testmasterbot.model.*;
import main.testmasterbot.service.BotService;
import main.testmasterbot.state.SessionState;
import main.testmasterbot.state.UserSession;
import main.testmasterbot.util.TextUtils;
import main.testmasterbot.util.SimpleXlsxBuilder;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TestMasterBot implements LongPollingSingleThreadUpdateConsumer {

    private static final int MAX_TEST_TITLE_LENGTH = 80;
    private static final int MAX_TEST_DESCRIPTION_LENGTH = 500;
    private static final int MAX_QUESTION_TEXT_LENGTH = 700;
    private static final int MAX_OPTION_TEXT_LENGTH = 180;

    private final TelegramClient telegramClient;
    private final BotService botService;
    private final String botUsername;

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, QuizRuntime> quizSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService quizTimerExecutor = Executors.newSingleThreadScheduledExecutor();

    public TestMasterBot(String botToken, BotService botService) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.botService = botService;
        this.botUsername = System.getenv("TELEGRAM_BOT_USERNAME");
        this.quizTimerExecutor.scheduleAtFixedRate(this::tickQuizTimers, 1, 5, TimeUnit.SECONDS);
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

        botService.rememberUser(user);

        if (quizSessions.containsKey(chatId)) {
            QuizRuntime runtime = quizSessions.get(chatId);
            if (text.equals("⛔ Прервать тест") || text.equalsIgnoreCase("/stop")) {
                abortQuiz(chatId, user, "Прерван пользователем");
                return;
            }
            if (text.equals("🏠 Меню") || text.equals("✋ Отмена") || text.equals("❓ Помощь") || text.equalsIgnoreCase("/help")) {
                sendRemoveKeyboard(chatId, "Сейчас идёт прохождение теста. Меню скрыто до завершения.");
                sendInline(chatId, "Можно только ответить на вопрос или нажать «⛔ Прервать тест».", MenuFactory.quizActionMenu(timerLabel(botService.getTestById(runtime.testId), runtime)));
                return;
            }
            if (runtime != null && runtime.awaitingConfirmation) {
                sendInline(chatId, "Ответы приняты. Выбери: завершить и сохранить или исправить ответы.", MenuFactory.quizReviewMenu());
                return;
            }
            if (handleQuizTextIfNeeded(chatId, user, text)) {
                return;
            }
            if (quizSessions.containsKey(chatId)) {
                QuizRuntime active = quizSessions.get(chatId);
                sendInline(chatId,
                        "Сейчас идёт вопрос с вариантами. Выбери ответ кнопкой под карточкой вопроса.",
                        MenuFactory.quizActionMenu(timerLabel(botService.getTestById(active.testId), active)));
                return;
            }
        }

        if (text.startsWith("/start")) {
            handleStart(chatId, text, role, userName);
            return;
        }
        if (text.equalsIgnoreCase("/help") || text.equals("❓ Помощь")) {
            sendHelp(chatId, role);
            return;
        }
        if (text.equals("📨 Обращения")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                sendText(chatId, "У тебя нет доступа к обращениям.", MenuFactory.mainMenu(role));
                return;
            }
            showSupportRequests(chatId);
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

        if (text.equals("⛔ Прервать тест")) {
            if (quizSessions.containsKey(chatId)) {
                quizSessions.remove(chatId);
                sendText(chatId, "Прохождение теста прервано.", MenuFactory.mainMenu(role));
                return;
            }
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
            if (botService.isCreationBlocked(chatId)) {
                UserRestriction restriction = botService.getRestriction(chatId);
                String until = restriction == null || restriction.blockedUntil == null
                        ? "не указано"
                        : restriction.blockedUntil.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                sendText(chatId,
                        "Создание тестов временно запрещено.\n" +
                                "До: " + until + "\n" +
                                "Причина: " + (restriction == null || restriction.reason == null ? "-" : restriction.reason),
                        MenuFactory.mainMenu(role));
                return;
            }
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

        if (text.equals("📈 Статистика")) {
            if (!botService.isAdmin(chatId)) {
                sendText(chatId, "У тебя нет доступа к статистике.", MenuFactory.mainMenu(role));
                return;
            }
            showAdminStats(chatId);
            return;
        }

        if (text.equals("🚫 Блокировки")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                sendText(chatId, "У тебя нет доступа к блокировкам.", MenuFactory.mainMenu(role));
                return;
            }
            showRestrictionPanel(chatId);
            return;
        }

        switch (session.state) {
            case WAITING_TEST_TITLE -> handleTestTitle(chatId, text);
            case WAITING_TEST_DESCRIPTION -> handleTestDescription(chatId, text);
            case WAITING_CUSTOM_TEST_TIME -> handleCustomTestTime(chatId, text);
            case WAITING_EDIT_CUSTOM_TEST_TIME -> handleEditCustomTestTime(chatId, text);
            case WAITING_QUESTION_TEXT -> handleQuestionText(chatId, text);
            case WAITING_OPTION_TEXT -> handleOptionText(chatId, text);
            case WAITING_CORRECT_TEXT_ANSWER -> handleCorrectText(chatId, text);
            case WAITING_CORRECT_NUMBER_ANSWER -> handleCorrectNumber(chatId, text);
            case WAITING_QUESTION_PHOTO -> sendInputPromptWithMenu(chatId, "Пришли фото одним сообщением или нажми кнопку «Без фото» ниже.", MenuFactory.skipPhotoMenu());
            case WAITING_EDIT_TITLE -> finishEditTitle(chatId, text);
            case WAITING_EDIT_DESCRIPTION -> finishEditDescription(chatId, text);
            case WAITING_ADMIN_EDIT_TITLE -> finishAdminEditTitle(chatId, text);
            case WAITING_ADMIN_EDIT_DESCRIPTION -> finishAdminEditDescription(chatId, text);
            case WAITING_ADMIN_ADD_MODERATOR -> finishSetRole(chatId, text, Role.MODERATOR);
            case WAITING_ADMIN_ADD_ADMIN -> finishSetRole(chatId, text, Role.ADMIN);
            case WAITING_ADMIN_REMOVE_ROLE -> finishRemoveRole(chatId, text);
            case WAITING_MOD_BLOCK_USER -> finishBlockUserStep(chatId, text);
            case WAITING_MOD_BLOCK_DAYS -> finishBlockDaysStep(chatId, text);
            case WAITING_MOD_BLOCK_REASON -> finishBlockReasonStep(chatId, text);
            case WAITING_MOD_BLOCK_FOREVER_USER -> finishBlockForeverUser(chatId, text);
            case WAITING_MOD_UNBLOCK_USER -> finishUnblockUser(chatId, text);
            case WAITING_SUPPORT_TEXT -> finishSupportText(chatId, userName, text);
            case WAITING_SUPPORT_ANSWER -> finishSupportAnswer(chatId, userName, text);
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
        sb.append("\nЗапустить тест можно через команду /play CODE");
        return sb.toString();
    }
    private String roleToText(Role role) {
        if (role == Role.ADMIN) {
            return "администратор";
        }

        if (role == Role.MODERATOR) {
            return "модератор";
        }

        return "пользователь";
    }

    private void sendHelp(long chatId, Role role) {
        SupportRequest latestAnswer = botService.getLatestAnsweredSupportForUser(chatId);
        StringBuilder sb = new StringBuilder();
        sb.append("❓ Справка\n\n")

                .append("Твоя роль: ").append(roleToText(role)).append("\n\n")


                .append("Как создать тест:\n")
                .append("Нажми «Создать тест», введи название, описание, время, настройку показа ответов и добавь вопросы.\n\n")

                .append("Как пройти тест:\n")
                .append("Тест можно запустить через список опубликованных тестов, по ссылке или командой:\n")
                .append("/play КОД ТЕСТА\n\n")


                .append("Как посмотреть результаты прохождений:\n")
                .append("В разделе «Мои тесты» можно открыть тест, посмотреть прохождения и выгрузить результаты в Excel.\n\n")

                .append("Обращения:\n")
                .append("Если есть вопрос, нашли баг или ошибку можете оставить обращение, модератор или администратор ответит как можно скорее.\n");

        if (role == Role.MODERATOR || role == Role.ADMIN) {
            sb.append("\nДля модератора:\n")
                    .append("Доступна очередь модерации, просмотр тестов, одобрение, отклонение, удаление и блокировка создания тестов.\n");
        }

        if (role == Role.ADMIN) {
            sb.append("\nДля администратора:\n")
                    .append("Доступны пользователи, роли, все тесты, статистика, Excel-отчёты и управление обращениями.\n");
        }
        if (latestAnswer != null) {
            sb.append("\n📩 У тебя есть ответ на обращение. Нажми кнопку ниже, чтобы посмотреть его.");
        }
        sendInline(chatId, sb.toString(), MenuFactory.helpMenu(latestAnswer != null));
    }

    private void handleTestTitle(long chatId, String text) {
        if (text.isBlank()) {
            sendInputPrompt(chatId, "Название не должно быть пустым. Введи название теста ещё раз.");
            return;
        }
        if (text.trim().length() > MAX_TEST_TITLE_LENGTH) {
            sendInputPrompt(chatId, "Название слишком длинное. Максимум " + MAX_TEST_TITLE_LENGTH + " символов.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftTest.title = text.trim();
        session.state = SessionState.WAITING_TEST_DESCRIPTION;
        sendInputPrompt(chatId, "Введи описание теста.");
    }

    private void handleTestDescription(long chatId, String text) {
        if (text.trim().length() > MAX_TEST_DESCRIPTION_LENGTH) {
            sendInputPrompt(chatId, "Описание слишком длинное. Максимум " + MAX_TEST_DESCRIPTION_LENGTH + " символов.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.draftTest.description = text.trim();
        session.state = SessionState.IDLE;
        sendInline(chatId,
                "Выбери режим показа ответов для проходящего пользователя:",
                MenuFactory.answerPolicyMenu(session.draftTest.getEffectiveAnswerRevealMode()));
    }

    private void handleCustomTestTime(long chatId, String text) {
        Integer seconds = parseTimeSeconds(text);
        if (seconds == null) {
            sendInputPrompt(chatId, "Введи время числом в минутах или в формате 10м / 30с. Например: 15 или 90с.");
            return;
        }
        UserSession session = sessions.get(chatId);
        if (session.draftTest != null) {
            session.draftTest.totalTimeLimitSeconds = seconds <= 0 ? null : seconds;
        }
        session.state = SessionState.IDLE;
        startFirstQuestion(chatId);
    }

    private void handleEditCustomTestTime(long chatId, String text) {
        Integer seconds = parseTimeSeconds(text);
        if (seconds == null) {
            sendInputPrompt(chatId, "Введи время числом в минутах или в формате 10м / 30с. Например: 20 или 120с.");
            return;
        }
        UserSession session = sessions.get(chatId);
        if (session.selectedTestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Тест не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        botService.updateTotalTimeLimit(session.selectedTestId, seconds <= 0 ? null : seconds);
        session.state = SessionState.IDLE;
        openMyTestForActor(chatId, session.selectedTestId);
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
        if (text.trim().length() > MAX_QUESTION_TEXT_LENGTH) {
            sendInputPrompt(chatId, "Вопрос слишком длинный. Максимум " + MAX_QUESTION_TEXT_LENGTH + " символов.");
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
        if (text.trim().length() > MAX_OPTION_TEXT_LENGTH) {
            sendInputPrompt(chatId, "Вариант слишком длинный. Максимум " + MAX_OPTION_TEXT_LENGTH + " символов.");
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

    private String buildDraftSummary(TestData draft) {
        StringBuilder sb = new StringBuilder("Проверь тест перед завершением\n\n");
        sb.append("Название: ").append(draft.title).append("\n")
                .append("Описание: ").append(draft.description == null || draft.description.isBlank() ? "-" : draft.description).append("\n")
                .append("Показ ответов: ").append(revealModeText(draft.getEffectiveAnswerRevealMode())).append("\n")
                .append("Время на тест: ").append(timeText(draft.totalTimeLimitSeconds)).append("\n")
                .append("Вопросов: ").append(draft.questions.size()).append("\n\n");
        for (int i = 0; i < draft.questions.size(); i++) {
            Question question = draft.questions.get(i);
            sb.append(i + 1).append(") ").append(TextUtils.shorten(question.text, 80))
                    .append(" — ").append(questionTypeText(question.type))
                    .append("\n");
        }
        sb.append("\nЕсли всё правильно — подтверди. Если нужно исправить — добавь новый вопрос или вернись в меню.");
        return sb.toString();
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
        if (text.isBlank() || text.trim().length() > MAX_TEST_TITLE_LENGTH) {
            sendInputPrompt(chatId, "Название не должно быть пустым и должно быть не длиннее " + MAX_TEST_TITLE_LENGTH + " символов.");
            return;
        }
        botService.updateTestTitle(session.selectedTestId, text.trim());
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
        if (text.trim().length() > MAX_TEST_DESCRIPTION_LENGTH) {
            sendInputPrompt(chatId, "Описание слишком длинное. Максимум " + MAX_TEST_DESCRIPTION_LENGTH + " символов.");
            return;
        }
        botService.updateTestDescription(session.selectedTestId, text.trim());
        session.state = SessionState.IDLE;
        openMyTest(chatId, session.selectedTestId);
    }

    private void finishAdminEditTitle(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        if (!botService.isAdmin(chatId) || session.selectedTestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Нет доступа или тест не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        if (text.isBlank() || text.trim().length() > MAX_TEST_TITLE_LENGTH) {
            sendInputPrompt(chatId, "Название не должно быть пустым и должно быть не длиннее " + MAX_TEST_TITLE_LENGTH + " символов.");
            return;
        }
        botService.updateTestTitle(session.selectedTestId, text.trim());
        session.state = SessionState.IDLE;
        openAdminTest(chatId, session.selectedTestId);
    }

    private void finishAdminEditDescription(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        if (!botService.isAdmin(chatId) || session.selectedTestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Нет доступа или тест не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        if (text.trim().length() > MAX_TEST_DESCRIPTION_LENGTH) {
            sendInputPrompt(chatId, "Описание слишком длинное. Максимум " + MAX_TEST_DESCRIPTION_LENGTH + " символов.");
            return;
        }
        botService.updateTestDescription(session.selectedTestId, text.trim());
        session.state = SessionState.IDLE;
        openAdminTest(chatId, session.selectedTestId);
    }

    private void finishSetRole(long chatId, String text, Role roleToSet) {
        Long userId = botService.resolveKnownUserId(text);
        if (userId == null) {
            sendInputPrompt(chatId, "Пользователь не найден. Введи @username известного пользователя или числовой Telegram ID.");
            return;
        }
        botService.setRole(userId, roleToSet);
        sessions.get(chatId).state = SessionState.IDLE;
        String label = botService.displayUser(userId);
        sendText(chatId,
                "Роль обновлена.\nПользователь " + label + " теперь " + roleToSet + ".",
                MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(userId,
                "Тебе назначена роль: " + roleToSet + ".\nОткрой меню, чтобы увидеть новые возможности.",
                MenuFactory.mainMenu(botService.getRole(userId)));
    }

    private void finishRemoveRole(long chatId, String text) {
        Long userId = botService.resolveKnownUserId(text);
        if (userId == null) {
            sendInputPrompt(chatId, "Пользователь не найден. Введи @username известного пользователя или числовой Telegram ID.");
            return;
        }
        botService.removeRole(userId);
        sessions.get(chatId).state = SessionState.IDLE;
        sendText(chatId,
                "Специальная роль снята с пользователя " + botService.displayUser(userId) + ".",
                MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(userId,
                "С тебя снята специальная роль.",
                MenuFactory.mainMenu(botService.getRole(userId)));
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
                    .append("\nСтатус: ").append(statusText(test.status))
                    .append("\nПоказ ответов: ").append(revealModeText(test.getEffectiveAnswerRevealMode()))
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
        String playableCode = test.testId;
        StringBuilder sb = new StringBuilder();
        sb.append("Тест\n\n")
                .append("Название: ").append(test.title).append("\n")
                .append("Описание: ").append(test.description == null || test.description.isBlank() ? "-" : test.description).append("\n")
                .append("Статус: ").append(statusText(test.status)).append("\n")
                .append("Показ ответов: ").append(revealModeText(test.getEffectiveAnswerRevealMode())).append("\n")
                .append("Время на тест: ").append(timeText(test.totalTimeLimitSeconds)).append("\n")
                .append("Код запуска: ").append(playableCode).append("\n")
                .append("Вопросов: ").append(test.questions.size()).append("\n")
                .append("Прохождений: ").append(test.results.size()).append("\n")
                .append("Средний результат: ").append(TextUtils.formatPercent(test.averagePercent())).append("%\n");
        if (shareLink != null) {
            sb.append("Ссылка: ").append(shareLink).append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.myTestActions(test));
    }

    private void openMyTestForActor(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null) {
            sendText(chatId, "Тест не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        if (test.creatorId == chatId) {
            openMyTest(chatId, testId);
            return;
        }
        if (botService.isAdmin(chatId)) {
            openAdminTest(chatId, testId);
            return;
        }
        sendText(chatId, "Нет доступа к этому тесту.", MenuFactory.mainMenu(botService.getRole(chatId)));
    }

    private void openAdminTest(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null) {
            sendText(chatId, "Тест не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        sessions.computeIfAbsent(chatId, id -> new UserSession()).selectedTestId = testId;
        String shareLink = shareLink(test);
        String playableCode = test.testId;
        StringBuilder sb = new StringBuilder();
        sb.append("Админ: управление тестом\n\n")
                .append("Название: ").append(test.title).append("\n")
                .append("Автор: ").append(botService.displayUser(test.creatorId)).append("\n")
                .append("Описание: ").append(test.description == null || test.description.isBlank() ? "-" : test.description).append("\n")
                .append("Статус: ").append(statusText(test.status)).append("\n")
                .append("Показ ответов: ").append(revealModeText(test.getEffectiveAnswerRevealMode())).append("\n")
                .append("Время на тест: ").append(timeText(test.totalTimeLimitSeconds)).append("\n")
                .append("Код запуска: ").append(playableCode).append("\n")
                .append("Вопросов: ").append(test.questions.size()).append("\n")
                .append("Прохождений: ").append(test.results.size()).append("\n")
                .append("Средний результат: ").append(TextUtils.formatPercent(test.averagePercent())).append("%\n");
        if (shareLink != null) {
            sb.append("Ссылка: ").append(shareLink).append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.adminTestActions(test));
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
                sb.append(botService.displayUser(entry.getKey())).append(" — ").append(entry.getValue()).append("\n");
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
        runtime.startedAtMillis = System.currentTimeMillis();
        runtime.questionStartedAtMillis = System.currentTimeMillis();
        quizSessions.put(chatId, runtime);
        sendRemoveKeyboard(chatId, "▶ Тест начался. Меню скрыто до завершения теста.");
        sendQuestion(chatId, test, runtime);
    }

    private void sendQuestion(long chatId, TestData test, QuizRuntime runtime) {
        runtime.questionStartedAtMillis = System.currentTimeMillis();
        Question question = test.questions.get(runtime.questionIndex);
        StringBuilder sb = new StringBuilder();
        sb.append("📘 ").append(test.title).append("\n")
                .append("━━━━━━━━━━━━━━\n")
                .append("Вопрос ").append(runtime.questionIndex + 1).append(" из ").append(test.questions.size()).append("\n")
                .append("Тип: ").append(questionTypeText(question.type)).append("\n");
        if (test.totalTimeLimitSeconds != null && test.totalTimeLimitSeconds > 0) {
            sb.append("⏳ Время на тест: ").append(timeText(test.totalTimeLimitSeconds)).append("\n");
        }
        sb.append("\n❓ ").append(question.text).append("\n\n");

        if (question.type == QuestionType.SINGLE_CHOICE) {
            for (int i = 0; i < question.options.size(); i++) {
                sb.append(i + 1).append(") ").append(question.options.get(i)).append("\n");
            }
            Message sent;
            String timer = timerLabel(test, runtime);
            if (question.photoFileId != null && !question.photoFileId.isBlank()) {
                sent = sendPhoto(chatId, question.photoFileId, sb.toString(), MenuFactory.answerButtons(test.testId, runtime.questionIndex, question.options.size(), timer));
            } else {
                sent = sendInline(chatId, sb.toString(), MenuFactory.answerButtons(test.testId, runtime.questionIndex, question.options.size(), timer));
            }
            rememberQuestionMessage(runtime, chatId, sent, timer);
            return;
        }

        if (question.type == QuestionType.TEXT_INPUT) {
            sb.append("✍️ Ответь текстом одним сообщением.");
        } else {
            sb.append("🔢 Ответь числом одним сообщением.");
        }

        Message sent;
        String timer = timerLabel(test, runtime);
        if (question.photoFileId != null && !question.photoFileId.isBlank()) {
            sent = sendPhoto(chatId, question.photoFileId, sb.toString(), MenuFactory.quizActionMenu(timer));
        } else {
            sent = sendInputPromptWithMenu(chatId, sb.toString(), MenuFactory.quizActionMenu(timer));
        }
        rememberQuestionMessage(runtime, chatId, sent, timer);
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

        if (checkTotalTimeout(chatId, user, test, runtime)) {
            return true;
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
                sendInputPromptWithMenu(chatId, "Нужно ввести число.", MenuFactory.quizActionMenu());
                return true;
            }
            detail.userAnswer = TextUtils.formatNumber(value);
            detail.correctAnswer = TextUtils.formatNumber(question.correctNumberAnswer);
            correct = Math.abs(value - question.correctNumberAnswer) < 0.000001;
        }
        detail.correct = correct;

        if (runtime.editingQuestionIndex >= 0) {
            replaceAnswerDetail(runtime, runtime.editingQuestionIndex, detail);
            runtime.editingQuestionIndex = -1;
            runtime.questionIndex = test.questions.size();
            runtime.awaitingConfirmation = true;

            AnswerRevealMode mode = test.getEffectiveAnswerRevealMode();
            if (mode == AnswerRevealMode.IMMEDIATE) {
                if (correct) {
                    sendInline(chatId, "✅ Ответ изменён: верно.", MenuFactory.quizReviewMenu(timerLabel(test, runtime)));
                } else {
                    sendInline(chatId, "❌ Ответ изменён.\nПравильный ответ: " + detail.correctAnswer, MenuFactory.quizReviewMenu(timerLabel(test, runtime)));
                }
            } else {
                sendInline(chatId, "☑️ Ответ изменён.", MenuFactory.quizReviewMenu(timerLabel(test, runtime)));
            }
            showQuizReview(chatId, test, runtime);
            return true;
        }

        runtime.details.add(detail);
        if (correct) {
            runtime.score++;
        }

        AnswerRevealMode mode = test.getEffectiveAnswerRevealMode();
        if (mode == AnswerRevealMode.IMMEDIATE) {
            if (correct) {
                sendInline(chatId, "✅ Ответ принят: верно.", MenuFactory.quizActionMenu(timerLabel(test, runtime)));
            } else {
                sendInline(chatId, "❌ Неверно.\nПравильный ответ: " + detail.correctAnswer, MenuFactory.quizActionMenu(timerLabel(test, runtime)));
            }
        } else {
            sendInline(chatId, "☑️ Ответ принят.", MenuFactory.quizActionMenu(timerLabel(test, runtime)));
        }

        runtime.questionIndex++;
        if (runtime.questionIndex < test.questions.size()) {
            sendQuestion(chatId, test, runtime);
        } else {
            showQuizReview(chatId, test, runtime);
        }
        return true;
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();
        String userName = displayName(callback.getFrom());
        Role role = botService.getRole(chatId);
        UserSession session = sessions.computeIfAbsent(chatId, id -> new UserSession());

        botService.rememberUser(callback.getFrom());

        if (data == null || data.isBlank()) {
            answerCallback(callback.getId(), "Пустое действие.");
            return;
        }

        if (data.equals("timer:none")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            answerCallback(callback.getId(), timerLabel(botService.getTestById(runtime == null ? null : runtime.testId), runtime));
            return;
        }

        if (data.equals("quiz:abort")) {
            abortQuiz(chatId, callback.getFrom(), "Прерван пользователем");
            answerCallback(callback.getId(), "Тест прерван.");
            return;
        }

        if (data.equals("quiz:finish")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            TestData test = runtime == null ? null : botService.getTestById(runtime.testId);
            if (runtime == null || test == null || !runtime.awaitingConfirmation) {
                answerCallback(callback.getId(), "Нет теста для завершения.");
                return;
            }
            if (checkTotalTimeout(chatId, callback.getFrom(), test, runtime)) {
                answerCallback(callback.getId(), "Время теста вышло.");
                return;
            }
            finishQuiz(chatId, callback.getFrom(), test, runtime);
            answerCallback(callback.getId(), "Тест сохранён.");
            return;
        }

        if (data.equals("quiz:review")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            TestData test = runtime == null ? null : botService.getTestById(runtime.testId);
            if (runtime == null || test == null) {
                answerCallback(callback.getId(), "Нет активного теста.");
                return;
            }
            showQuizAnswerReview(chatId, test, runtime);
            answerCallback(callback.getId(), "Показываю ответы.");
            return;
        }

        if (data.equals("quiz:redo")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            TestData test = runtime == null ? null : botService.getTestById(runtime.testId);
            if (runtime == null || test == null) {
                answerCallback(callback.getId(), "Нет активного теста.");
                return;
            }
            if (checkTotalTimeout(chatId, callback.getFrom(), test, runtime)) {
                answerCallback(callback.getId(), "Время теста вышло.");
                return;
            }
            showQuizCorrectionMenu(chatId, test, runtime);
            answerCallback(callback.getId(), "Выбери вопрос для исправления.");
            return;
        }

        if (data.equals("quiz:back_review")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            TestData test = runtime == null ? null : botService.getTestById(runtime.testId);
            if (runtime == null || test == null) {
                answerCallback(callback.getId(), "Нет активного теста.");
                return;
            }
            showQuizReview(chatId, test, runtime);
            answerCallback(callback.getId(), "Возвращаемся к завершению.");
            return;
        }

        if (data.startsWith("quiz:edit:")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            TestData test = runtime == null ? null : botService.getTestById(runtime.testId);
            if (runtime == null || test == null) {
                answerCallback(callback.getId(), "Нет активного теста.");
                return;
            }
            if (checkTotalTimeout(chatId, callback.getFrom(), test, runtime)) {
                answerCallback(callback.getId(), "Время теста вышло.");
                return;
            }

            try {
                int editIndex = Integer.parseInt(data.substring("quiz:edit:".length()));
                if (editIndex < 0 || editIndex >= test.questions.size()) {
                    answerCallback(callback.getId(), "Некорректный номер вопроса.");
                    return;
                }

                runtime.editingQuestionIndex = editIndex;
                runtime.questionIndex = editIndex;
                runtime.awaitingConfirmation = false;
                sendQuestion(chatId, test, runtime);
                answerCallback(callback.getId(), "Исправь ответ на выбранный вопрос.");
            } catch (NumberFormatException e) {
                answerCallback(callback.getId(), "Некорректный номер вопроса.");
            }
            return;
        }

        if (quizSessions.containsKey(chatId) && !data.startsWith("answer:") && !data.startsWith("quiz:") && !data.equals("timer:none")) {
            QuizRuntime runtime = quizSessions.get(chatId);
            answerCallback(callback.getId(), "Во время теста доступен только ответ или прерывание.");
            sendInline(chatId, "Сейчас идёт прохождение теста. Заверши его или нажми «Прервать тест».", MenuFactory.quizActionMenu(timerLabel(botService.getTestById(runtime == null ? null : runtime.testId), runtime)));
            return;
        }

        if (data.equals("menu:open")) {
            sendText(chatId, welcomeText(userName, role), MenuFactory.mainMenu(role));
            answerCallback(callback.getId(), "Меню открыто.");
            return;
        }
        if (data.equals("admin:panel")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showAdminPanel(chatId);
            answerCallback(callback.getId(), "Админ-панель.");
            return;
        }
        if (data.equals("support:new")) {
            session.state = SessionState.WAITING_SUPPORT_TEXT;
            sendInputPrompt(chatId, "Опиши проблему или баг одним сообщением. Администратор или модератор сможет ответить тебе через бота.");
            answerCallback(callback.getId(), "Жду обращение.");
            return;
        }
        if (data.equals("support:my_answer")) {
            showMyLatestSupportAnswer(chatId);
            answerCallback(callback.getId(), "Показываю ответ.");
            return;
        }
        if (data.equals("support:list")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showSupportRequests(chatId);
            answerCallback(callback.getId(), "Обращения.");
            return;
        }
        if (data.startsWith("support:open:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showSupportRequest(chatId, data.substring("support:open:".length()));
            answerCallback(callback.getId(), "Открываю обращение.");
            return;
        }
        if (data.startsWith("support:answer:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.selectedSupportRequestId = data.substring("support:answer:".length());
            session.state = SessionState.WAITING_SUPPORT_ANSWER;
            sendInputPrompt(chatId, "Введи ответ пользователю.");
            answerCallback(callback.getId(), "Жду ответ.");
            return;
        }
        if (data.startsWith("policy:")) {
            if (session.draftTest != null) {
                String modeValue = data.substring("policy:".length());
                session.draftTest.answerRevealMode = switch (modeValue) {
                    case "immediate" -> AnswerRevealMode.IMMEDIATE;
                    case "end" -> AnswerRevealMode.END_ONLY;
                    case "never" -> AnswerRevealMode.NEVER;
                    default -> AnswerRevealMode.IMMEDIATE;
                };
                session.draftTest.showCorrectAnswerImmediately = session.draftTest.answerRevealMode == AnswerRevealMode.IMMEDIATE;
                sendInline(chatId, "Выбери ограничение времени на весь тест:", MenuFactory.testTimeMenu());
            }
            answerCallback(callback.getId(), "Режим показа ответов выбран.");
            return;
        }
        if (data.startsWith("testtime:")) {
            if (session.draftTest != null) {
                String value = data.substring("testtime:".length());
                if (value.equals("custom")) {
                    session.state = SessionState.WAITING_CUSTOM_TEST_TIME;
                    sendInputPrompt(chatId, "Введи своё время на тест. Число без буквы считается минутами. Примеры: 15, 90с, 10м. Для снятия ограничения введи 0.");
                } else {
                    int seconds = Integer.parseInt(value);
                    session.draftTest.totalTimeLimitSeconds = seconds <= 0 ? null : seconds;
                    startFirstQuestion(chatId);
                }
            }
            answerCallback(callback.getId(), "Время теста выбрано.");
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

            String playableCode = saved.testId;
            StringBuilder sb = new StringBuilder();
            sb.append("Тест сохранён.\n\n")
                    .append("Название: ").append(saved.title).append("\n")
                    .append("Описание: ").append(saved.description == null ? "-" : saved.description).append("\n")
                    .append("Статус: ").append(statusText(saved.status)).append("\n")
                    .append("Показ ответов: ").append(revealModeText(saved.getEffectiveAnswerRevealMode())).append("\n")
                    .append("Время на тест: ").append(timeText(saved.totalTimeLimitSeconds)).append("\n")
                    .append("Код запуска: ").append(playableCode).append("\n");
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
        if (data.startsWith("qtime:")) {
            // Ограничение времени на отдельный вопрос отключено.
            askAddPhoto(chatId);
            answerCallback(callback.getId(), "Время на отдельный вопрос больше не настраивается.");
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
            openMyTestForActor(chatId, testId);
            answerCallback(callback.getId(), "Открываем тест.");
            return;
        }
        if (data.startsWith("results:")) {
            showResults(chatId, data.substring(8));
            answerCallback(callback.getId(), "Открываем результаты.");
            return;
        }
        if (data.startsWith("export_results:")) {
            String testId = data.substring("export_results:".length());
            TestData test = botService.getTestById(testId);
            if (test == null || (test.creatorId != chatId && !botService.isAdmin(chatId))) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            exportTestResultsExcel(chatId, testId);
            answerCallback(callback.getId(), "Готовлю Excel.");
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
        if (data.startsWith("export_test:")) {
            String testId = data.substring("export_test:".length());
            TestData test = botService.getTestById(testId);
            if (test == null || (test.creatorId != chatId && !botService.isAdmin(chatId))) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            exportTestText(chatId, testId);
            answerCallback(callback.getId(), "Экспорт теста.");
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
            String playableCode = test.testId;
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
        if (data.startsWith("reveal_menu:")) {
            String testId = data.substring("reveal_menu:".length());
            TestData test = botService.getTestById(testId);
            if (test == null) {
                answerCallback(callback.getId(), "Тест не найден.");
                return;
            }
            sendInline(chatId, "Выбери режим показа ответов:", MenuFactory.answerRevealEditMenu(testId, test.getEffectiveAnswerRevealMode()));
            answerCallback(callback.getId(), "Настройка показа.");
            return;
        }
        if (data.startsWith("set_reveal:")) {
            String[] parts = data.split(":");
            if (parts.length == 3) {
                String testId = parts[1];
                AnswerRevealMode mode = AnswerRevealMode.valueOf(parts[2]);
                botService.updateAnswerRevealMode(testId, mode);
                openMyTestForActor(chatId, testId);
            }
            answerCallback(callback.getId(), "Настройка обновлена.");
            return;
        }
        if (data.startsWith("time_menu:")) {
            String testId = data.substring("time_menu:".length());
            sendInline(chatId, "Выбери время на весь тест:", MenuFactory.testTimeEditMenu(testId));
            answerCallback(callback.getId(), "Настройка времени.");
            return;
        }
        if (data.startsWith("set_time:")) {
            String[] parts = data.split(":");
            if (parts.length == 3) {
                String testId = parts[1];
                if (parts[2].equals("custom")) {
                    session.selectedTestId = testId;
                    session.state = SessionState.WAITING_EDIT_CUSTOM_TEST_TIME;
                    sendInputPrompt(chatId, "Введи своё время на тест. Число без буквы считается минутами. Примеры: 20, 120с, 10м. Для снятия ограничения введи 0.");
                } else {
                    int seconds = Integer.parseInt(parts[2]);
                    botService.updateTotalTimeLimit(testId, seconds <= 0 ? null : seconds);
                    openMyTestForActor(chatId, testId);
                }
            }
            answerCallback(callback.getId(), "Время обновлено.");
            return;
        }
        if (data.startsWith("toggle_policy:")) {
            String testId = data.substring(14);
            TestData test = botService.getTestById(testId);
            if (test != null) {
                sendInline(chatId, "Выбери режим показа ответов:", MenuFactory.answerRevealEditMenu(testId, test.getEffectiveAnswerRevealMode()));
            }
            answerCallback(callback.getId(), "Настройка показа.");
            return;
        }
        if (data.startsWith("toggle_access:")) {
            String testId = data.substring(14);
            botService.toggleTestAccess(testId, chatId);
            openMyTestForActor(chatId, testId);
            answerCallback(callback.getId(), "Доступ обновлён.");
            return;
        }
        if (data.startsWith("submit:")) {
            String testId = data.substring(7);
            botService.sendToModeration(testId);
            openMyTestForActor(chatId, testId);
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
            TestData approved = botService.approveTest(testId);
            if (approved != null) {
                sendText(approved.creatorId,
                        "✅ Твой тест «" + approved.title + "» одобрен и опубликован.",
                        MenuFactory.mainMenu(botService.getRole(approved.creatorId)));
            }
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
            TestData rejected = botService.rejectTest(testId);
            if (rejected != null) {
                sendText(rejected.creatorId,
                        "❌ Твой тест «" + rejected.title + "» отклонён модератором.",
                        MenuFactory.mainMenu(botService.getRole(rejected.creatorId)));
            }
            showModerationQueue(chatId);
            answerCallback(callback.getId(), "Тест отклонён.");
            return;
        }
        if (data.startsWith("mod_delete:")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            String testId = data.substring("mod_delete:".length());
            TestData test = botService.getTestById(testId);
            botService.deleteTest(testId);
            if (test != null) {
                sendText(test.creatorId,
                        "🗑 Твой тест «" + test.title + "» удалён модератором.",
                        MenuFactory.mainMenu(botService.getRole(test.creatorId)));
            }
            showModerationQueue(chatId);
            answerCallback(callback.getId(), "Тест удалён.");
            return;
        }
        if (data.equals("admin:list_users")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showUsersList(chatId);
            answerCallback(callback.getId(), "Пользователи.");
            return;
        }
        if (data.equals("admin:stats")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showAdminStats(chatId);
            answerCallback(callback.getId(), "Статистика.");
            return;
        }
        if (data.equals("admin:export_stats")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            exportStatisticsCsv(chatId);
            answerCallback(callback.getId(), "Экспорт готовится.");
            return;
        }
        if (data.equals("admin:all_tests")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showAdminAllTests(chatId);
            answerCallback(callback.getId(), "Все тесты.");
            return;
        }
        if (data.startsWith("admin_open_test:")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            openAdminTest(chatId, data.substring("admin_open_test:".length()));
            answerCallback(callback.getId(), "Открываю тест.");
            return;
        }
        if (data.startsWith("admin_edit_title:")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.selectedTestId = data.substring("admin_edit_title:".length());
            session.state = SessionState.WAITING_ADMIN_EDIT_TITLE;
            sendInputPrompt(chatId, "Введи новое название теста.");
            answerCallback(callback.getId(), "Редактирование.");
            return;
        }
        if (data.startsWith("admin_edit_desc:")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.selectedTestId = data.substring("admin_edit_desc:".length());
            session.state = SessionState.WAITING_ADMIN_EDIT_DESCRIPTION;
            sendInputPrompt(chatId, "Введи новое описание теста.");
            answerCallback(callback.getId(), "Редактирование.");
            return;
        }
        if (data.startsWith("admin_delete:")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            String testId = data.substring("admin_delete:".length());
            botService.deleteTest(testId);
            showAdminAllTests(chatId);
            answerCallback(callback.getId(), "Тест удалён.");
            return;
        }
        if (data.equals("mod:block_create")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_MOD_BLOCK_USER;
            sendInputPrompt(chatId, "Введи @username или Telegram ID пользователя для запрета создания тестов.");
            answerCallback(callback.getId(), "Жду пользователя.");
            return;
        }
        if (data.equals("mod:block_forever")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_MOD_BLOCK_FOREVER_USER;
            sendInputPrompt(chatId, "Введи @username или Telegram ID пользователя для бессрочного запрета создания тестов.");
            answerCallback(callback.getId(), "Жду пользователя.");
            return;
        }
        if (data.equals("mod:unblock_create")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_MOD_UNBLOCK_USER;
            sendInputPrompt(chatId, "Введи @username или Telegram ID пользователя для снятия запрета.");
            answerCallback(callback.getId(), "Жду пользователя.");
            return;
        }
        if (data.equals("mod:list_blocks")) {
            if (!botService.isModeratorOrAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            showRestrictionPanel(chatId);
            answerCallback(callback.getId(), "Блокировки.");
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
            sendInputPrompt(chatId, "Введи @username известного пользователя или Telegram ID, которого нужно сделать модератором.");
            answerCallback(callback.getId(), "Жду ID.");
            return;
        }
        if (data.equals("admin:add_admin")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_ADMIN_ADD_ADMIN;
            sendInputPrompt(chatId, "Введи @username известного пользователя или Telegram ID, которого нужно сделать администратором.");
            answerCallback(callback.getId(), "Жду ID.");
            return;
        }
        if (data.equals("admin:remove_role")) {
            if (!botService.isAdmin(chatId)) {
                answerCallback(callback.getId(), "Нет доступа.");
                return;
            }
            session.state = SessionState.WAITING_ADMIN_REMOVE_ROLE;
            sendInputPrompt(chatId, "Введи @username известного пользователя или Telegram ID, у которого нужно снять специальную роль.");
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
        if (checkTotalTimeout(chatId, callback.getFrom(), test, runtime)) {
            answerCallback(callback.getId(), "Время теста вышло.");
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

        boolean reveal = test.getEffectiveAnswerRevealMode() == AnswerRevealMode.IMMEDIATE;
        editAnsweredQuestion(callback, test, questionIndex, selectedIndex, reveal);

        if (runtime.editingQuestionIndex >= 0) {
            replaceAnswerDetail(runtime, runtime.editingQuestionIndex, detail);
            runtime.editingQuestionIndex = -1;
            runtime.questionIndex = test.questions.size();
            runtime.awaitingConfirmation = true;
            answerCallback(callback.getId(), test.getEffectiveAnswerRevealMode() == AnswerRevealMode.IMMEDIATE
                    ? (correct ? "Ответ изменён: верно!" : "Ответ изменён: неверно")
                    : "Ответ изменён.");
            showQuizReview(chatId, test, runtime);
            return;
        }

        runtime.details.add(detail);
        if (correct) {
            runtime.score++;
        }

        answerCallback(callback.getId(), test.getEffectiveAnswerRevealMode() == AnswerRevealMode.IMMEDIATE
                ? (correct ? "Верно!" : "Неверно")
                : "Ответ принят.");

        runtime.questionIndex++;
        if (runtime.questionIndex < test.questions.size()) {
            sendQuestion(chatId, test, runtime);
        } else {
            showQuizReview(chatId, test, runtime);
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


    private void replaceAnswerDetail(QuizRuntime runtime, int questionIndex, QuestionAnswerDetail detail) {
        while (runtime.details.size() <= questionIndex) {
            runtime.details.add(new QuestionAnswerDetail());
        }
        runtime.details.set(questionIndex, detail);
        recalculateScore(runtime);
    }

    private void recalculateScore(QuizRuntime runtime) {
        int score = 0;
        for (QuestionAnswerDetail detail : runtime.details) {
            if (detail != null && detail.correct) {
                score++;
            }
        }
        runtime.score = score;
    }

    private void showQuizReview(long chatId, TestData test, QuizRuntime runtime) {
        runtime.awaitingConfirmation = true;
        runtime.editingQuestionIndex = -1;
        runtime.questionIndex = test.questions.size();

        StringBuilder sb = new StringBuilder();
        sb.append("🧾 Ответы приняты\n")
                .append("━━━━━━━━━━━━━━\n")
                .append("📘 ").append(test.title).append("\n\n")
                .append("Время теста продолжает идти до сохранения результата.\n\n")
                .append("Выбери действие:\n")
                .append("✅ завершить и сохранить результат;\n")
                .append("🔁 выбрать вопрос и исправить ответ.");
        sendInline(chatId, sb.toString(), MenuFactory.quizReviewMenu(timerLabel(test, runtime)));
    }

    private void showQuizCorrectionMenu(long chatId, TestData test, QuizRuntime runtime) {
        runtime.awaitingConfirmation = true;
        runtime.editingQuestionIndex = -1;
        runtime.questionIndex = test.questions.size();

        StringBuilder sb = new StringBuilder();
        sb.append("🔁 Исправление ответов\n")
                .append("━━━━━━━━━━━━━━\n")
                .append("📘 ").append(test.title).append("\n\n")
                .append("Выбери номер вопроса, который нужно исправить.\n")
                .append("Текущий результат не сохраняется, пока ты не нажмёшь «Завершить и сохранить».\n")
                .append("Время теста продолжает идти.");

        sendInline(chatId, sb.toString(), MenuFactory.quizCorrectionMenu(test.questions.size(), timerLabel(test, runtime)));
    }

    private void showQuizAnswerReview(long chatId, TestData test, QuizRuntime runtime) {
        StringBuilder sb = new StringBuilder();
        sb.append("👀 Твои ответы перед завершением\n\n");
        AnswerRevealMode mode = test.getEffectiveAnswerRevealMode();
        for (int i = 0; i < runtime.details.size(); i++) {
            QuestionAnswerDetail detail = runtime.details.get(i);
            sb.append(i + 1).append(") ").append(detail.questionText).append("\n")
                    .append("Твой ответ: ").append(detail.userAnswer).append("\n");
            if (mode != AnswerRevealMode.NEVER) {
                sb.append(detail.correct ? "✅ Верно\n" : "❌ Неверно\n");
                if (!detail.correct || mode == AnswerRevealMode.END_ONLY || mode == AnswerRevealMode.IMMEDIATE) {
                    sb.append("Правильный ответ: ").append(detail.correctAnswer).append("\n");
                }
            }
            sb.append("\n");
        }
        if (mode == AnswerRevealMode.NEVER) {
            sb.append("Правильные ответы скрыты настройками теста.\n\n");
        }
        sb.append("Чтобы изменить ответ, нажми «Исправить ответы» и выбери номер вопроса.");
        sendInline(chatId, TextUtils.trimTelegramText(sb.toString()), MenuFactory.quizReviewMenu(timerLabel(test, runtime)));
    }

    private void rememberQuestionMessage(QuizRuntime runtime, long chatId, Message sent, String timerText) {
        runtime.cardChatId = chatId;
        runtime.questionMessageId = sent == null ? null : sent.getMessageId();
        runtime.lastTimerText = timerText;
    }

    private String timerLabel(TestData test, QuizRuntime runtime) {
        if (test == null || runtime == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        Long totalLeft = null;
        if (test.totalTimeLimitSeconds != null && test.totalTimeLimitSeconds > 0) {
            totalLeft = test.totalTimeLimitSeconds - ((now - runtime.startedAtMillis) / 1000);
        }
        Long left = null;
        if (totalLeft != null) {
            left = Math.max(0, totalLeft);
        }
        if (left == null) {
            return "⏳ Без ограничения";
        }
        return "⏳ Осталось " + timeText(left.intValue());
    }

    private void tickQuizTimers() {
        try {
            for (Map.Entry<Long, QuizRuntime> entry : quizSessions.entrySet()) {
                long chatId = entry.getKey();
                QuizRuntime runtime = entry.getValue();
                if (runtime == null) {
                    continue;
                }
                TestData test = botService.getTestById(runtime.testId);
                if (test == null) {
                    continue;
                }
                if (checkTotalTimeout(chatId, null, test, runtime)) {
                    continue;
                }
                if (runtime.awaitingConfirmation || runtime.questionIndex >= test.questions.size()) {
                    continue;
                }
                Question question = test.questions.get(runtime.questionIndex);
                updateQuestionTimerMarkup(chatId, test, runtime, question);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateQuestionTimerMarkup(long chatId, TestData test, QuizRuntime runtime, Question question) {
        if (runtime.questionMessageId == null) {
            return;
        }
        String timer = timerLabel(test, runtime);
        if (timer.equals(runtime.lastTimerText)) {
            return;
        }
        InlineKeyboardMarkup markup = question.type == QuestionType.SINGLE_CHOICE
                ? MenuFactory.answerButtons(test.testId, runtime.questionIndex, question.options.size(), timer)
                : MenuFactory.quizActionMenu(timer);
        try {
            telegramClient.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(runtime.questionMessageId)
                    .replyMarkup(markup)
                    .build());
            runtime.lastTimerText = timer;
        } catch (TelegramApiException ignored) {
        }
    }

    private void abortQuiz(long chatId, User user, String reason) {
        QuizRuntime runtime = quizSessions.get(chatId);
        if (runtime == null) {
            sendText(chatId, "Активного прохождения нет.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        TestData test = botService.getTestById(runtime.testId);
        if (test == null) {
            quizSessions.remove(chatId);
            sendText(chatId, "Прохождение прервано.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        finishQuiz(chatId, user, test, runtime, true, reason);
    }

    private void finishQuiz(long chatId, User user, TestData test, QuizRuntime runtime) {
        finishQuiz(chatId, user, test, runtime, false, "Завершён");
    }

    private void finishQuiz(long chatId, User user, TestData test, QuizRuntime runtime, boolean aborted, String finishReason) {
        quizSessions.remove(chatId);

        String resultUserName = user == null ? runtime.userName : displayName(user);
        TestResult saved = botService.appendResult(test.testId,
                user == null ? chatId : user.getId(),
                resultUserName,
                runtime.score,
                test.questions.size(),
                new ArrayList<>(runtime.details),
                aborted,
                finishReason);

        if (saved == null) {
            sendText(chatId, "Не удалось сохранить результат.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (aborted) {
            sb.append("⛔ Тест прерван\n")
                    .append("━━━━━━━━━━━━━━\n")
                    .append("📘 ").append(test.title).append("\n")
                    .append("Отвечено вопросов: ").append(runtime.details.size()).append(" из ").append(test.questions.size()).append("\n")
                    .append("Результат на момент прерывания: ").append(saved.score).append("/").append(saved.total).append("\n")
                    .append("Причина: ").append(finishReason).append("\n");
            sendText(chatId, sb.toString(), MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }

        AnswerRevealMode mode = test.getEffectiveAnswerRevealMode();
        sb.append("✅ Тест завершён\n")
                .append("━━━━━━━━━━━━━━\n")
                .append("📘 ").append(test.title).append("\n")
                .append("Статус: ").append(finishReason).append("\n")
                .append("Результат: ").append(saved.score).append("/").append(saved.total).append("\n")
                .append("Процент: ").append(saved.getPercentText()).append("\n");

        if (mode == AnswerRevealMode.END_ONLY || mode == AnswerRevealMode.IMMEDIATE) {
            sb.append("\nРазбор ответов:\n");
            for (int i = 0; i < runtime.details.size(); i++) {
                QuestionAnswerDetail detail = runtime.details.get(i);
                sb.append(i + 1).append(") ")
                        .append(detail.correct ? "✅ " : "❌ ")
                        .append(detail.questionText).append("\n")
                        .append("Твой ответ: ").append(detail.userAnswer).append("\n")
                        .append("Правильный ответ: ").append(detail.correctAnswer).append("\n\n");
            }
        } else {
            sb.append("\nПравильные ответы скрыты настройками теста.\n");
        }

        sendText(chatId, sb.toString(), MenuFactory.mainMenu(botService.getRole(chatId)));
    }

    private void showResults(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null || (test.creatorId != chatId && !botService.isAdmin(chatId))) {
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
                    .append(result.aborted ? " — прерван" : " — завершён")
                    .append(" — ").append(result.getCompletedAtText())
                    .append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.resultsList(testId, results));
    }

    private void showResultDetails(long chatId, String testId, String resultId) {
        TestData test = botService.getTestById(testId);
        if (test == null || (test.creatorId != chatId && !botService.isAdmin(chatId))) {
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
                .append("Показ ответов: ").append(revealModeText(test.getEffectiveAnswerRevealMode())).append("\n")
                .append("Время на тест: ").append(timeText(test.totalTimeLimitSeconds)).append("\n\n")
                .append("Содержимое теста:\n");
        for (int i = 0; i < test.questions.size(); i++) {
            Question q = test.questions.get(i);
            sb.append(i + 1).append(") ").append(TextUtils.shorten(q.text, 120))
                    .append(" — ").append(questionTypeText(q.type)).append("\n");
            if (q.type == QuestionType.SINGLE_CHOICE) {
                for (int j = 0; j < q.options.size(); j++) {
                    sb.append("   ").append(j + 1).append(". ").append(TextUtils.shorten(q.options.get(j), 80));
                    if (q.correctOptionIndex != null && q.correctOptionIndex == j) {
                        sb.append(" ✅");
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("   Правильный ответ: ").append(correctAnswerFor(q)).append("\n");
            }
        }
        sendInline(chatId, TextUtils.trimTelegramText(sb.toString()), MenuFactory.moderationActions(testId));
    }

    private String shareLink(TestData test) {
        if (botUsername == null || botUsername.isBlank()) {
            return null;
        }
        String username = botUsername.startsWith("@") ? botUsername.substring(1) : botUsername;
        String playableCode = test.testId;
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

    private String revealModeText(AnswerRevealMode mode) {
        if (mode == null) {
            return "показывать сразу";
        }
        return switch (mode) {
            case IMMEDIATE -> "показывать сразу";
            case END_ONLY -> "показывать только в конце";
            case NEVER -> "не показывать";
        };
    }

    private String timeText(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return "без ограничения";
        }

        int total = seconds;
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int sec = total % 60;

        List<String> parts = new ArrayList<>();
        if (hours > 0) {
            parts.add(hours + " " + hourWord(hours));
        }
        if (minutes > 0) {
            parts.add(minutes + " мин");
        }
        if (sec > 0 || parts.isEmpty()) {
            parts.add(sec + " сек");
        }
        return String.join(" ", parts);
    }

    private String hourWord(int value) {
        int abs = Math.abs(value);
        int lastTwo = abs % 100;
        int last = abs % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "часов";
        }
        if (last == 1) {
            return "час";
        }
        if (last >= 2 && last <= 4) {
            return "часа";
        }
        return "часов";
    }

    private Integer parseTimeSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            if (value.endsWith("с") || value.endsWith("s")) {
                return Integer.parseInt(value.substring(0, value.length() - 1));
            }
            if (value.endsWith("м") || value.endsWith("m")) {
                return Integer.parseInt(value.substring(0, value.length() - 1)) * 60;
            }
            // По умолчанию число считается минутами, потому что так удобнее при настройке теста.
            return Integer.parseInt(value) * 60;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String questionTypeText(QuestionType type) {
        if (type == null) {
            return "не указан";
        }
        return switch (type) {
            case SINGLE_CHOICE -> "выбор варианта";
            case TEXT_INPUT -> "текстовый ответ";
            case NUMBER_INPUT -> "числовой ответ";
        };
    }

    private String correctAnswerFor(Question question) {
        if (question.type == QuestionType.SINGLE_CHOICE) {
            if (question.correctOptionIndex == null || question.correctOptionIndex < 0 || question.correctOptionIndex >= question.options.size()) {
                return "-";
            }
            return question.options.get(question.correctOptionIndex);
        }
        if (question.type == QuestionType.TEXT_INPUT) {
            return question.correctTextAnswer;
        }
        return question.correctNumberAnswer == null ? "-" : TextUtils.formatNumber(question.correctNumberAnswer);
    }

    private boolean checkTotalTimeout(long chatId, User user, TestData test, QuizRuntime runtime) {
        if (test.totalTimeLimitSeconds == null || test.totalTimeLimitSeconds <= 0) {
            return false;
        }
        long elapsed = (System.currentTimeMillis() - runtime.startedAtMillis) / 1000;
        if (elapsed <= test.totalTimeLimitSeconds) {
            return false;
        }
        sendInline(chatId, "⏳ Время на тест вышло. Тест завершается.", MenuFactory.quizActionMenu());
        finishQuiz(chatId, user, test, runtime, false, "Время теста вышло");
        return true;
    }

    private void finishSupportText(long chatId, String userName, String text) {
        if (text == null || text.isBlank()) {
            sendInputPrompt(chatId, "Обращение не должно быть пустым. Опиши проблему одним сообщением.");
            return;
        }
        SupportRequest request = botService.createSupportRequest(chatId, userName, text.trim());
        sessions.get(chatId).state = SessionState.IDLE;
        sendText(chatId, "Обращение отправлено. Ответ появится в разделе «Помощь», а также придёт сообщением от бота.", MenuFactory.mainMenu(botService.getRole(chatId)));
        for (Map.Entry<Long, Role> entry : botService.getAllRoles()) {
            if (entry.getValue() == Role.ADMIN || entry.getValue() == Role.MODERATOR) {
                sendInline(entry.getKey(),
                        "📨 Новое обращение от " + botService.displayUser(chatId) + "\n\n" + TextUtils.shorten(request.text, 120),
                        MenuFactory.supportActions(request.requestId, false));
            }
        }
    }

    private void finishSupportAnswer(long chatId, String responderName, String text) {
        UserSession session = sessions.get(chatId);
        if (session.selectedSupportRequestId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Обращение не выбрано.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        if (text == null || text.isBlank()) {
            sendInputPrompt(chatId, "Ответ не должен быть пустым. Введи ответ пользователю.");
            return;
        }
        SupportRequest request = botService.answerSupportRequest(session.selectedSupportRequestId, chatId, responderName, text.trim());
        session.state = SessionState.IDLE;
        session.selectedSupportRequestId = null;
        if (request == null) {
            sendText(chatId, "Обращение не найдено.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        sendText(chatId, "Ответ отправлен пользователю " + botService.displayUser(request.userId) + ".", MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(request.userId,
                "📩 Ответ на твоё обращение:\n\n" + request.answer + "\n\nЕсли нужно, можешь открыть «Помощь» и написать новое обращение.",
                MenuFactory.mainMenu(botService.getRole(request.userId)));
    }

    private void showSupportRequests(long chatId) {
        List<SupportRequest> requests = botService.getSupportRequests();
        if (requests.isEmpty()) {
            sendInline(chatId, "📨 Обращений пока нет.", MenuFactory.supportList(requests));
            return;
        }
        StringBuilder sb = new StringBuilder("📨 Обращения пользователей\n\n");
        for (SupportRequest request : requests) {
            sb.append(request.answered ? "✅ " : "📨 ")
                    .append(request.userName).append(" — ")
                    .append(TextUtils.shorten(request.text, 70)).append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.supportList(requests));
    }

    private void showSupportRequest(long chatId, String requestId) {
        SupportRequest request = botService.getSupportRequest(requestId);
        if (request == null) {
            sendText(chatId, "Обращение не найдено.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        StringBuilder sb = new StringBuilder("📨 Обращение\n\n")
                .append("Пользователь: ").append(botService.displayUser(request.userId)).append("\n")
                .append("Статус: ").append(request.answered ? "отвечено" : "ожидает ответа").append("\n\n")
                .append("Текст:\n").append(request.text).append("\n");
        if (request.answered) {
            sb.append("\nОтвет: ").append(request.answer).append("\n")
                    .append("Ответил: ").append(request.responderName == null ? botService.displayUser(request.responderId) : request.responderName).append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.supportActions(request.requestId, request.answered));
    }

    private void showMyLatestSupportAnswer(long chatId) {
        SupportRequest request = botService.getLatestAnsweredSupportForUser(chatId);
        if (request == null) {
            sendInline(chatId, "Ответов на обращения пока нет.", MenuFactory.helpMenu(false));
            return;
        }
        sendInline(chatId,
                "📩 Последний ответ на обращение\n\n" +
                        "Твоё обращение: " + request.text + "\n\n" +
                        "Ответ: " + request.answer,
                MenuFactory.helpMenu(true));
    }

    private void showUsersList(long chatId) {
        StringBuilder sb = new StringBuilder("👥 Пользователи, которые писали боту\n\n");
        List<KnownUser> users = botService.getKnownUsers();
        if (users.isEmpty()) {
            sb.append("Пользователей пока нет.");
        }
        for (KnownUser knownUser : users) {
            sb.append("• ").append(knownUser.displayName())
                    .append(" — роль: ").append(botService.getRole(knownUser.userId));
            UserRestriction restriction = botService.getRestriction(knownUser.userId);
            if (restriction != null && botService.isCreationBlocked(knownUser.userId)) {
                sb.append(" — создание тестов заблокировано");
            }
            sb.append("\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.adminMenu());
    }

    private void showAdminStats(long chatId) {
        List<TestData> tests = botService.getAllTests();
        int attempts = botService.getTotalResultsCount();
        long aborted = tests.stream().flatMap(test -> test.results.stream()).filter(result -> result.aborted).count();
        long finished = attempts - aborted;

        StringBuilder sb = new StringBuilder("📈 Статистика системы\n\n")
                .append("Пользователей: ").append(botService.getKnownUsers().size()).append("\n")
                .append("Тестов: ").append(tests.size()).append("\n")
                .append("Прохождений всего: ").append(attempts).append("\n")
                .append("Завершено: ").append(finished).append("\n")
                .append("Прервано: ").append(aborted).append("\n\n");


        sendInline(chatId, sb.toString(), MenuFactory.statsMenu());
    }

    private void showRestrictionPanel(long chatId) {
        StringBuilder sb = new StringBuilder("🚫 Блокировки создания тестов\n\n");
        List<Map.Entry<Long, UserRestriction>> restrictions = botService.getRestrictions();
        if (restrictions.isEmpty()) {
            sb.append("Активных ограничений нет.\n");
        }
        for (Map.Entry<Long, UserRestriction> entry : restrictions) {
            UserRestriction restriction = entry.getValue();
            sb.append("• ").append(botService.displayUser(entry.getKey())).append("\n")
                    .append("До: ").append(restriction.blockedUntil == null ? "навсегда" : restriction.blockedUntil.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append("\n")
                    .append("Причина: ").append(restriction.reason == null ? "-" : restriction.reason).append("\n\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.moderationTools());
    }

    private void showAdminAllTests(long chatId) {
        List<TestData> tests = botService.getAllTests();
        if (tests.isEmpty()) {
            sendInline(chatId, "Тестов пока нет.", MenuFactory.adminMenu());
            return;
        }
        StringBuilder sb = new StringBuilder("🧾 Все тесты в системе\n\n");
        for (TestData test : tests) {
            sb.append("• ").append(test.title).append("\n")
                    .append("Автор: ").append(botService.displayUser(test.creatorId)).append("\n")
                    .append("Статус: ").append(statusText(test.status)).append("\n")
                    .append("Вопросов: ").append(test.questions.size()).append(" | Прохождений: ").append(test.results.size()).append("\n\n");
        }
        sendInline(chatId, sb.toString(), MenuFactory.adminAllTestsList(tests));
    }

    private void finishBlockUserStep(long chatId, String text) {
        Long userId = botService.resolveKnownUserId(text);
        if (userId == null) {
            sendInputPrompt(chatId, "Пользователь не найден. Введи @username известного пользователя или числовой Telegram ID.");
            return;
        }
        UserSession session = sessions.get(chatId);
        session.restrictionTargetUserId = userId;
        session.state = SessionState.WAITING_MOD_BLOCK_DAYS;
        sendInputPrompt(chatId, "На сколько дней запретить создание тестов? Введи число.");
    }

    private void finishBlockDaysStep(long chatId, String text) {
        try {
            int days = Integer.parseInt(text.trim());
            if (days <= 0) {
                sendInputPrompt(chatId, "Количество дней должно быть положительным.");
                return;
            }
            UserSession session = sessions.get(chatId);
            session.restrictionDays = days;
            session.state = SessionState.WAITING_MOD_BLOCK_REASON;
            sendInputPrompt(chatId, "Укажи причину запрета.");
        } catch (NumberFormatException e) {
            sendInputPrompt(chatId, "Нужно ввести число дней.");
        }
    }

    private void finishBlockReasonStep(long chatId, String text) {
        UserSession session = sessions.get(chatId);
        if (session.restrictionTargetUserId == null) {
            session.state = SessionState.IDLE;
            sendText(chatId, "Пользователь для блокировки не выбран.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        botService.blockCreation(session.restrictionTargetUserId, session.restrictionDays, text.trim());
        Long targetId = session.restrictionTargetUserId;
        session.state = SessionState.IDLE;
        sendText(chatId, "Создание тестов запрещено пользователю " + botService.displayUser(targetId) + ".", MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(targetId, "🚫 Тебе временно запрещено создавать тесты.\nПричина: " + text.trim(), MenuFactory.mainMenu(botService.getRole(targetId)));
    }

    private void finishUnblockUser(long chatId, String text) {
        Long userId = botService.resolveKnownUserId(text);
        if (userId == null) {
            sendInputPrompt(chatId, "Пользователь не найден. Введи @username известного пользователя или числовой Telegram ID.");
            return;
        }
        botService.unblockCreation(userId);
        sessions.get(chatId).state = SessionState.IDLE;
        sendText(chatId, "Запрет снят с пользователя " + botService.displayUser(userId) + ".", MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(userId, "✅ Запрет на создание тестов снят.", MenuFactory.mainMenu(botService.getRole(userId)));
    }

    private void finishBlockForeverUser(long chatId, String text) {
        Long userId = botService.resolveKnownUserId(text);
        if (userId == null) {
            sendInputPrompt(chatId, "Пользователь не найден. Введи @username известного пользователя или числовой Telegram ID.");
            return;
        }
        botService.blockCreationForever(userId, "Бессрочный запрет на создание тестов");
        sessions.get(chatId).state = SessionState.IDLE;
        sendText(chatId, "Пользователю " + botService.displayUser(userId) + " бессрочно запрещено создавать тесты.", MenuFactory.mainMenu(botService.getRole(chatId)));
        sendText(userId, "⛔ Тебе бессрочно запрещено создавать тесты.", MenuFactory.mainMenu(botService.getRole(userId)));
    }

    private void exportTestText(long chatId, String testId) {
        try {
            Path file = Files.createTempFile("testmasterbot-test", ".txt");
            Files.writeString(file, botService.exportTestAsText(testId), StandardCharsets.UTF_8);
            sendDocument(chatId, file, "Экспорт теста в TXT.");
        } catch (Exception e) {
            e.printStackTrace();
            sendText(chatId, "Не удалось экспортировать тест.", MenuFactory.mainMenu(botService.getRole(chatId)));
        }
    }


    private List<List<String>> buildSummaryRows() {
        List<TestData> tests = botService.getAllTests();
        int attempts = botService.getTotalResultsCount();
        long aborted = tests.stream().flatMap(test -> test.results.stream()).filter(result -> result.aborted).count();
        return List.of(
                List.of("Показатель", "Значение"),
                List.of("Пользователей", String.valueOf(botService.getKnownUsers().size())),
                List.of("Тестов", String.valueOf(tests.size())),
                List.of("Прохождений всего", String.valueOf(attempts)),
                List.of("Завершено", String.valueOf(attempts - aborted)),
                List.of("Прервано", String.valueOf(aborted))
        );
    }

    private List<List<String>> buildTestsRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Код", "Название", "Автор", "Статус", "Вопросов", "Прохождений", "Завершено", "Прервано", "Средний %"));
        for (TestData test : botService.getAllTests()) {
            long aborted = test.results.stream().filter(result -> result.aborted).count();
            rows.add(List.of(
                    test.testId,
                    safeCell(test.title),
                    safeCell(botService.displayUser(test.creatorId)),
                    statusText(test.status),
                    String.valueOf(test.questions.size()),
                    String.valueOf(test.results.size()),
                    String.valueOf(test.results.size() - aborted),
                    String.valueOf(aborted),
                    TextUtils.formatPercent(test.averagePercent())
            ));
        }
        return rows;
    }

    private List<List<String>> buildResultsRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Тест", "Пользователь", "Балл", "Всего", "Процент", "Статус", "Причина", "Дата"));
        for (TestData test : botService.getAllTests()) {
            for (TestResult result : test.results) {
                rows.add(List.of(
                        safeCell(test.title),
                        safeCell(result.userName),
                        String.valueOf(result.score),
                        String.valueOf(result.total),
                        result.getPercentText(),
                        result.aborted ? "Прерван" : "Завершён",
                        safeCell(result.finishReason),
                        result.getCompletedAtText()
                ));
            }
        }
        return rows;
    }

    private List<List<String>> buildAnswerRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Тест", "Пользователь", "Вопрос", "Ответ пользователя", "Правильный ответ", "Верно"));
        for (TestData test : botService.getAllTests()) {
            for (TestResult result : test.results) {
                for (QuestionAnswerDetail detail : result.details) {
                    rows.add(List.of(
                            safeCell(test.title),
                            safeCell(result.userName),
                            safeCell(detail.questionText),
                            safeCell(detail.userAnswer),
                            safeCell(detail.correctAnswer),
                            detail.correct ? "Да" : "Нет"
                    ));
                }
            }
        }
        return rows;
    }

    private List<List<String>> buildUsersRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Telegram ID", "Пользователь", "Роль", "Блокировка создания", "До", "Причина"));
        for (KnownUser user : botService.getKnownUsers()) {
            UserRestriction restriction = botService.getRestriction(user.userId);
            rows.add(List.of(
                    String.valueOf(user.userId),
                    safeCell(user.displayName()),
                    String.valueOf(botService.getRole(user.userId)),
                    botService.isCreationBlocked(user.userId) ? "Да" : "Нет",
                    restriction == null || restriction.blockedUntil == null ? "" : restriction.blockedUntil.toString(),
                    restriction == null ? "" : safeCell(restriction.reason)
            ));
        }
        return rows;
    }

    private List<List<String>> buildChartRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Тест", "Прохождений", "Средний результат", "Прервано"));
        for (TestData test : botService.getAllTests()) {
            long aborted = test.results.stream().filter(result -> result.aborted).count();
            rows.add(List.of(
                    safeCell(test.title),
                    String.valueOf(test.results.size()),
                    TextUtils.formatPercent(test.averagePercent()),
                    String.valueOf(aborted)
            ));
        }
        return rows;
    }

    private String safeCell(String value) {
        return value == null ? "" : value;
    }

    private void exportTestResultsExcel(long chatId, String testId) {
        TestData test = botService.getTestById(testId);
        if (test == null) {
            sendText(chatId, "Тест не найден.", MenuFactory.mainMenu(botService.getRole(chatId)));
            return;
        }
        try {
            Path file = Files.createTempFile("testmasterbot-results-" + testId, ".xlsx");
            SimpleXlsxBuilder.write(file, List.of(
                    new SimpleXlsxBuilder.Sheet("Сводка", buildOneTestSummaryRows(test)),
                    new SimpleXlsxBuilder.Sheet("Прохождения", buildOneTestResultsRows(test)),
                    new SimpleXlsxBuilder.Sheet("Ответы", buildOneTestAnswerRows(test))
            ));
            sendDocument(chatId, file, "Excel-отчёт по результатам теста «" + TextUtils.shorten(test.title, 60) + "».");
        } catch (Exception e) {
            e.printStackTrace();
            sendText(chatId, "Не удалось сформировать Excel по результатам теста.", MenuFactory.mainMenu(botService.getRole(chatId)));
        }
    }

    private List<List<String>> buildOneTestSummaryRows(TestData test) {
        long aborted = test.results.stream().filter(result -> result.aborted).count();
        return List.of(
                List.of("Показатель", "Значение"),
                List.of("Название", safeCell(test.title)),
                List.of("Код запуска", test.testId),
                List.of("Автор", safeCell(botService.displayUser(test.creatorId))),
                List.of("Статус", statusText(test.status)),
                List.of("Вопросов", String.valueOf(test.questions.size())),
                List.of("Прохождений", String.valueOf(test.results.size())),
                List.of("Завершено", String.valueOf(test.results.size() - aborted)),
                List.of("Прервано", String.valueOf(aborted)),
                List.of("Средний результат", TextUtils.formatPercent(test.averagePercent()) + "%")
        );
    }

    private List<List<String>> buildOneTestResultsRows(TestData test) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Пользователь", "Балл", "Всего", "Процент", "Статус", "Причина", "Дата"));
        for (TestResult result : test.results) {
            rows.add(List.of(
                    safeCell(result.userName),
                    String.valueOf(result.score),
                    String.valueOf(result.total),
                    result.getPercentText(),
                    result.aborted ? "Прерван" : "Завершён",
                    safeCell(result.finishReason),
                    result.getCompletedAtText()
            ));
        }
        return rows;
    }

    private List<List<String>> buildOneTestAnswerRows(TestData test) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Пользователь", "Вопрос", "Ответ пользователя", "Правильный ответ", "Верно"));
        for (TestResult result : test.results) {
            for (QuestionAnswerDetail detail : result.details) {
                rows.add(List.of(
                        safeCell(result.userName),
                        safeCell(detail.questionText),
                        safeCell(detail.userAnswer),
                        safeCell(detail.correctAnswer),
                        detail.correct ? "Да" : "Нет"
                ));
            }
        }
        return rows;
    }

    private void exportStatisticsCsv(long chatId) {
        try {
            Path file = Files.createTempFile("testmasterbot-statistics", ".xlsx");
            SimpleXlsxBuilder.write(file, List.of(
                    new SimpleXlsxBuilder.Sheet("Сводка", buildSummaryRows()),
                    new SimpleXlsxBuilder.Sheet("Тесты", buildTestsRows()),
                    new SimpleXlsxBuilder.Sheet("Прохождения", buildResultsRows()),
                    new SimpleXlsxBuilder.Sheet("Ответы", buildAnswerRows()),
                    new SimpleXlsxBuilder.Sheet("Пользователи", buildUsersRows())
            ));
            sendDocument(chatId, file, "Excel-отчёт TestMasterBot.");
        } catch (Exception e) {
            e.printStackTrace();
            sendText(chatId, "Не удалось сформировать Excel-отчёт.", MenuFactory.mainMenu(botService.getRole(chatId)));
        }
    }

    private void addZipEntry(ZipOutputStream zip, String name, String content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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

    private Message sendInline(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(keyboard)
                .build();
        try {
            return telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
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

    private Message sendInputPromptWithMenu(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(keyboard)
                .build();
        try {
            return telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Message sendPhoto(long chatId, String photoFileId, String caption, Object keyboard) {
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
            return telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendRemoveKeyboard(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(TextUtils.trimTelegramText(text))
                .replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build())
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendDocument(long chatId, Path path, String caption) {
        try {
            telegramClient.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(path.toFile()))
                    .caption(caption)
                    .build());
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
        long startedAtMillis;
        long questionStartedAtMillis;
        Long cardChatId;
        Integer questionMessageId;
        String lastTimerText;
        boolean awaitingConfirmation;
        int editingQuestionIndex = -1;
        List<QuestionAnswerDetail> details = new ArrayList<>();
    }
}
