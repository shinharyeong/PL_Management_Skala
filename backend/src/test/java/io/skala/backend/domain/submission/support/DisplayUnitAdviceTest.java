package io.skala.backend.domain.submission.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DisplayUnitAdviceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 금액_필드_옆에_화면_단위_값을_더하고_원_단위와_비율은_그대로_둔다() throws Exception {
        JsonNode node = mapper.readTree("""
                {"amount":"1240000000","plan":null,"rate":"5.0","name":"매출",
                 "children":[{"diff":"-150000000","current":"abc"}],"values":["100000000",null]}
                """);
        DisplayUnitAdvice.addDisplay(node, new DisplayUnitAdvice.Unit(new BigDecimal("100000000"), 1));

        assertThat(node.get("amount").asText()).isEqualTo("1240000000");
        assertThat(node.get("displayAmount").asText()).isEqualTo("12.4");
        assertThat(node.get("displayPlan").isNull()).isTrue();
        assertThat(node.has("displayRate")).isFalse();
        assertThat(node.has("displayName")).isFalse();
        assertThat(node.at("/children/0/displayDiff").asText()).isEqualTo("-1.5");
        assertThat(node.at("/children/0/displayCurrent").isNull()).isTrue();
        assertThat(node.at("/displayValues/0").asText()).isEqualTo("1.0");
        assertThat(node.at("/displayValues/1").isNull()).isTrue();
    }

    @Test
    void 천원은_정수로_반올림() throws Exception {
        JsonNode node = mapper.readTree("{\"amount\":\"1500\",\"baseline\":\"1499\"}");
        DisplayUnitAdvice.addDisplay(node, new DisplayUnitAdvice.Unit(new BigDecimal("1000"), 0));
        assertThat(node.get("displayAmount").asText()).isEqualTo("2");
        assertThat(node.get("displayBaseline").asText()).isEqualTo("1");
    }
}
