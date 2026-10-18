package org.pubsub.prototype.persistence.adapter;

import org.pubsub.prototype.persistence.core.EpochProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.command.QueryTipCommand;
import org.pubsub.prototype.constants.CardanoConstants;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.math.BigDecimal;

/** Reads the current epoch from the configured Cardano node using {@code cardano-cli query tip}. */
public final class CardanoCliEpochProvider implements EpochProvider {
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;

    /** Record used to deserialize the JSON output of {@code cardano-cli query tip}. */
    private record CardanoTip(@JsonProperty(CardanoConstants.EPOCH_FIELD) Long epoch,
                              @JsonProperty(CardanoConstants.SYNC_PROGRESS_FIELD) String syncProgress) {
    }

    private final CardanoCommandRunner commands;

    public CardanoCliEpochProvider(CardanoCommandRunner commands) {
        this.commands = commands;
    }

    @Override
    public long currentEpoch() {
        String output = commands.run(new QueryTipCommand(commands.networkMagic()));
        try {
            CardanoTip tip = MAPPER.readValue(output, CardanoTip.class);
            if (tip == null || tip.epoch() == null || tip.epoch() < 0) {
                throw new IllegalStateException("Cardano query tip returned an invalid epoch");
            }
            if (tip.syncProgress() == null
            // Check how much % has the node syhnc progress advanced
                    || new BigDecimal(tip.syncProgress()).compareTo(BigDecimal.valueOf(100)) < 0) {
                throw new IllegalStateException("Cardano node is not fully synced: " + tip.syncProgress());
            }
            return tip.epoch();
        } catch (IOException | NumberFormatException ex) {
            throw new IllegalStateException("Unable to parse Cardano query tip output", ex);
        }
    }
}
