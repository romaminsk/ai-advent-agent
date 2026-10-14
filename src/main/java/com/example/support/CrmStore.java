package com.example.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Чтение данных мини-CRM из JSON-файлов каталога (только чтение). */
public final class CrmStore {

    static final String CUSTOMERS_FILE = "customers.json";
    static final String TICKETS_FILE = "tickets.json";

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public CrmStore(Path directory) {
        this.directory = directory;
    }

    Optional<JsonNode> customer(String id) throws IOException {
        return find(CUSTOMERS_FILE, id);
    }

    Optional<JsonNode> ticket(String id) throws IOException {
        return find(TICKETS_FILE, id);
    }

    List<JsonNode> ticketsOf(String customerId) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode ticket : records(TICKETS_FILE)) {
            if (customerId.equals(ticket.path("customerId").asText())) {
                result.add(ticket);
            }
        }
        return result;
    }

    private Optional<JsonNode> find(String file, String id) throws IOException {
        for (JsonNode record : records(file)) {
            if (id.equals(record.path("id").asText())) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    private List<JsonNode> records(String file) throws IOException {
        Path path = directory.resolve(file);
        if (!Files.isRegularFile(path)) {
            throw new IOException("файл CRM не найден: " + file);
        }
        JsonNode root = mapper.readTree(Files.readString(path));
        if (!(root instanceof ArrayNode array)) {
            throw new IOException("файл CRM должен содержать массив: " + file);
        }
        List<JsonNode> records = new ArrayList<>();
        array.forEach(records::add);
        return records;
    }
}
