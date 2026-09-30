package com.example.agent.chain;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogHandler implements CustomHandler{
    @Override
    public Object handle(Object obj, HandlerChain chain) {
        log.info("logHandler before:{}",obj.toString());
        Object rs=chain.handle("ccc", chain);
        log.info("logHandler after:{}",rs.toString());
        return rs;
    }
}
