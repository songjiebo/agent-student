package com.example.agent.function;

import com.example.agent.function.dto.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证工具调用的正常结果和模型可能传入的无效参数。
 */
class TestFunctionTests {

    private final TestFunction testFunction = new TestFunction();

    /**
     * 有效姓名和地点应返回查询结果。
     */
    @Test
    void returnsResultForValidRequest() {
        assertEquals("37", testFunction.queryByNameAndLocation(new Request("张三", "成都")).message());
    }

    /**
     * 工具参数缺失时应返回业务提示，不抛出空指针异常。
     */
    @Test
    void returnsMessageForMissingRequest() {
        assertEquals("未传入参数,暂时无数据!", testFunction.queryByNameAndLocation(null).message());
        assertEquals("未传入参数,暂时无数据!", testFunction.queryByNameAndLocation(new Request(" ", "成都")).message());
    }
}
