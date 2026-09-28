package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared default JSON mapper. Configure it only before the first use. */
public final class JsonSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }
}
