package com.example.mcp.mcpserver.service;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;


@Component
public class WeatherService {

    @McpTool(
            name = "getWeather",
            description = "根据城市名称获取天气信息")
    public String getWeather(@McpToolParam(description = "城市名称,例如:成都市，成都") String cityName) {
        System.out.println("调用mcp服务！");
        return String.format("%s未来六天，天气晴朗。", cityName);
    }
}
