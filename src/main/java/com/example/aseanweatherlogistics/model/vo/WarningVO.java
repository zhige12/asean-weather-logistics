package com.example.aseanweatherlogistics.model.vo;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarningVO {
    private String level;
    private String message;
    private Instant generatedAt;
}
