package com.example;

import org.springframework.stereotype.Component;

/**
 * Referenced from processes/order.bpmn as ${orderDelegate}. Nothing in Java calls it, which is exactly
 * the case Atlas exists for: without the model link the IDE would offer to delete it.
 */
@Component("orderDelegate")
public class OrderDelegate {

    public void execute(Object execution) {
        String orderId = (String) getVariable(execution, "orderId");
        setVariable(execution, "courierCode", orderId == null ? "UNKNOWN" : "DHL");
    }

    private Object getVariable(Object execution, String name) {
        return null;
    }

    private void setVariable(Object execution, String name, Object value) {
    }
}
