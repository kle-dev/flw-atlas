{
  "key": "onboardingApp",
  "name": "Customer Onboarding",
  "description": "Onboards a new customer, reviews the application and scores its risk.",
  "groupsAccess": "sales,compliance",
  "variables": {
    "campaign": {
      "type": "string"
    }
  },
  "pageModels": [
    {
      "key": "customerOverviewPage",
      "accessPermissions": "sales"
    }
  ],
  "extension": {
    "design": {
      "childModels": [
        {
          "key": "DEMO-onboarding",
          "type": "bpmn"
        },
        {
          "key": "DEMO-reviewCase",
          "type": "cmmn"
        },
        {
          "key": "dec_risk",
          "type": "dmn"
        },
        {
          "key": "onboardingForm",
          "type": "form"
        },
        {
          "key": "customerOverviewPage",
          "type": "page"
        },
        {
          "key": "supportAgent",
          "type": "agent"
        },
        {
          "key": "orderService",
          "type": "service"
        },
        {
          "key": "orderDO",
          "type": "dataObject"
        },
        {
          "key": "orderTypes",
          "type": "dataDictionary"
        },
        {
          "key": "customerEventsChannel",
          "type": "channel"
        },
        {
          "key": "customerCreated",
          "type": "event"
        },
        {
          "key": "orderNumberSequence",
          "type": "sequence"
        },
        {
          "key": "openOrdersQuery",
          "type": "query"
        },
        {
          "key": "orderConfirmationTemplate",
          "type": "template"
        },
        {
          "key": "approvalSla",
          "type": "sla"
        },
        {
          "key": "legacyUnusedForm",
          "type": "form"
        }
      ]
    }
  }
}
