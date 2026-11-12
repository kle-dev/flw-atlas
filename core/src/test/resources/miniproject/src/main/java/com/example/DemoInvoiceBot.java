package com.example;

/**
 * The bot behind actions/send-invoice.action — a Java bot, where notify-customer.action runs one the
 * platform ships. Between them the fixture resolves an action to both kinds of bot.
 */
public class DemoInvoiceBot implements BotService {

    @Override
    public String getKey() {
        return "DEMO-invoice-bot";
    }

    public void invoke(Object request) {
    }
}
