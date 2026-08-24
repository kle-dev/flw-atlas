package com.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the endpoints the order service model and the onboarding form's REST button call.
 * Atlas links each handler back to the models that call it — see the gutter icons.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderDelegate orderDelegate;

    public OrderController(OrderDelegate orderDelegate) {
        this.orderDelegate = orderDelegate;
    }

    @GetMapping
    public String orders() {
        return "[]";
    }

    @GetMapping("/{orderNumber}")
    public String byNumber(@PathVariable String orderNumber) {
        return "{}";
    }

    @GetMapping("/{company}/risk")
    public String risk(@PathVariable String company) {
        return "{\"level\":\"low\"}";
    }

    @PostMapping("/archive")
    public String archive() {
        return "ok";
    }
}
