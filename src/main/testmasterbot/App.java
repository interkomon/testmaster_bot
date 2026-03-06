package main.testmasterbot;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.util.concurrent.CountDownLatch;

public class App {
    public static void main(String[] args) throws Exception {
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");

        if (botToken == null || botToken.isBlank()) {
            System.out.println("Ошибка: не задан токен бота.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);

        try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
            botsApplication.registerBot(botToken, new TestMasterBotService(botToken));

            System.out.println("Бот запущен.");

            latch.await();
        }
    }
}