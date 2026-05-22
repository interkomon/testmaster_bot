package main.testmasterbot;

import main.testmasterbot.bot.TestMasterBot;
import main.testmasterbot.repository.SqlServerDataStore;
import main.testmasterbot.service.BotService;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.util.concurrent.CountDownLatch;

public class App {
    public static void main(String[] args) throws Exception {
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        if (botToken == null || botToken.isBlank()) {
            System.out.println("Ошибка: не задан токен бота. Укажи TELEGRAM_BOT_TOKEN.");
            return;
        }

        String dbUrl = System.getenv("DB_URL");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        if (dbUrl == null || dbUrl.isBlank()) {
            System.out.println("Ошибка: не задана строка подключения DB_URL.");
            System.out.println("Пример: jdbc:sqlserver://localhost:1433;databaseName=TestMasterBotDb;encrypt=true;trustServerCertificate=true");
            return;
        }

        SqlServerDataStore dataStore = new SqlServerDataStore(dbUrl, dbUser, dbPassword);
        BotService botService = new BotService(dataStore);
        botService.bootstrapRolesFromEnv(
                System.getenv("BOT_ADMIN_IDS"),
                System.getenv("BOT_MODERATOR_IDS")
        );

        CountDownLatch latch = new CountDownLatch(1);

        try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
            botsApplication.registerBot(botToken, new TestMasterBot(botToken, botService));
            System.out.println("TestMasterBot запущен.");
            System.out.println("Хранилище данных: SQL Server.");
            latch.await();
        }
    }
}
