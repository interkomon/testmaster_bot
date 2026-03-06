package main.testmasterbot;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class TestMasterBotService implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;

    public TestMasterBotService(String botToken) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        String answer;

        if (text.equals("/start")) {
            answer = "Привет! Я TestMasterBot.\nПока это только начало.";
        } else if (text.equals("/help")) {
            answer = "Команды:\n/start - запуск\n/help - помощь";
        } else {
            answer = "Ты написал: " + text;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(answer)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
