package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateNotifyListenerTest {

    // With notify_console off the server log holds no update details to point to.
    @Test
    void joinHintPointsToTheLogOnlyWhenTheConsoleIsNotified() {
        assertEquals(List.of("update.available", "update.details"), UpdateNotifyListener.joinMessageKeys(true));
        assertEquals(List.of("update.available"), UpdateNotifyListener.joinMessageKeys(false));
    }
}
