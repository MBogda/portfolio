package ru.portfolio.portfolio.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import org.springframework.lang.Nullable;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Builder(toBuilder = true)
public class EventCashFlow {
    @Nullable // autoincrement
    private Integer id;

    @NotNull
    private Instant timestamp;

    @Nullable
    private String isin;

    @Nullable
    private Integer count;

    @NotNull
    @JsonProperty("event-type")
    private CashFlowType eventType;

    @NotNull
    private BigDecimal value;

    @Builder.Default
    private String currency = "RUR";
}
