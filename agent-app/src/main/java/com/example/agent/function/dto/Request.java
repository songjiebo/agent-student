package com.example.agent.function.dto;

import org.springframework.ai.tool.annotation.ToolParam;


public record Request(@ToolParam(description = "姓名，比如张三") String name , @ToolParam(description = "地点，比如成都")String location) {
}
