package org.pubsub.prototype.registry.topic.cardano;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.cardano.cli.CardanoCommand;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.transaction.CardanoPaymentIdentity;
import org.pubsub.prototype.util.files.TextFiles;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoTopicRegistryAuditTest {
    @TempDir
    Path tempDir;

    @Test
    void logsSigningFailuresAndRethrowsThem() {
        assertFailureLogged("sign");
    }

    @Test
    void logsSubmissionFailuresAndRethrowsThem() {
        assertFailureLogged("submit");
    }

    private void assertFailureLogged(String failingStage) {
        CardanoRegistryConfig config = new CardanoRegistryConfig(
                tempDir.resolve(failingStage).resolve("runtime/registry"),
                "registry-deployer",
                CardanoCliBackend.HOST
        );
        CardanoTopicRegistry registry = new CardanoTopicRegistry(config, new FailingCommandRunner(failingStage));
        CardanoPaymentIdentity signer = new CardanoPaymentIdentity(
                config.signer(),
                "addr_test1",
                tempDir.resolve("payment.skey"),
                tempDir.resolve("payment.vkey")
        );
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.signAndSubmit(tempDir.resolve("transaction.txbody"),
                        tempDir.resolve("transaction.tx"), signer,
                        "TOPIC_CREATED topicId=1", "TOPIC_CREATE_FAILED topicId=1"));

        assertTrue(failure.getMessage().contains(failingStage + " rejected"));
        String auditLog = TextFiles.read(config.transactionLogFile());
        assertTrue(auditLog.contains("TOPIC_CREATE_FAILED topicId=1"));
        assertTrue(auditLog.contains("stage=" + failingStage));
        assertTrue(auditLog.contains("error=" + failingStage + " rejected"));
    }

    private static final class FailingCommandRunner implements CardanoCommandRunner {
        private final String failingStage;

        private FailingCommandRunner(String failingStage) {
            this.failingStage = failingStage;
        }

        @Override
        public String networkMagic() {
            return "42";
        }

        @Override
        public String run(CardanoCommand command) {
            String stage = command.arguments().get(2);
            if (failingStage.equals(stage)) {
                throw new IllegalStateException(stage + " rejected");
            }
            return "accepted";
        }

        @Override
        public void requireCommandExecution() {
        }
    }
}
