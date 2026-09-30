package com.example.agent.chain;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogzHandler implements CustomHandler{
    @Override
    public Object handle(Object obj, HandlerChain chain) {
        log.info("logzHandler before:{}",obj.toString());
        Object rs=chain.handle(obj, chain);
        log.info("logzHandler after:{}",rs.toString());
        return rs;
    }
}
