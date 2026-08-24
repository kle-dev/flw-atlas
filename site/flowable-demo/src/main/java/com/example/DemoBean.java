package com.example;

import org.springframework.stereotype.Component;

@Component("demoBean")
public class DemoBean {

    public void run(Object execution) {
        setVariable("total", 42);
    }

    private void setVariable(String name, Object value) {
    }
}
