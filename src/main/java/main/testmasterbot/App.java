package main.testmasterbot;

import main.testmasterbot.bot.TestMasterBot;
import main.testmasterbot.repository.JsonDataStore;
import main.testmasterbot.service.BotService;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.util.concurrent.CountDownLatch;

public class App {
    public static void main(String[] args) throws Exception {
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        if (botToken == null || botToken.isBlank()) {
            System.out.println("Ошибка: не задан токен бота.");
            return;
        }

        JsonDataStore dataStore = new JsonDataStore("src/main/resources/data/testmasterbot-data.json");
        BotService botService = new BotService(dataStore);
        botService.bootstrapRolesFromEnv(
                System.getenv("BOT_ADMIN_IDS"),
                System.getenv("BOT_MODERATOR_IDS")
        );

        CountDownLatch latch = new CountDownLatch(1);

        try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
            botsApplication.registerBot(botToken, new TestMasterBot(botToken, botService));
            System.out.println("TestMasterBot запущен.");
            System.out.println("Файл данных: src/main/resources/data/testmasterbot-data.json");
            latch.await();
        }
    }
}
