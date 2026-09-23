package com.example.agent.function.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.tool.annotation.ToolParam;


public record Request(@ToolParam(description = "姓名，比如张三") String name , @ToolParam(description = "地点，比如成都")String location) {
}
