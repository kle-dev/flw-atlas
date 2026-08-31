{
  "key": "orderConfirmationTemplate",
  "name": "Order confirmation",
  "description": "Confirmation message sent to the customer once an order is approved.",
  "type": "text",
  "editorJson": {
    "templateType": "text",
    "variationParameters": [{"name": "channel", "defaultValue": "email"}],
    "templateVariations": [
      {
        "parameterValues": {"channel": "email"},
        "text": "Dear ${customerName}, your order ${orderNumber} for ${grandTotal} has been confirmed."
      },
      {
        "parameterValues": {"channel": "sms"},
        "text": "Order ${orderNumber} confirmed."
      }
    ]
  }
}
