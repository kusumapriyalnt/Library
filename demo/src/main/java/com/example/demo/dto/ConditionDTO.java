package com.example.demo.dto;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ConditionDTO {
    private String entity;
    private String columnName;
    private String condition;
    private String checkOnce;
    private String delayInMin;
    private String message;
    private String trigger;
}
