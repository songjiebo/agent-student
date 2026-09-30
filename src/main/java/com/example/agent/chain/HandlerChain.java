package com.example.agent.chain;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class HandlerChain implements CustomHandler{

    public List<CustomHandler> handlers = new ArrayList<CustomHandler>();

    private Integer i;

    public HandlerChain(List<CustomHandler> handlers) {
        this.handlers = handlers;
        this.i=0;
    }

    @Override
    public Object handle(Object obj, HandlerChain chain) {
        if(CollectionUtils.isEmpty(handlers)){
            return null;
        }
        if (!"stop".equals(obj.toString())&&i < handlers.size()) {
            return handlers.get(i++).handle(obj, this);
        }
        return "bbb";
    }
}
