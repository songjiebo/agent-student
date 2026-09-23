package com.example.agent.function;

import com.example.agent.function.dto.Request;
import com.example.agent.function.dto.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Description;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 工具类：根据姓名和地点查询同名人数。
 * Spring AI 1.1+ 使用 @Tool 注解标记可被 LLM 调用的工具方法，
 * 替代旧版 Function<T,R> + @Description + bean name 的注册方式。
 */
@Slf4j
@Description("需要知道某地有多少个名字的人")
public class TestFunction {

    /**
     * 根据姓名和地点查询该地有多少个同名的人。
     *
     * @return 查询结果
     */
    @Tool(description = "根据姓名和地点查询该地有多少个同名的人")
    public Response queryByNameAndLocation(
            Request request) {
        // 防御性校验：任一参数为空则返回提示，避免 NPE
        if (Objects.isNull(request.name()) || Objects.isNull(request.location())
                || StringUtils.isEmpty(request.name()) || StringUtils.isEmpty(request.location())) {
            return new Response("未传入参数,暂时无数据!");
        }
        log.info("queryByNameAndLocation -> name:{}, location:{}", request.name(), request.location());
        return new Response("37");
    }
}

