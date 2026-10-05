package org.pubsub.prototype.cardano;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardanoEnvironmentFileTest {
    @TempDir
    Path tempDir;

    @Test
    void readsRequiredValuesAndPreservesEqualsInValues() throws Exception {
        Path file = tempDir.resolve("network.env");
        Files.writeString(file, "CARDANO_NETWORK_MAGIC=42\nTOKEN=a=b\n");

        Map<String, String> values = CardanoEnvironmentFile.readRequired(
                file,
                CardanoEnvironment.NETWORK_MAGIC,
                "TOKEN"
        );

        assertEquals("42", CardanoEnvironmentFile.required(values, CardanoEnvironment.NETWORK_MAGIC));
        assertEquals("a=b", CardanoEnvironmentFile.required(values, "TOKEN"));
    }

    @Test
    void rejectsMissingAndBlankRequiredValues() throws Exception {
        Path file = tempDir.resolve("network.env");
        Files.writeString(file, "CARDANO_NETWORK_MAGIC=\n");

        assertThrows(IllegalArgumentException.class, () -> CardanoEnvironmentFile.readRequired(
                file,
                CardanoEnvironment.NETWORK_MAGIC
        ));
        assertThrows(IllegalArgumentException.class, () -> CardanoEnvironmentFile.readRequired(file, "MISSING"));
    }

    @Test
    void writesValuesThatCanBeReadBack() {
        Path file = tempDir.resolve("runtime/deployment.env");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ADDRESS", "addr_test1");
        expected.put("POLICY", "policy-id");

        CardanoEnvironmentFile.write(file, expected);

        assertEquals(expected, CardanoEnvironmentFile.readRequired(file, "ADDRESS", "POLICY"));
    }

    @Test
    void translatesIoFailuresAtTheUtilityBoundary() {
        assertThrows(IllegalStateException.class, () -> CardanoEnvironmentFile.read(tempDir));
    }
}
