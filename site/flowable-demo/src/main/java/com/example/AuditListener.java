package com.example;

import org.springframework.stereotype.Component;

/** Wired as an execution listener in processes/DEMO-onboarding.bpmn20.xml. */
@Component("auditListener")
public class AuditListener {

    public void notify(Object execution) {
        setVariable(execution, "auditedAt", System.currentTimeMillis());
    }

    private void setVariable(Object execution, String name, Object value) {
    }
}
