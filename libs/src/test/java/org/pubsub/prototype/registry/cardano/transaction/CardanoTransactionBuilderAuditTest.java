package org.pubsub.prototype.registry.cardano.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.CardanoRegistryDeploymentManager;
import org.pubsub.prototype.util.TextFiles;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoTransactionBuilderAuditTest {
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
        FailingCommandRunner commandRunner = new FailingCommandRunner(failingStage);
        CardanoTransactionBuilder builder = new CardanoTransactionBuilder(
                config,
                commandRunner,
                new CardanoRegistryDeploymentManager(config, commandRunner)
        );
        CardanoIdentity signer = new CardanoIdentity(
                config.signer(),
                "addr_test1",
                tempDir.resolve("payment.skey"),
                tempDir.resolve("payment.vkey")
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> builder.signAndSubmit(
                tempDir.resolve("transaction.txbody"),
                tempDir.resolve("transaction.tx"),
                signer,
                "TOPIC_CREATED topicId=1",
                "TOPIC_CREATE_FAILED topicId=1"
        ));

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
        public String run(String... arguments) {
            String stage = arguments[2];
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
