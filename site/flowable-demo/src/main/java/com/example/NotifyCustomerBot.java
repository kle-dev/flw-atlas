package com.example;

/**
 * The bot behind actions/notify-customer.action. Atlas resolves the action's botKey to this class, so
 * Find Usages on it lists the action, and the gutter icon navigates there.
 */
public class NotifyCustomerBot implements BotService {

    @Override
    public String getKey() {
        return "script-evaluation-bot";
    }

    public void execute(Object request) {
    }
}
