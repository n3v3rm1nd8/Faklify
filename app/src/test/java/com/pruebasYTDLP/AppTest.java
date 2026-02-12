package com.pruebasYTDLP;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @Test void appHasAGreeting() {
       YouTubePlayerExamplep classUnderTest = newYouTubePlayerExamplep();
        assertNotNull(classUnderTest.getGreeting(), "app should have a greeting");
    }
}
