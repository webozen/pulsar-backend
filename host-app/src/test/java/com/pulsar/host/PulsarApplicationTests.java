package com.pulsar.host;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class PulsarApplicationTests {
    @Test
    void mainClassIsLoadable() {
        assertNotNull(PulsarApplication.class.getName());
    }
}
