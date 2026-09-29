package com.example;

/** The provider returned a successful response without visible assistant text. */
public final class EmptyLlmAnswerException extends AgentException {
    public EmptyLlmAnswerException(String message) {
        super(message);
    }
}
