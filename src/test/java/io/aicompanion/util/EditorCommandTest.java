package io.aicompanion.util;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EditorCommandTest {

    @Test
    void splitsEditorWithArguments() {
        assertEquals(
            java.util.List.of("code", "--wait", "/tmp/x.md"),
            EditorCommand.argv("code --wait", Path.of("/tmp/x.md")));
    }

    @Test
    void respectsSingleQuotes() {
        assertEquals(
            java.util.List.of("vim", "-c", "set nocp", "/tmp/x.md"),
            EditorCommand.argv("vim -c 'set nocp'", Path.of("/tmp/x.md")));
    }
}
