package com.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    @GetMapping("/api/customers")
    public String customers() {
        return "[]";
    }

    @GetMapping("/api/customers/{id}/canEdit")
    public String canEdit(@PathVariable String id) {
        return "true";
    }
}
