package com.example.index;

import java.io.IOException;
import java.util.List;

/** Общий контракт эмбеддера: батч текстов → векторы одной размерности. */
public interface Embedder {

    /** Максимальное число текстов в одном запросе. */
    int batchSize();

    /** Векторы для текстов в порядке входа; один вызов = один запрос к API. */
    List<float[]> embed(List<String> texts) throws IOException, InterruptedException;
}
