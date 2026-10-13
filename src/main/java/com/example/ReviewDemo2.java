package com.example;

import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class ReviewDemo2 {

    static int nameLength(Map<String, String> values) {
        return values.get("name").length();
    }

    static int readFirstChar(String path) throws IOException {
        FileReader reader = new FileReader(path);
        return reader.read();
    }

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com/")).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("status=" + response.statusCode());
        System.out.println("nameLength=" + nameLength(Map.of("title", "demo")));
        System.out.println("firstChar=" + readFirstChar(args.length > 0 ? args[0] : "README.md"));
    }
}
