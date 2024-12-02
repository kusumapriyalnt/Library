package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Condition {
    @JsonProperty("Check")
    private String check;

    @JsonProperty("delay in min")
    private String delayInMin;

    @JsonProperty("checkOnce")
    private String checkOnce;
    @JsonProperty("message")
    private String message;
}
