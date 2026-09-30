package com.example.agent.chain;

import java.util.ArrayList;
import java.util.List;

public class ClientChainTest {

    public static void main(String[] args) {
        List<CustomHandler> handlers = new ArrayList<CustomHandler>();
        handlers.add(new LogHandler());
        handlers.add(new LogzHandler());
        HandlerChain chain = new HandlerChain(handlers);
        chain.handle("aaa",chain);
    }

}
