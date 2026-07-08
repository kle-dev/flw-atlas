{
  "key": "demoApp",
  "name": "Demo App",
  "description": "Miniature fixture app for the atlas test suite",
  "groupsAccess": "sales,backoffice",
  "variables": {"appVar": {"type": "string"}},
  "pageModels": [],
  "extension": {
    "design": {
      "childModels": [
        {"key": "orderProcess", "type": "bpmn"},
        {"key": "reviewCase", "type": "cmmn"},
        {"key": "orderForm", "type": "form"},
        {"key": "customerService", "type": "service"},
        {"key": "customerDO", "type": "dataObject"}
      ]
    }
  }
}
