package com.example.index;

/** Кусок текста документа: строка и её координаты в исходном тексте. */
public record Piece(String text, int startChar, int endChar) {
}
