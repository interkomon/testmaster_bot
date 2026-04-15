package main.testmasterbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import main.testmasterbot.model.BotData;

import java.io.File;
import java.io.IOException;

public class JsonDataStore {
    private final File file;
    private final ObjectMapper mapper;
    private BotData cachedData;

    public JsonDataStore(String filePath) {
        this.file = new File(filePath);
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        ensureParentDir();
        this.cachedData = readFromFile();
    }

    public synchronized BotData load() {
        return cachedData;
    }

    public synchronized void save() {
        try {
            mapper.writeValue(file, cachedData);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось сохранить данные в файл: " + file.getAbsolutePath(), e);
        }
    }

    private BotData readFromFile() {
        if (!file.exists()) {
            return new BotData();
        }
        try {
            return mapper.readValue(file, BotData.class);
        } catch (IOException e) {
            return new BotData();
        }
    }

    private void ensureParentDir() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
