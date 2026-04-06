package com.appiancs.plugins.aiexplaination.function;

import com.appiancorp.services.ServiceContext;
import com.appiancorp.suiteapi.common.ServiceLocator;
import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentFilter;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.expression.annotations.AppianScriptingFunctionsCategory;
import com.appiancorp.suiteapi.expression.annotations.Function;
import com.appiancorp.suiteapi.expression.annotations.Parameter;
import com.appiancorp.suiteapi.process.ActivityClass;
import com.appiancorp.suiteapi.process.ActivityClassSchema;
import com.appiancorp.suiteapi.process.Connection;
import com.appiancorp.suiteapi.process.ProcessDesignService;
import com.appiancorp.suiteapi.process.ProcessModel;
import com.appiancorp.suiteapi.process.ProcessNode;
import com.appiancorp.suiteapi.process.forms.FormConfig;
import com.appiancorp.suiteapi.process.forms.UiExpressionForm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@AppianScriptingFunctionsCategory
public class AIExplaination {

    private static final String GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL   = "llama-3.3-70b-versatile";
    private static final String GROQ_API_KEY = System.getProperty("groq.api.key", "GROQ_API_KEY_HERE");

    @Function
    public String explainAppianObject(
            @Parameter String objectType,
            @Parameter String objectName,
            @Parameter(required = false) String appianApiKey) {

        if (objectType == null || objectType.trim().isEmpty())
            return "ERROR: objectType is required. Supported: ProcessModel, Interface, ExpressionRule, Integration, RecordType, CDT";
        if (objectName == null || objectName.trim().isEmpty())
            return "ERROR: objectName is required.";

        String norm = objectType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        try {
            switch (norm) {
                case "processmodel": case "process":
                    return explainProcessModel(objectName);
                case "interface": case "ui": case "form": case "sail":
                    return explainContent(objectName, "Interface", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_INTERFACE);
                case "expressionrule": case "rule": case "expression":
                    return explainContent(objectName, "Expression Rule", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_FREEFORM);
                case "integration": case "api":
                    return explainContent(objectName, "Integration", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_OUTBOUND_INTEGRATION);
                case "recordtype": case "record":
                    return explainRecordType(objectName, appianApiKey != null ? appianApiKey.trim() : "");
                case "cdt": case "customdatatype": case "datatype":
                    return explainContent(objectName, "CDT", ContentConstants.TYPE_CUSTOM, -1);
                default:
                    return "Unsupported object type: '" + objectType + "'. Supported: ProcessModel, Interface, ExpressionRule, Integration, RecordType, CDT";
            }
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private String explainProcessModel(String name) throws Exception {
        ServiceContext sc = ServiceLocator.getAdministratorServiceContext();
        ProcessDesignService pds = ServiceLocator.getProcessDesignService(sc);

        ProcessModel.Descriptor[] all = pds.listProcessModels();
        if (all == null || all.length == 0) return "No process models found.";

        ProcessModel.Descriptor found = null;
        for (ProcessModel.Descriptor d : all) {
            try {
                String dName = extractLocaleName(d.getClass().getMethod("getName").invoke(d));
                if (normalise(dName).equals(normalise(name))) { found = d; break; }
            } catch (Exception ignored) {}
        }
        if (found == null) return "Process Model '" + name + "' not found.";

        Long pmId = found.getId();
        ProcessModel pm = pds.exportProcessModel(pmId);

        String pmName  = extractLocaleName(found.getClass().getMethod("getName").invoke(found));
        if (pmName.isEmpty()) pmName = name;
        String desc    = extractLocaleName(found.getClass().getMethod("getDescription").invoke(found));
        String creator = found.getCreatorUsername() != null ? found.getCreatorUsername() : "N/A";
        String updated = found.getTimeStampUpdated() != null ? found.getTimeStampUpdated().toString() : "N/A";

        StringBuilder metadata = new StringBuilder();
        metadata.append("PROCESS MODEL NAME: ").append(pmName).append("\n");
        metadata.append("DESCRIPTION: ").append(desc.isEmpty() ? "Not provided" : desc).append("\n");
        metadata.append("CREATED BY: ").append(creator).append("\n");
        metadata.append("LAST MODIFIED: ").append(updated).append("\n\n");

        Object[] vars = (Object[]) ProcessDesignService.class
            .getMethod("getProcessVariablesForModel", Long.class, boolean.class)
            .invoke(pds, pmId, true);
        metadata.append("PROCESS VARIABLES:\n");
        if (vars != null) {
            for (Object v : vars) {
                String vName    = safeGet(v, "getFriendlyName", safeGet(v, "getName", "unknown"));
                boolean isParam = safeGetBool(v, "isParameter");
                metadata.append("  - ").append(vName)
                        .append(isParam ? " [INPUT PARAMETER]" : " [INTERNAL VARIABLE]").append("\n");
            }
        }
        metadata.append("\n");

        ProcessNode[] nodes = pm.getProcessNodes();
        Map<Long, String> guiIdToName = new HashMap<>();
        if (nodes != null) {
            for (ProcessNode n : nodes) {
                Long gid = n.getGuiId();
                String nName = extractLocaleName(safeInvoke(n, "getFriendlyName"));
                if (nName.isEmpty()) nName = "Node-" + gid;
                if (gid != null) guiIdToName.put(gid, nName);
            }
        }

        // Lanes (Swimlanes)
        try {
            Object[] lanes = (Object[]) pm.getClass().getMethod("getLanes").invoke(pm);
            if (lanes != null && lanes.length > 0) {
                metadata.append("SWIMLANES:\n");
                for (Object lane : lanes) {
                    String laneName = safeGet(lane, "getName", safeGet(lane, "toString", "Unnamed Lane"));
                    metadata.append("  - ").append(laneName).append("\n");
                }
                metadata.append("\n");
            }
        } catch (Exception ignored) {}

        // Annotations (designer notes)
        try {
            Object[] annotations = (Object[]) pm.getClass().getMethod("getAnnotations").invoke(pm);
            if (annotations != null && annotations.length > 0) {
                metadata.append("DESIGNER ANNOTATIONS:\n");
                for (Object ann : annotations) {
                    String text = safeGet(ann, "getText", safeGet(ann, "toString", ""));
                    if (!text.isEmpty()) metadata.append("  - ").append(text).append("\n");
                }
                metadata.append("\n");
            }
        } catch (Exception ignored) {}

        metadata.append("NODES:\n");
        if (nodes != null) {
            for (ProcessNode n : nodes) {
                String nName    = extractLocaleName(safeInvoke(n, "getFriendlyName"));
                if (nName.isEmpty()) nName = "Unnamed";
                boolean isStart = safeGetBool(n, "isStartNode");
                ActivityClass ac = n.getActivityClass();
                String localId  = ac != null && ac.getLocalId() != null ? ac.getLocalId() : "";

                String schemaName = localId;
                String schemaDesc = "";
                if (!localId.isEmpty()) {
                    try {
                        ActivityClassSchema schema = pds.getACSchemaByLocalId(localId);
                        if (schema != null) {
                            String sn = extractLocaleName(safeInvoke(schema, "getName"));
                            if (!sn.isEmpty()) schemaName = sn;
                            String sd = extractLocaleName(safeInvoke(schema, "getDescription"));
                            if (sd != null && !sd.isEmpty()) schemaDesc = sd;
                        }
                    } catch (Exception ignored) {}
                }

                // Node type
                String nodeType = safeGet(n, "getNodeType", "");
                if (nodeType.isEmpty()) nodeType = localId.isEmpty() ? "Unknown" : localId;

                // Assignment (attended/unattended, assignees)
                boolean attended = safeGetBool(n, "isAttended");
                String assignees = safeGet(n, "getAssignees", safeGet(n, "getRunAs", "Process Initiator"));

                metadata.append("\n  NODE: ").append(nName).append(isStart ? " [START]" : "").append("\n");
                metadata.append("    Node Type: ").append(nodeType).append("\n");
                metadata.append("    Smart Service: ").append(schemaName).append("\n");
                metadata.append("    Assignment: ").append(attended ? "attended" : "unattended")
                        .append(", ").append(assignees).append("\n");
                if (!schemaDesc.isEmpty())
                    metadata.append("    Description: ").append(schemaDesc).append("\n");

                Object[] params = ac != null ? (Object[]) safeInvoke(ac, "getParameters") : null;
                if (params != null && params.length > 0) {
                    metadata.append("    DATA TAB (Inputs & Save Values):\n");
                    for (Object p : params) {
                        String pName      = safeGet(p, "getFriendlyName", safeGet(p, "getName", ""));
                        String expr       = safeGet(p, "getExpression", "");
                        String saveInto   = safeGet(p, "getAssignToProcessVariable", "");
                        Object[] interior = (Object[]) safeInvoke(p, "getInteriorExpressions");
                        if (!pName.isEmpty()) {
                            metadata.append("      Parameter: ").append(pName).append("\n");
                            if (!expr.isEmpty())
                                metadata.append("        Input Value: ").append(expr).append("\n");
                            if (!saveInto.isEmpty())
                                metadata.append("        Save Into (process variable): ").append(saveInto).append("\n");
                            if (interior != null && interior.length > 0) {
                                metadata.append("        Interior Expressions:\n");
                                for (Object ie : interior) {
                                    if (ie != null && !ie.toString().trim().isEmpty())
                                        metadata.append("          - ").append(ie.toString().trim()).append("\n");
                                }
                            }
                        }
                    }
                }

                String[] outputs = ac != null ? (String[]) safeInvoke(ac, "getOutputExpressions") : null;
                if (outputs != null) {
                    for (String o : outputs) {
                        if (o != null && !o.trim().isEmpty())
                            metadata.append("    Output: ").append(o.trim()).append("\n");
                    }
                }

                try {
                    if (ac != null) {
                        FormConfig fc = ac.getFormConfig(java.util.Locale.ENGLISH);
                        if (fc != null) {
                            UiExpressionForm uef = fc.getUiExpressionForm();
                            if (uef != null && uef.getExpression() != null && !uef.getExpression().trim().isEmpty()) {
                                String ifRef = extractRuleRef(uef.getExpression().trim());
                                metadata.append("    FORMS TAB:\n");
                                metadata.append("      Interface used: ").append(ifRef.isEmpty() ? uef.getExpression().trim() : ifRef).append("\n");
                                // Extract rule inputs passed to the interface
                                String formExpr = uef.getExpression().trim();
                                java.util.Set<String> riInputs = new java.util.LinkedHashSet<>();
                                int ri = 0;
                                while ((ri = formExpr.indexOf("ri!", ri)) != -1) {
                                    int riEnd = ri + 3;
                                    while (riEnd < formExpr.length() && (Character.isLetterOrDigit(formExpr.charAt(riEnd)) || formExpr.charAt(riEnd) == '_')) riEnd++;
                                    String riName = formExpr.substring(ri + 3, riEnd);
                                    if (!riName.isEmpty()) riInputs.add(riName);
                                    ri = riEnd;
                                }
                                if (!riInputs.isEmpty())
                                    metadata.append("      Rule Inputs passed: ").append(String.join(", ", riInputs)).append("\n");
                                // Extract pv! references (process variables used in form)
                                java.util.Set<String> pvRefs = new java.util.LinkedHashSet<>();
                                int pvi = 0;
                                while ((pvi = formExpr.indexOf("pv!", pvi)) != -1) {
                                    int pvEnd = pvi + 3;
                                    while (pvEnd < formExpr.length() && (Character.isLetterOrDigit(formExpr.charAt(pvEnd)) || formExpr.charAt(pvEnd) == '_')) pvEnd++;
                                    String pvName = formExpr.substring(pvi + 3, pvEnd);
                                    if (!pvName.isEmpty()) pvRefs.add(pvName);
                                    pvi = pvEnd;
                                }
                                if (!pvRefs.isEmpty())
                                    metadata.append("      Process Variables used in form: ").append(String.join(", ", pvRefs)).append("\n");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // XOR conditions
                try {
                    Object[] conditions = (Object[]) safeInvoke(n, "getConditions");
                    if (conditions != null && conditions.length > 0) {
                        metadata.append("    XOR Conditions:\n");
                        for (Object cond : conditions) {
                            String condExpr = safeGet(cond, "getExpression", safeGet(cond, "toString", ""));
                            String condTarget = safeGet(cond, "getTargetNodeGuiId", "");
                            Long targetId = null;
                            try { targetId = Long.parseLong(condTarget); } catch (Exception ignored) {}
                            String targetName = targetId != null ? guiIdToName.getOrDefault(targetId, "Node-" + targetId) : condTarget;
                            if (!condExpr.isEmpty())
                                metadata.append("      If ").append(condExpr).append(" -> ").append(targetName).append("\n");
                        }
                    }
                } catch (Exception ignored) {}

                Connection[] conns = n.getConnections();
                if (conns != null && conns.length > 0) {
                    metadata.append("    Connects to:\n");
                    for (Connection c : conns) {
                        Long endId = c.getEndNodeGuiId();
                        String label = c.getLabel() != null && !c.getLabel().isEmpty() ? " (condition: " + c.getLabel() + ")" : "";
                        boolean chained = safeGetBool(c, "isChained");
                        String endName = endId != null ? guiIdToName.getOrDefault(endId, "Node-" + endId) : "?";
                        metadata.append("      -> ").append(endName).append(label)
                                .append(chained ? " [chained]" : "").append("\n");
                    }
                }
            }
        }

        String prompt = "You are the developer who built this Appian Process Model. "
            + "Explain this process model to a new team member joining your project, "
            + "as if you are doing a knowledge transfer session.\n\n"
            + "Here is the complete technical data of the process model you built:\n\n"
            + metadata.toString()
            + "\n\nNow explain this process model exactly like a developer doing KT (knowledge transfer). Cover:\n"
            + "1. What business problem this process solves and when it gets triggered\n"
            + "2. For each process variable - what data it holds, why you created it, "
            + "whether it is an input or internal variable and how it flows through the process\n"
            + "3. For EVERY node - explain:\n"
            + "   - What this node does in the business context\n"
            + "   - Which smart service is used and why that specific smart service was chosen\n"
            + "   - In the DATA tab - what inputs are configured, which process variables are "
            + "passed as input values, which process variables are used as save values to store the output\n"
            + "   - In the FORMS tab - which interface is attached, what rule inputs are passed "
            + "to that interface and what process variables are mapped to those rule inputs\n"
            + "   - What the node produces and where that output goes next\n"
            + "4. For XOR gateways - explain what condition is being evaluated, "
            + "what each path means in business terms\n"
            + "5. Explain the complete data flow - how data enters, transforms and exits the process\n"
            + "6. End with a complete business flow summary in plain English\n\n"
            + "Use the EXACT variable names, node names, interface names, smart service names from the data above. "
            + "Be very specific and technical. Write in full paragraphs like a developer explaining to another developer.";

        return callGroq(prompt);
    }

    private String explainRecordType(String name, String apiKey) throws Exception {
        if (apiKey.isEmpty())
            return "ERROR: Appian API key is required for Record Type. Use: explainAppianObject(\"RecordType\", \"name\", \"your-api-key\")";

        String baseUrl = "http://tamminademoapps.com:8228";
        StringBuilder metadata = new StringBuilder();

        // Step 1: Get all record types and find matching one
        URL listUrl = new URL(baseUrl + "/suite/api/recordType/v1/recordTypes");
        HttpURLConnection listConn = (HttpURLConnection) listUrl.openConnection();
        listConn.setRequestMethod("GET");
        listConn.setRequestProperty("Appian-API-Key", apiKey);
        listConn.setRequestProperty("Accept", "application/json");
        listConn.setConnectTimeout(15000);
        listConn.setReadTimeout(15000);

        int listStatus = listConn.getResponseCode();
        if (listStatus != 200)
            return "Appian REST API error (" + listStatus + ") fetching record types. Check your API key.";

        StringBuilder listResp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(listConn.getInputStream(), "UTF-8"))) {
            char[] buf = new char[4096];
            int read;
            while ((read = br.read(buf)) != -1) listResp.append(buf, 0, read);
        }
        String listJson = listResp.toString();

        // Find the record type UUID by name
        String normName = normalise(name);
        String matchedUuid = null;
        String matchedName = null;
        int idx = 0;
        while ((idx = listJson.indexOf("\"name\"", idx)) != -1) {
            int colon = listJson.indexOf(":", idx);
            int q1 = listJson.indexOf("\"", colon + 1);
            int q2 = listJson.indexOf("\"", q1 + 1);
            if (q1 != -1 && q2 != -1) {
                String rtName = listJson.substring(q1 + 1, q2);
                if (normalise(rtName).equals(normName) || normalise(rtName).contains(normName)) {
                    matchedName = rtName;
                    // Extract uuid from nearby JSON
                    int uuidIdx = listJson.lastIndexOf("\"uuid\"", idx);
                    if (uuidIdx == -1) uuidIdx = listJson.indexOf("\"uuid\"", idx);
                    if (uuidIdx != -1) {
                        int uc = listJson.indexOf(":", uuidIdx);
                        int uq1 = listJson.indexOf("\"", uc + 1);
                        int uq2 = listJson.indexOf("\"", uq1 + 1);
                        if (uq1 != -1 && uq2 != -1) matchedUuid = listJson.substring(uq1 + 1, uq2);
                    }
                    break;
                }
            }
            idx++;
        }

        if (matchedName == null)
            return "Record Type '" + name + "' not found. Make sure the name matches exactly as it appears in Appian Designer.";

        metadata.append("OBJECT TYPE: Record Type\n");
        metadata.append("NAME: ").append(matchedName).append("\n");

        // Step 2: Get full record type details using uuid
        if (matchedUuid != null && !matchedUuid.isEmpty()) {
            URL detailUrl = new URL(baseUrl + "/api/recordType/v1/recordTypes/" + matchedUuid);
            HttpURLConnection detailConn = (HttpURLConnection) detailUrl.openConnection();
            detailConn.setRequestMethod("GET");
            detailConn.setRequestProperty("Appian-API-Key", apiKey);
            detailConn.setRequestProperty("Accept", "application/json");
            detailConn.setConnectTimeout(15000);
            detailConn.setReadTimeout(15000);

            if (detailConn.getResponseCode() == 200) {
                StringBuilder detailResp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(detailConn.getInputStream(), "UTF-8"))) {
                    char[] buf = new char[4096];
                    int read;
                    while ((read = br.read(buf)) != -1) detailResp.append(buf, 0, read);
                }
                metadata.append("FULL DETAILS:\n").append(truncate(detailResp.toString(), 4000)).append("\n\n");
            }
        } else {
            metadata.append("RAW LIST DATA:\n").append(truncate(listJson, 4000)).append("\n\n");
        }

        String prompt = "You are a senior Appian developer doing a Knowledge Transfer (KT) session to a Business Analyst.\n"
            + "Explain this Appian Record Type based STRICTLY on the data provided below. Do NOT add anything not in the data.\n\n"
            + "Here is the technical data:\n\n" + metadata.toString()
            + "\n\nExplain this Record Type in a clear KT style covering:\n"
            + "1. What this Record Type represents in the business\n"
            + "2. What DATA SOURCE it is connected to\n"
            + "3. What FIELDS are defined - list each field name and type\n"
            + "4. What RECORD ACTIONS are configured - list each by exact name\n"
            + "5. What RELATIONSHIPS exist with other record types\n"
            + "6. What VIEWS or filters are configured\n"
            + "7. A simple summary of how it is used in the application\n\n"
            + "FORMATTING RULES:\n"
            + "- Do NOT use ##, ###, *, **, or any markdown symbols\n"
            + "- Write each section heading in PLAIN CAPITAL LETTERS followed by a colon\n"
            + "- Use a blank line between sections\n"
            + "- Use a dash (-) for bullet points\n"
            + "- Keep it professional, clean and easy to read\n"
            + "Use exact names from the data. Write in plain English. No generic Appian theory.";

        return callGroq(prompt);
    }

    private String explainContent(String name, String displayType, int type, int subtype) throws Exception {
        ServiceContext sc = ServiceLocator.getAdministratorServiceContext();
        ContentService cs = ServiceLocator.getContentService(sc);

        Content found = findContent(cs, name, type, subtype);
        if (found == null)
            return displayType + " '" + name + "' not found. Check the name exactly as it appears in Appian.";

        String objName = found.getName() != null ? found.getName() : name;
        String desc    = found.getDescription() != null ? found.getDescription() : "Not provided";
        String creator = found.getCreator() != null ? found.getCreator() : "N/A";
        String updated = found.getUpdatedTimestamp() != null ? found.getUpdatedTimestamp().toString() : "N/A";
        String created = found.getCreatedTimestamp() != null ? found.getCreatedTimestamp().toString() : "N/A";

        StringBuilder metadata = new StringBuilder();
        metadata.append("OBJECT TYPE: ").append(displayType).append("\n");
        metadata.append("NAME: ").append(objName).append("\n");
        metadata.append("DESCRIPTION: ").append(desc).append("\n");
        metadata.append("CREATED BY: ").append(creator).append("\n");
        metadata.append("CREATED ON: ").append(created).append("\n");
        metadata.append("LAST MODIFIED: ").append(updated).append("\n\n");

        Object exprObj = found.getAttributes() != null ? found.getAttributes().get("expression") : null;
        if (exprObj == null) {
            try {
                Content full = cs.getVersion(found.getId(), ContentConstants.VERSION_CURRENT);
                if (full != null && full.getAttributes() != null) {
                    exprObj = full.getAttributes().get("expression");
                    if (exprObj == null) exprObj = full.getAttributes().get("body");
                    if (exprObj == null) exprObj = full.getAttributes().get("definition");
                    if (exprObj == null) exprObj = full.getAttributes().get("content");
                }
            } catch (Exception ignored) {}
        }
        String expr = exprObj != null ? exprObj.toString().trim() : "";

        if (!expr.isEmpty()) {
            metadata.append("EXPRESSION:\n").append(truncate(expr, 3000)).append("\n\n");

            java.util.Set<String> inputs = new java.util.LinkedHashSet<>();
            int idx = 0;
            while ((idx = expr.indexOf("ri!", idx)) != -1) {
                int end = idx + 3;
                while (end < expr.length() && (Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_')) end++;
                String input = expr.substring(idx + 3, end);
                if (!input.isEmpty()) inputs.add(input);
                idx = end;
            }
            if (!inputs.isEmpty())
                metadata.append("RULE INPUTS: ").append(String.join(", ", inputs)).append("\n");

            java.util.Set<String> rules = new java.util.LinkedHashSet<>();
            idx = 0;
            while ((idx = expr.indexOf("rule!", idx)) != -1) {
                int end = idx + 5;
                while (end < expr.length() && (Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_')) end++;
                String r = expr.substring(idx + 5, end);
                if (!r.isEmpty()) rules.add(r);
                idx = end;
            }
            if (!rules.isEmpty())
                metadata.append("REFERENCED RULES: ").append(String.join(", ", rules)).append("\n");
        }

        String prompt = "You are a senior Appian developer doing a Knowledge Transfer (KT) session to a Business Analyst who has basic Appian knowledge.\n"
            + "Explain this Appian " + displayType + " based STRICTLY on the actual code and metadata provided below. Do NOT add anything that is not in the data.\n\n"
            + "Here is the technical data:\n\n" + metadata.toString()
            + "\n\nExplain this " + displayType + " in a clear KT style covering:\n"
            + "1. What is the purpose of this " + displayType + " - what does it do in simple terms\n"
            + "2. What LAYOUTS are used (e.g. a!formLayout, a!sectionLayout, a!columnsLayout, a!cardLayout etc.) and what they contain\n"
            + "3. What UI COMPONENTS are used (e.g. a!textField, a!dropdownField, a!buttonWidget, a!gridField, a!recordActionField etc.) - list each one and what it does\n"
            + "4. What RULE INPUTS (ri!) are defined and how each one is used inside the interface\n"
            + "5. What RULES (rule!), RECORD TYPES (recordType!), CONSTANTS (cons!) or other objects are referenced - list each by exact name and explain what it does in this interface\n"
            + "6. What LOGIC or CONDITIONS exist (if(), a!localVariables, local!, choose() etc.) and what they control\n"
            + "7. What ACTIONS or EVENTS are triggered (saveInto, a!save, submit buttons, record actions etc.)\n"
            + "8. A simple summary of the full flow - what the analyst sees on screen and what happens when they interact with it\n\n"
            + "FORMATTING RULES:\n"
            + "- Do NOT use ##, ###, *, **, or any markdown symbols\n"
            + "- Write each section heading in PLAIN CAPITAL LETTERS followed by a colon, then explain in normal text below it\n"
            + "- Use a blank line between sections\n"
            + "- Use a dash (-) for bullet points\n"
            + "- Keep it professional, clean and easy to read\n"
            + "Use exact names from the code. Write in plain English. No generic Appian theory.";

        return callGroq(prompt);
    }

    private String callGroq(String prompt) throws Exception {
        URL url = new URL(GROQ_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        String escapedPrompt = prompt
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");

        String body = "{\"model\":\"" + GROQ_MODEL + "\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],"
            + "\"max_tokens\":2048}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int status = conn.getResponseCode();
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                status == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"))) {
            char[] buf = new char[4096];
            int read;
            while ((read = br.read(buf)) != -1) response.append(buf, 0, read);
        }

        if (status != 200)
            return "Groq API error (" + status + "): " + response.toString();

        String json = response.toString();
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return "Could not parse Groq response: " + truncate(json, 200);
        start += marker.length();
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == '"' && (end == 0 || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        String content = json.substring(start, Math.min(end, json.length()));
        content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        if (content.length() > 10000) content = content.substring(0, 10000) + "\n\n[Truncated]";
        return content;
    }

    private String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Content findContent(ContentService cs, String name, int type, int subtype) throws Exception {
        if (type == ContentConstants.TYPE_RULE) {
            try {
                Long rulesRoot = cs.getSystemId(ContentConstants.RULES_ROOT_SYSTEM_ID);
                Content[] results = cs.searchByRoot(rulesRoot, name, new ContentFilter(type));
                if (results != null) {
                    for (Content c : results) {
                        if (normalise(name).equals(normalise(c.getName()))) {
                            if (subtype == -1 || (c.getSubtype() != null && c.getSubtype() == subtype))
                                return c;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (type == ContentConstants.TYPE_CUSTOM) {
            try {
                Long rtFolder = cs.getIdByUuid(ContentConstants.UUID_SYSTEM_RECORD_TYPES_FOLDER);
                if (rtFolder != null) {
                    Content[] r = cs.getChildren(rtFolder, new ContentFilter(type), ContentConstants.VERSION_CURRENT);
                    if (r != null) for (Content c : r) if (normalise(name).equals(normalise(c.getName()))) return c;
                }
            } catch (Exception ignored) {}
            try {
                Long knRoot = cs.getSystemId(ContentConstants.KNOWLEDGE_ROOT_SYSTEM_ID);
                Content[] r2 = cs.searchByRoot(knRoot, name, new ContentFilter(type));
                if (r2 != null) for (Content c : r2) if (normalise(name).equals(normalise(c.getName()))) return c;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String extractRuleRef(String expr) {
        int idx = expr.indexOf("rule!");
        if (idx == -1) return "";
        int end = idx + 5;
        while (end < expr.length() && (Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_')) end++;
        return "rule!" + expr.substring(idx + 5, end);
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    private String extractLocaleName(Object localeString) {
        if (localeString == null) return "";
        String raw = localeString.toString().trim();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            int idx = raw.indexOf("en_US=");
            if (idx != -1) {
                String after = raw.substring(idx + 6);
                int end = after.indexOf(",");
                if (end == -1) end = after.indexOf("}");
                if (end != -1) return after.substring(0, end).trim();
            }
            String inner = raw.substring(1, raw.length() - 1);
            for (String part : inner.split(",")) {
                int eq = part.indexOf("=");
                if (eq != -1) { String val = part.substring(eq + 1).trim(); if (!val.isEmpty()) return val; }
            }
        }
        return raw;
    }

    private Object safeInvoke(Object obj, String method) {
        try { return obj.getClass().getMethod(method).invoke(obj); }
        catch (Exception e) { return null; }
    }

    private String safeGet(Object obj, String method, String fallback) {
        try {
            Object val = obj.getClass().getMethod(method).invoke(obj);
            return val != null ? val.toString().trim() : fallback;
        } catch (Exception e) { return fallback; }
    }

    private boolean safeGetBool(Object obj, String method) {
        try {
            Object val = obj.getClass().getMethod(method).invoke(obj);
            return val instanceof Boolean && (Boolean) val;
        } catch (Exception e) { return false; }
    }
}
