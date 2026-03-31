package com.appiancs.plugins.aiexplaination.function;

import com.appiancorp.suiteapi.expression.annotations.AppianScriptingFunctionsCategory;
import com.appiancorp.suiteapi.expression.annotations.Function;
import com.appiancorp.suiteapi.expression.annotations.Parameter;

import java.util.HashMap;
import java.util.Map;

@AppianScriptingFunctionsCategory
public class AIExplaination {

    // -------------------------------------------------------------------------
    // Alias map: every possible variation → canonical registry key
    // Covers camelCase, spaces, abbreviations, Appian typed-ref prefixes
    // -------------------------------------------------------------------------
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        // Process Model
        ALIAS.put("processmodel",       "processmodel");
        ALIAS.put("processmodels",      "processmodel");
        ALIAS.put("process",            "processmodel");

        // Record Type
        ALIAS.put("recordtype",         "recordtype");
        ALIAS.put("recordtypes",        "recordtype");
        ALIAS.put("record",             "recordtype");

        // Interface
        ALIAS.put("interface",          "interface");
        ALIAS.put("interfaces",         "interface");
        ALIAS.put("ui",                 "interface");
        ALIAS.put("form",               "interface");
        ALIAS.put("sail",               "interface");

        // Expression Rule
        ALIAS.put("expressionrule",     "expressionrule");
        ALIAS.put("expressionrules",    "expressionrule");
        ALIAS.put("rule",               "expressionrule");
        ALIAS.put("rules",              "expressionrule");
        ALIAS.put("expression",         "expressionrule");

        // Integration
        ALIAS.put("integration",        "integration");
        ALIAS.put("integrations",       "integration");
        ALIAS.put("api",                "integration");

