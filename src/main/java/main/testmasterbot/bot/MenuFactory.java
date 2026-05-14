package main.testmasterbot.bot;

import main.testmasterbot.model.AnswerRevealMode;
import main.testmasterbot.model.PublicationStatus;
import main.testmasterbot.model.Role;
import main.testmasterbot.model.TestData;
import main.testmasterbot.model.TestResult;
import main.testmasterbot.util.TextUtils;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

public final class MenuFactory {
    private MenuFactory() {
    }

    public static ReplyKeyboardMarkup mainMenu(Role role) {
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("➕ Создать тест");
        row1.add("📚 Мои тесты");
        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🌍 Опубликованные тесты");
        row2.add("❓ Помощь");
        rows.add(row2);

        if (role == Role.MODERATOR || role == Role.ADMIN) {
            KeyboardRow row3 = new KeyboardRow();
            row3.add("🛡 Очередь модерации");
            row3.add("🚫 Блокировки");
            rows.add(row3);
        }

        if (role == Role.ADMIN) {
            KeyboardRow row4 = new KeyboardRow();
            row4.add("⚙️ Админ-панель");
            row4.add("📈 Статистика");
            rows.add(row4);
        }

        KeyboardRow last = new KeyboardRow();
        last.add("✋ Отмена");
        last.add("🏠 Меню");
        rows.add(last);

        return ReplyKeyboardMarkup.builder()
                .keyboard(rows)
                .resizeKeyboard(true)
                .build();
    }

    public static ReplyKeyboardMarkup compactReplyMenu() {
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("🏠 Меню");
        rows.add(row);
        return ReplyKeyboardMarkup.builder().keyboard(rows).resizeKeyboard(true).build();
    }

    public static ReplyKeyboardMarkup quizLockedMenu() {
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("⛔ Прервать тест");
        rows.add(row);
        return ReplyKeyboardMarkup.builder().keyboard(rows).resizeKeyboard(true).build();
    }

