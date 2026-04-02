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
import java.util.Map;

@AppianScriptingFunctionsCategory
public class AIExplaination {

    private static final String GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL   = "llama-3.3-70b-versatile";
    private static final String GROQ_API_KEY = "<YOUR_GROQ_API_KEY>";

    @Function
    public String explainAppianObject(
            @Parameter String objectType,
            @Parameter String objectName) {

        if (objectType == null || objectType.trim().isEmpty())
            return "ERROR: objectType is required. Supported: ProcessModel, Interface, ExpressionRule, Integration, RecordType, CDT";
        if (objectName == null || objectName.trim().isEmpty())
            return "ERROR: objectName is required.";

        String norm = objectType.trim().toLowerCase().replaceAll("[^a-z]", "");
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
                    return explainContent(objectName, "Record Type", ContentConstants.TYPE_CUSTOM, -1);
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
        ProcessModel pm = pds.getProcessModel(pmId);

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

                metadata.append("\n  NODE: ").append(nName).append(isStart ? " [START]" : "").append("\n");
                metadata.append("    Smart Service: ").append(schemaName).append("\n");
                if (!schemaDesc.isEmpty())
                    metadata.append("    Description: ").append(schemaDesc).append("\n");

                Object[] params = ac != null ? (Object[]) safeInvoke(ac, "getParameters") : null;
                if (params != null && params.length > 0) {
                    metadata.append("    Inputs:\n");
                    for (Object p : params) {
                        String pName = safeGet(p, "getFriendlyName", safeGet(p, "getName", ""));
                        String expr  = safeGet(p, "getExpression", "");
                        if (!pName.isEmpty() && !expr.isEmpty())
                            metadata.append("      - ").append(pName).append(" = ").append(expr).append("\n");
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
                                metadata.append("    Form: ").append(ifRef.isEmpty() ? uef.getExpression().trim() : ifRef).append("\n");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                Connection[] conns = n.getConnections();
                if (conns != null && conns.length > 0) {
                    metadata.append("    Connects to:\n");
                    for (Connection c : conns) {
                        Long endId = c.getEndNodeGuiId();
                        String label = c.getLabel() != null && !c.getLabel().isEmpty() ? " (condition: " + c.getLabel() + ")" : "";
                        String endName = endId != null ? guiIdToName.getOrDefault(endId, "Node-" + endId) : "?";
                        metadata.append("      -> ").append(endName).append(label).append("\n");
                    }
                }
            }
        }

        String prompt = "You are an expert Appian consultant explaining an Appian Process Model to a fresher "
            + "who has no knowledge of Appian.\n\n"
            + "Here is the complete technical metadata:\n\n" + metadata.toString()
            + "\n\nWrite a detailed human-readable explanation covering: purpose, process variables, "
            + "each node step by step, decision gateways, data flow, and a final summary. "
            + "Write like explaining to a client. Use simple language. Full paragraphs only.";

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
        if (exprObj != null && !exprObj.toString().trim().isEmpty()) {
            String expr = exprObj.toString().trim();
            metadata.append("EXPRESSION (first 3000 chars):\n").append(truncate(expr, 3000)).append("\n\n");

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

        String prompt = "You are an expert Appian consultant explaining an Appian " + displayType
            + " to a fresher who has no knowledge of Appian.\n\n"
            + "Here is the complete technical metadata:\n\n" + metadata.toString()
            + "\n\nWrite a detailed human-readable explanation of what this " + displayType
            + " does, its inputs, outputs, dependencies, and how it fits in the application. "
            + "Write like explaining to a client. Use simple language. Full paragraphs only.";

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
            + "\"max_tokens\":1024}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int status = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
            status == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) response.append(line);
        br.close();

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
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
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