        // CDT
        ALIAS.put("cdt",                "cdt");
        ALIAS.put("cdts",               "cdt");
        ALIAS.put("customdatatype",     "cdt");
        ALIAS.put("customdatatypes",    "cdt");
        ALIAS.put("datatype",           "cdt");
        ALIAS.put("type",               "cdt");
    }

    // -------------------------------------------------------------------------
    // Metadata registry: canonical key → explanation
    // -------------------------------------------------------------------------
    private static final Map<String, ObjectMetadata> REGISTRY = new HashMap<>();
    static {
        REGISTRY.put("processmodel", new ObjectMetadata(
            "Process Model",
            "A Process Model defines the workflow logic in Appian. It orchestrates tasks, decisions, and integrations in a visual BPMN-like flow.",
            "Nodes (Start, End, Activity, Gateway), Process Variables, Swimlanes, Timers, Exception Flows",
            "1. Process is triggered (manually, via rule, or event).\n" +
            "2. Nodes execute sequentially or in parallel based on gateways.\n" +
            "3. User tasks route to Appian Tasks; automated nodes call integrations or rules.\n" +
            "4. Process completes or loops based on conditions.",
            "Interfaces (User Input Forms), Integrations (External Calls), Expression Rules (Conditions), CDTs (Data Structures), Record Types (Data Lookup)",
            "An employee onboarding process: Start → Fill Form (Interface) → Approval Gateway → Send Welcome Email (Integration) → End."
        ));

        REGISTRY.put("recordtype", new ObjectMetadata(
            "Record Type",
            "A Record Type is Appian's data layer abstraction. It connects to a data source (database table or process) and exposes data as structured records with relationships.",
            "Record Fields, Relationships (one-to-many, many-to-one), Record Actions, Record Views, Filters, Sync Settings",
            "1. Record Type is configured with a data source (DB table or process model).\n" +
            "2. Fields are mapped to columns or process variables.\n" +
            "3. Relationships link to other Record Types.\n" +
            "4. Record data is synced and queried via a!queryRecordType().",
            "Database Tables (Data Source), Related Record Types (Relationships), Interfaces (Record Views/Actions), Expression Rules (Filters/Conditions)",
            "An 'Employee' Record Type synced to the EMPLOYEE table, with a relationship to 'Department' Record Type, displayed via a Record View Interface."
        ));

        REGISTRY.put("interface", new ObjectMetadata(
            "Interface",
            "An Interface is a reusable UI component in Appian built using SAIL (Self-Assembling Interface Layer). It defines forms, dashboards, and views.",
            "SAIL Components (a!formLayout, a!textField, etc.), Local Variables (local!), Rule Inputs, Component Rules, Display Logic",
            "1. Interface is rendered when called from a Process Task, Record View, or directly.\n" +
            "2. Rule Inputs receive data; local variables manage UI state.\n" +
            "3. User interactions trigger save behaviors or navigation.\n" +
            "4. Data is submitted back to the process or saved via integrations.",
            "Expression Rules (Logic/Validation), Record Types (Data Display), Integrations (API Calls from UI), Process Models (Form Submission), CDTs (Typed Inputs)",
            "A 'Leave Request Form' Interface with employee dropdown (from Record Type), date pickers, and a submit button that starts a Process Model."
        ));

        REGISTRY.put("expressionrule", new ObjectMetadata(
            "Expression Rule",
            "An Expression Rule is a reusable function in Appian that encapsulates business logic, calculations, or data transformations using Appian Expression Language.",
            "Rule Inputs (typed parameters), Return Type, Expression Body, Test Cases",
            "1. Rule is called with typed inputs from Interfaces, Process Models, or other rules.\n" +
            "2. Expression body evaluates using Appian built-in functions.\n" +
            "3. Returns a typed value (Text, Number, CDT, List, etc.).\n" +
            "4. Results are used for display logic, validations, or data mapping.",
            "Interfaces (UI Logic), Process Models (Conditions/Mappings), Record Types (Filters), CDTs (Typed Data), Other Expression Rules (Composition)",
            "A rule 'calculateLeaveBalance(employeeId)' queries the Leave Record Type, applies business logic, and returns remaining leave days shown in an Interface."
        ));

        REGISTRY.put("integration", new ObjectMetadata(
            "Integration",
            "An Integration object in Appian connects to external systems via REST or SOAP APIs. It defines the HTTP request, authentication, and response mapping.",
            "Connected System (Base URL + Auth), HTTP Method, Request Headers/Body, Response Mapping, Error Handling",
            "1. Integration is called from a Process Model node or Interface.\n" +
            "2. Request is built using rule inputs or process variables.\n" +
            "3. Appian sends the HTTP request to the external system.\n" +
            "4. Response is parsed and mapped to CDTs or variables for further use.",
            "Connected Systems (Auth + Base URL), Process Models (Caller), Interfaces (Real-time API calls), CDTs (Request/Response Types), Expression Rules (Request Building)",
            "A 'CreateJiraTicket' Integration called from a Process Model node, sending employee data as JSON to Jira REST API and mapping the ticket ID back to a process variable."
        ));

        REGISTRY.put("cdt", new ObjectMetadata(
            "CDT (Custom Data Type)",
            "A CDT defines a structured, typed data object in Appian — similar to a Java class or database row. It groups related fields into a reusable type.",
            "Fields (name + type: Text, Integer, Date, List, nested CDT), Namespace, XSD Schema backing",
            "1. CDT is defined with fields and types in Appian Designer.\n" +
            "2. Instances are created using a!map() or type!CDTName() constructors.\n" +
            "3. CDTs are passed between Process Models, Interfaces, and Expression Rules.\n" +
            "4. They map to database tables when used with Record Types or process data.",
            "Process Models (Process Variables), Interfaces (Rule Inputs/Outputs), Expression Rules (Typed Parameters), Integrations (Request/Response Bodies), Database Tables (via Record Type)",
            "An 'EmployeeRequest' CDT with fields {name: Text, department: Text, startDate: Date} used as a process variable in onboarding Process Model and as input to a 'CreateEmployee' Integration."
        ));
    }

    // -------------------------------------------------------------------------
    // Normalise: strip everything except a-z, lowercase
    // "RecordType" → "recordtype", "Record Type" → "recordtype"
    // -------------------------------------------------------------------------
    private static String normalise(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase().replaceAll("[^a-z]", "");
    }

    // -------------------------------------------------------------------------
    // DEBUG function — call this first to see EXACTLY what Java receives
    // Usage in Appian: debugInput("RecordType", "SITA Incident Details")
    // -------------------------------------------------------------------------
    @Function
    public String debugInput(
            @Parameter String objectType,
            @Parameter String objectName) {
        String normType = normalise(objectType);
        String aliasKey = ALIAS.get(normType);
        return "RAW objectType   : [" + objectType + "]\n"
             + "RAW objectName   : [" + objectName + "]\n"
             + "NORMALISED type  : [" + normType + "]\n"
             + "ALIAS resolved   : [" + (aliasKey != null ? aliasKey : "NOT FOUND") + "]\n"
             + "REGISTRY has key : [" + (aliasKey != null && REGISTRY.containsKey(aliasKey)) + "]";
    }

    // -------------------------------------------------------------------------
    // MAIN function
    // Usage: explainAppianObject("RecordType", "SITA Incident Details")
    //        explainAppianObject("ProcessModel", "Onboarding Flow")
    //        explainAppianObject("CDT", "EmployeeRequest")
    // -------------------------------------------------------------------------
    @Function
    public String explainAppianObject(
            @Parameter String objectType,
            @Parameter String objectName) {

        if (objectType == null || objectType.trim().isEmpty()) {
            return "ERROR: First parameter (objectType) is empty.\n"
                 + "Supported values: ProcessModel, RecordType, Interface, ExpressionRule, Integration, CDT";
        }

        String normType = normalise(objectType);
        String canonicalKey = ALIAS.get(normType);

        if (canonicalKey == null) {
            return "Unsupported object type: '" + objectType + "' (normalised: '" + normType + "').\n"
                 + "Supported: ProcessModel, RecordType, Interface, ExpressionRule, Integration, CDT\n"
                 + "Tip: Call debugInput(\"" + objectType + "\", \"\") to inspect what Java received.";
        }

        ObjectMetadata meta = REGISTRY.get(canonicalKey);
        String instanceName = (objectName != null) ? objectName.trim() : "";
        return meta.toSummary(instanceName);
    }

    // -------------------------------------------------------------------------
    // Internal model
    // -------------------------------------------------------------------------
    private static class ObjectMetadata {
        final String displayName;
        final String definition;
        final String keyComponents;
        final String workingFlow;
        final String connectedObjects;
        final String realWorldExample;

        ObjectMetadata(String displayName, String definition, String keyComponents,
                       String workingFlow, String connectedObjects, String realWorldExample) {
            this.displayName    = displayName;
            this.definition     = definition;
            this.keyComponents  = keyComponents;
            this.workingFlow    = workingFlow;
            this.connectedObjects = connectedObjects;
            this.realWorldExample = realWorldExample;
        }

        String toSummary(String instanceName) {
            String heading = displayName;
            if (instanceName != null && !instanceName.isEmpty()) {
                heading += " — " + instanceName;
            }
            return "===== " + heading + " =====\n\n"
                 + "[DEFINITION]\n"           + definition      + "\n\n"
                 + "[KEY COMPONENTS]\n"        + keyComponents   + "\n\n"
                 + "[WORKING FLOW]\n"          + workingFlow     + "\n\n"
                 + "[CONNECTED OBJECTS / DEPENDENCIES]\n" + connectedObjects + "\n\n"
                 + "[REAL-TIME EXAMPLE]\n"     + realWorldExample;
        }
    }
}