    public static InlineKeyboardMarkup compactMenuButton() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup quizActionMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("⛔ Прервать тест").callbackData("quiz:abort").build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup afterQuestionMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("➕ Добавить ещё вопрос").callbackData("draft:add_question").build(),
                InlineKeyboardButton.builder().text("✅ Завершить тест").callbackData("draft:finish").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup visibilityMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🌍 Публичный").callbackData("visibility:public").build(),
                InlineKeyboardButton.builder().text("🔒 Приватный").callbackData("visibility:private").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup answerPolicyMenu(AnswerRevealMode selected) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.IMMEDIATE ? "✅ " : "") + "Показывать сразу")
                        .callbackData("policy:immediate")
                        .build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.END_ONLY ? "✅ " : "") + "Только в конце")
                        .callbackData("policy:end")
                        .build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.NEVER ? "✅ " : "") + "Не показывать ответы")
                        .callbackData("policy:never")
                        .build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup answerRevealEditMenu(String testId, AnswerRevealMode selected) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.IMMEDIATE ? "✅ " : "") + "Показывать сразу")
                        .callbackData("set_reveal:" + testId + ":IMMEDIATE")
                        .build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.END_ONLY ? "✅ " : "") + "Только в конце")
                        .callbackData("set_reveal:" + testId + ":END_ONLY")
                        .build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text((selected == AnswerRevealMode.NEVER ? "✅ " : "") + "Не показывать")
                        .callbackData("set_reveal:" + testId + ":NEVER")
                        .build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup testTimeMenu() {
        return timeMenu("testtime:", true);
    }

    public static InlineKeyboardMarkup testTimeEditMenu(String testId) {
        return timeMenu("set_time:" + testId + ":", true);
    }

    public static InlineKeyboardMarkup questionTimeMenu() {
        return timeMenu("qtime:", false);
    }

    private static InlineKeyboardMarkup timeMenu(String prefix, boolean allowLong) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Без ограничения").callbackData(prefix + "0").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("30 сек").callbackData(prefix + "30").build(),
                InlineKeyboardButton.builder().text("1 мин").callbackData(prefix + "60").build(),
                InlineKeyboardButton.builder().text("2 мин").callbackData(prefix + "120").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("5 мин").callbackData(prefix + "300").build(),
                InlineKeyboardButton.builder().text(allowLong ? "10 мин" : "3 мин").callbackData(prefix + (allowLong ? "600" : "180")).build(),
                InlineKeyboardButton.builder().text(allowLong ? "20 мин" : "10 мин").callbackData(prefix + (allowLong ? "1200" : "600")).build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup questionTypeMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Выбор варианта").callbackData("qtype:single").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Текстовый ответ").callbackData("qtype:text").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Числовой ответ").callbackData("qtype:number").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup optionsCountMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("2").callbackData("optcount:2").build(),
                InlineKeyboardButton.builder().text("3").callbackData("optcount:3").build(),
                InlineKeyboardButton.builder().text("4").callbackData("optcount:4").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("5").callbackData("optcount:5").build(),
                InlineKeyboardButton.builder().text("6").callbackData("optcount:6").build(),
                InlineKeyboardButton.builder().text("7").callbackData("optcount:7").build(),
                InlineKeyboardButton.builder().text("8").callbackData("optcount:8").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup correctOptionMenu(int count) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int i = 1; i <= count; i++) {
            row.add(InlineKeyboardButton.builder().text(String.valueOf(i)).callbackData("correctopt:" + i).build());
            if (row.size() == 4) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup photoDecisionMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("📷 Добавить фото").callbackData("photo:yes").build(),
                InlineKeyboardButton.builder().text("➡️ Без фото").callbackData("photo:no").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup skipPhotoMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("➡️ Без фото").callbackData("photo:no").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup playList(List<TestData> tests) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (TestData test : tests) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text("▶ " + TextUtils.shorten(test.title, 28))
                            .callbackData("play:" + test.testId)
                            .build()
            ));
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup answerButtons(String testId, int questionIndex, int optionCount) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int i = 0; i < optionCount; i++) {
            row.add(InlineKeyboardButton.builder()
                    .text(String.valueOf(i + 1))
                    .callbackData("answer:" + testId + ":" + questionIndex + ":" + i)
                    .build());
            if (row.size() == 4) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("⛔ Прервать тест").callbackData("quiz:abort").build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup myTestsList(List<TestData> tests) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (TestData test : tests) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text(TextUtils.shorten(test.title, 30))
                            .callbackData("myopen:" + test.testId)
                            .build()
            ));
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup myTestActions(TestData test) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("📊 Результаты").callbackData("results:" + test.testId).build(),
                InlineKeyboardButton.builder().text("🔗 Ссылка").callbackData("share:" + test.testId).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✏️ Название").callbackData("edit_title:" + test.testId).build(),
                InlineKeyboardButton.builder().text("📝 Описание").callbackData("edit_desc:" + test.testId).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🙈 Показ ответов").callbackData("reveal_menu:" + test.testId).build(),
                InlineKeyboardButton.builder().text("⏱ Время").callbackData("time_menu:" + test.testId).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🔁 Доступ").callbackData("toggle_access:" + test.testId).build(),
                InlineKeyboardButton.builder().text("▶ Запустить").callbackData("play:" + test.testId).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🗑 Удалить").callbackData("delete:" + test.testId).build()
        ));
        if (test.status == PublicationStatus.REJECTED || test.status == PublicationStatus.PRIVATE) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text("📤 На модерацию").callbackData("submit:" + test.testId).build()
            ));
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup resultsList(String testId, List<TestResult> results) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        int count = 0;
        for (TestResult result : results) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text(TextUtils.shorten(result.userName, 16) + " · " + result.getPercentText())
                            .callbackData("result:" + testId + ":" + result.resultId)
                            .build()
            ));
            count++;
            if (count >= 12) {
                break;
            }
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup moderationList(List<TestData> tests) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (TestData test : tests) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text("📝 " + TextUtils.shorten(test.title, 28))
                            .callbackData("mod_open:" + test.testId)
                            .build()
            ));
        }
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup moderationActions(String testId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✅ Одобрить").callbackData("mod_approve:" + testId).build(),
                InlineKeyboardButton.builder().text("❌ Отклонить").callbackData("mod_reject:" + testId).build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup moderationTools() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🚫 Запретить создание").callbackData("mod:block_create").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("✅ Снять запрет").callbackData("mod:unblock_create").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("📋 Активные ограничения").callbackData("mod:list_blocks").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup adminMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("👥 Пользователи").callbackData("admin:list_users").build(),
                InlineKeyboardButton.builder().text("👥 Роли").callbackData("admin:list_roles").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("📈 Статистика").callbackData("admin:stats").build(),
                InlineKeyboardButton.builder().text("📤 CSV").callbackData("admin:export_stats").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("➕ Модератор").callbackData("admin:add_mod").build(),
                InlineKeyboardButton.builder().text("⭐ Админ").callbackData("admin:add_admin").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("➖ Снять роль").callbackData("admin:remove_role").build()
        ));
        rows.add(menuRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardRow menuRow() {
        return new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("🏠 Меню").callbackData("menu:open").build()
        );
    }
}
