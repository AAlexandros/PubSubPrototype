package org.pubsub.prototype.persistence.adapter;

import org.junit.jupiter.api.Test;
import org.pubsub.prototype.cardano.cli.CardanoCommand;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardanoCliEpochProviderTest {

    @Test
    void readsEpochWhenCardanoTipContainsAdditionalFields() {
        CardanoCommandRunner commands = new CardanoCommandRunner() {
            @Override
            public String networkMagic() {
                return "42";
            }

            @Override
            public String run(CardanoCommand command) {
                return """
                        {
                          "block": 3,
                          "epoch": 0,
                          "era": "Conway",
                          "hash": "e1d30b93337cdd1e53630bcacb4bb772ded53018ba134f6d53143d0828047bfd",
                          "slot": 3,
                          "slotInEpoch": 3,
                          "slotsToEpochEnd": 497,
                          "syncProgress": "100.00"
                        }
                        """;
            }

            @Override
            public void requireCommandExecution() {
            }
        };

        assertEquals(0, new CardanoCliEpochProvider(commands).currentEpoch());
    }
}
