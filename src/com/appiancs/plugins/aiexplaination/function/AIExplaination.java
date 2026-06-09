package com.appiancs.plugins.aiexplaination.function;

import com.appiancorp.suiteapi.security.external.SecureCredentialsStore;
import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentFilter;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.expression.annotations.AppianScriptingFunctionsCategory;
import com.appiancorp.suiteapi.expression.annotations.Function;
import com.appiancorp.suiteapi.expression.annotations.Parameter;
import com.appiancorp.suiteapi.process.*;
import com.appiancorp.suiteapi.process.forms.FormConfig;
import com.appiancorp.suiteapi.process.forms.UiExpressionForm;
import com.appiancorp.suiteapi.type.TypeService;
import com.appiancorp.suiteapi.type.Datatype;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

@AppianScriptingFunctionsCategory
public class AIExplaination {

    @Function
    public String explainAppianObject(
            ProcessDesignService pds,
            ContentService cs,
            SecureCredentialsStore scs,
            TypeService ts,
            @Parameter String objectType,
            @Parameter String objectName,
            @Parameter String externalSystemKey) {

        if (objectType == null || objectType.trim().isEmpty())
            return "ERROR: objectType is required. Supported: ProcessModel, Interface, ExpressionRule, Integration, CDT, RecordType";
        if (objectName == null || objectName.trim().isEmpty())
            return "ERROR: objectName is required.";
        if (externalSystemKey == null || externalSystemKey.trim().isEmpty())
            return "ERROR: externalSystemKey is required.";

        java.util.Map<String, String> credentials;
        try {
            credentials = scs.getSystemSecuredValues(externalSystemKey);
            if (credentials == null || credentials.isEmpty())
                return "ERROR: No credentials found for external system key: " + externalSystemKey;
        } catch (Exception e) {
            return "ERROR: Failed to retrieve credentials: " + e.getMessage();
        }

        String apiKey = credentials.get("apikey");
        String provider = credentials.get("provider");
        
        if (apiKey == null || apiKey.trim().isEmpty())
            return "ERROR: API Key not found in credentials. Add 'apikey' field in Third-Party Credentials.";
        if (provider == null || provider.trim().isEmpty())
            provider = "claude";

        String norm = objectType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        try {
            switch (norm) {
                case "processmodel": case "process":
                    return explainProcessModel(pds, objectName, apiKey, provider);
                case "interface": case "ui": case "form": case "sail":
                    return explainContent(cs, objectName, "Interface", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_INTERFACE, apiKey, provider);
                case "expressionrule": case "rule": case "expression":
                    return explainContent(cs, objectName, "Expression Rule", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_FREEFORM, apiKey, provider);
                case "integration": case "api":
                    return explainContent(cs, objectName, "Integration", ContentConstants.TYPE_RULE, ContentConstants.SUBTYPE_RULE_OUTBOUND_INTEGRATION, apiKey, provider);
                case "cdt": case "datatype": case "customdatatype":
                    return explainCDT(ts, objectName, apiKey, provider);
                case "recordtype": case "record":
                    return explainRecordType(ts, objectName, apiKey, provider);
                default:
                    return "Unsupported object type: '" + objectType + "'. Supported: ProcessModel, Interface, ExpressionRule, Integration, CDT, RecordType";
            }
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private String explainProcessModel(ProcessDesignService pds, String name, String apiKey, String provider) throws Exception {
        ProcessModel.Descriptor[] all = pds.listProcessModels();
        if (all == null || all.length == 0) return "No process models found.";

        ProcessModel.Descriptor found = null;
        for (ProcessModel.Descriptor d : all) {
            String dName = d.toString();
            if (dName != null && normalise(dName).contains(normalise(name))) { found = d; break; }
        }
        if (found == null) return "Process Model '" + name + "' not found.";

        Long pmId = found.getId();
        ProcessModel pm = pds.exportProcessModel(pmId);

        String pmName = name;
        String desc = "";
        String creator = found.getCreatorUsername() != null ? found.getCreatorUsername() : "N/A";
        String updated = found.getTimeStampUpdated() != null ? found.getTimeStampUpdated().toString() : "N/A";

        StringBuilder metadata = new StringBuilder();
        metadata.append("PROCESS MODEL NAME: ").append(pmName).append("\n");
        metadata.append("DESCRIPTION: ").append(desc.isEmpty() ? "Not provided" : desc).append("\n");
        metadata.append("CREATED BY: ").append(creator).append("\n");
        metadata.append("LAST MODIFIED: ").append(updated).append("\n\n");

        // Process Variables
        try {
            Object[] vars = pds.getProcessVariablesForModel(pmId, true);
            metadata.append("PROCESS VARIABLES:\n");
            if (vars != null && vars.length > 0) {
                for (Object v : vars) {
                    if (v != null) {
                        String vName = v.toString();
                        metadata.append("  - ").append(vName).append("\n");
                    }
                }
            }
            metadata.append("\n");
        } catch (Exception ignored) {}

        ProcessNode[] nodes = pm.getProcessNodes();
        Map<Long, String> guiIdToName = new HashMap<>();
        if (nodes != null) {
            for (ProcessNode n : nodes) {
                Long gid = n.getGuiId();
                String nName = "Node-" + gid;
                if (gid != null) guiIdToName.put(gid, nName);
            }
        }

        // Swimlanes - skip if not accessible
        // Annotations - skip if not accessible

        metadata.append("NODES:\n");
        if (nodes != null) {
            for (ProcessNode n : nodes) {
                String nName = "Node-" + n.getGuiId();
                ActivityClass ac = n.getActivityClass();
                String localId = ac != null && ac.getLocalId() != null ? ac.getLocalId() : "";

                String schemaName = localId;
                String schemaDesc = "";
                if (!localId.isEmpty()) {
                    try {
                        ActivityClassSchema schema = pds.getACSchemaByLocalId(localId);
                        if (schema != null) {
                            schemaName = localId;
                            schemaDesc = "";
                        }
                    } catch (Exception ignored) {}
                }

                String nodeType = localId.isEmpty() ? "Unknown" : localId;

                metadata.append("\n  NODE: ").append(nName).append("\n");
                metadata.append("    Node Type: ").append(nodeType).append("\n");
                metadata.append("    Smart Service: ").append(schemaName).append("\n");
                if (!schemaDesc.isEmpty())
                    metadata.append("    Description: ").append(schemaDesc).append("\n");

                // Parameters (DATA TAB)
                try {
                    if (ac != null) {
                        Object[] params = ac.getParameters();
                        if (params != null && params.length > 0) {
                            metadata.append("    DATA TAB (Inputs & Save Values):\n");
                            for (Object p : params) {
                                if (p != null) {
                                    metadata.append("      Parameter: ").append(p.toString()).append("\n");
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Outputs
                try {
                    if (ac != null) {
                        String[] outputs = ac.getOutputExpressions();
                        if (outputs != null) {
                            for (String o : outputs) {
                                if (o != null && !o.trim().isEmpty())
                                    metadata.append("    Output: ").append(o.trim()).append("\n");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Forms (FORMS TAB)
                try {
                    if (ac != null) {
                        FormConfig fc = ac.getFormConfig(Locale.ENGLISH);
                        if (fc != null) {
                            UiExpressionForm uef = fc.getUiExpressionForm();
                            if (uef != null && uef.getExpression() != null && !uef.getExpression().trim().isEmpty()) {
                                String ifRef = extractRuleRef(uef.getExpression().trim());
                                metadata.append("    FORMS TAB:\n");
                                metadata.append("      Interface used: ").append(ifRef.isEmpty() ? uef.getExpression().trim() : ifRef).append("\n");
                                
                                String formExpr = uef.getExpression().trim();
                                Set<String> riInputs = new LinkedHashSet<>();
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
                                
                                Set<String> pvRefs = new LinkedHashSet<>();
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

                // XOR Conditions - skip if not accessible

                // Connections
                try {
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
                } catch (Exception ignored) {}
            }
        }

        String header = "OBJECT TYPE: Process Model\n"
            + "OBJECT NAME: " + pmName + "\n"
            + "CREATED BY: " + creator + "\n"
            + "LAST MODIFIED: " + updated + "\n"
            + "---\n\n";
            
        String prompt = "You are a Business Analyst explaining an Appian Process Model to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a structured explanation.\n"
            + "Do NOT use markdown symbols (##, **, *, --)\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Answer these sections exactly:\n\n"
            + "1. PURPOSE\n"
            + "Why does this process exist? What business problem does it solve?\n\n"
            + "2. BUSINESS FLOW\n"
            + "What is the end-to-end business flow?\n"
            + "Describe each step in plain English like: Request submitted -> Manager reviews -> HR approves\n\n"
            + "3. START TRIGGER\n"
            + "How is this process started? (Manual / API / Event / Timer / Form submission)\n\n"
            + "4. ACTORS\n"
            + "Who is involved in this process? (e.g., Employee, Manager, HR, System)\n\n"
            + "5. INPUTS\n"
            + "What data enters this process? List each input parameter and what it represents.\n\n"
            + "6. OUTPUTS\n"
            + "What does this process produce or trigger at the end?\n\n"
            + "7. NODE-BY-NODE EXPLANATION\n"
            + "For EVERY node in the process, explain:\n"
            + "- Node Name: (exact name)\n"
            + "- Node Type: (User Task / Script Task / Integration / Gateway / Start / End etc.)\n"
            + "- Business Purpose: Why is this node here? What business action does it perform?\n"
            + "- Smart Service Used: Which smart service or activity is configured and why was it chosen?\n"
            + "- Inputs Configured: What data is passed into this node? Which process variables are used as inputs?\n"
            + "- Outputs / Save Values: What does this node produce? Which process variables store the result?\n"
            + "- Interface Attached (if any): Which interface is shown to the user? What rule inputs are passed to it?\n"
            + "- Conditions / Rules (if any): What XOR conditions or routing rules are applied after this node?\n"
            + "- Next Step: Where does the flow go after this node?\n\n"
            + "8. LOGIC / DECISION POINTS\n"
            + "What decisions or conditions exist? (e.g., If approved -> notify employee, If rejected -> send back)\n\n"
            + "9. DEPENDENCIES\n"
            + "What interfaces, rules, integrations, or other objects does this process use?\n\n"
            + "10. BUSINESS IMPACT\n"
            + "How does this process help the business? What would break if it did not exist?\n\n"
            + "11. DETAILED SUMMARY\n"
            + "Write a detailed pin-to-pin summary covering:\n"
            + "- What triggers this process and why\n"
            + "- Every major step and what happens at each step\n"
            + "- What data flows through the process from start to end\n"
            + "- Who does what at each stage\n"
            + "- What the final outcome is and how it impacts the business\n"
            + "Write this as a full paragraph narrative, not bullet points.\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on WHY and WHAT not HOW\n"
            + "- Use exact node names, variable names, interface names from the data";

        return header + callAI(prompt, apiKey, provider);
    }

    private String explainContent(ContentService cs, String name, String displayType, int type, int subtype, String apiKey, String provider) throws Exception {
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

        String contentHeader = "OBJECT TYPE: " + displayType + "\n"
            + "OBJECT NAME: " + objName + "\n"
            + "CREATED BY: " + creator + "\n"
            + "CREATED ON: " + created + "\n"
            + "---\n\n";

        String prompt = "You are a Business Analyst explaining an Appian " + displayType + " to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a comprehensive structured explanation.\n"
            + "Do NOT use markdown symbols (##, **, *, --)\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Generate a detailed explanation with these sections:\n\n"
            + "1. PURPOSE - Why this exists and what business need it addresses\n"
            + "2. INPUTS - All parameters and what they represent\n"
            + "3. OUTPUTS - What it returns or triggers\n"
            + "4. FUNCTIONALITIES - Key features and capabilities\n"
            + "5. BUSINESS FLOW - Step-by-step flow from input to output\n"
            + "6. LOGIC / DECISION POINTS - Conditional logic and validations\n"
            + "7. DEPENDENCIES - Referenced rules, integrations, constants\n"
            + "8. BUSINESS IMPACT - Business value and impact if it fails\n"
            + "9. SUMMARY - Comprehensive 3-5 sentence summary\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on business value\n"
            + "- Be thorough and detailed";

        return contentHeader + callAI(prompt, apiKey, provider);
    }

    private String explainCDT(TypeService ts, String name, String apiKey, String provider) throws Exception {
        StringBuilder metadata = new StringBuilder();
        Object match = null;
        java.util.List<String> cdtNames = new java.util.ArrayList<>();

        try {
            // Use reflection to access getTypesPaging method
            int startIndex = 0, batchSize = 100;
            while (true) {
                Object page = ts.getClass().getMethod("getTypesPaging", int.class, int.class, Integer.class, Integer.class)
                    .invoke(ts, startIndex, batchSize, null, null);
                if (page == null) break;
                Object[] results = (Object[]) page.getClass().getMethod("getResults").invoke(page);
                if (results == null || results.length == 0) break;
                for (Object dt : results) {
                    if (safeGetBool(dt, "isRecordType")) continue;
                    if (safeGetBool(dt, "isSystemType")) continue;
                    if (safeGetBool(dt, "isListType")) continue;
                    if (safeGetBool(dt, "isExternal")) continue;
                    String ns = safeGet(dt, "getNamespace", "");
                    if (!ns.contains("appian:types")) continue;

                    // getNameWithinNamespace returns "SA_Student" or "SA_Student?list" - strip ?list
                    String withinNs = safeGet(dt, "getNameWithinNamespace", "");
                    if (withinNs.contains("?")) withinNs = withinNs.substring(0, withinNs.indexOf("?"));
                    if (withinNs.isEmpty()) continue;

                    cdtNames.add(withinNs);

                    if (normalise(withinNs).equals(normalise(name)) || normalise(withinNs).contains(normalise(name))) {
                        match = dt; break;
                    }
                }
                if (match != null) break;
                if (results.length < batchSize) break;
                startIndex += batchSize;
            }
        } catch (Exception e) {
            return "ERROR scanning CDTs: " + e.getMessage();
        }

        if (match == null) {
            StringBuilder sb = new StringBuilder("CDT '" + name + "' not found.\n\nAvailable CDTs:\n");
            for (String n2 : cdtNames) sb.append("  - ").append(n2).append("\n");
            if (cdtNames.isEmpty()) sb.append("  No CDTs found.\n");
            return sb.toString();
        }

        metadata.append("OBJECT TYPE: CDT (Custom Data Type)\n");
        String cdtDisplayName = safeGet(match, "getNameWithinNamespace", safeGet(match, "getLocalName", name));
        if (cdtDisplayName.contains("?")) cdtDisplayName = cdtDisplayName.substring(0, cdtDisplayName.indexOf("?"));
        metadata.append("NAME: ").append(cdtDisplayName).append("\n");
        metadata.append("NAMESPACE: ").append(safeGet(match, "getNamespace", "N/A")).append("\n");
        metadata.append("DESCRIPTION: ").append(safeGet(match, "getLocalDescription", safeGet(match, "getDescription", "Not provided"))).append("\n");
        metadata.append("CREATED BY: ").append(safeGet(match, "getCreator", "N/A")).append("\n");
        metadata.append("CREATED ON: ").append(safeGet(match, "getCreationTime", "N/A")).append("\n\n");

        Object[] props = null;
        try { props = (Object[]) match.getClass().getMethod("getInstanceProperties").invoke(match); } catch (Exception ignored) {}
        if (props != null && props.length > 0) {
            metadata.append("FIELDS:\n");
            for (Object prop : props) {
                String fName = safeGet(prop, "getLocalName", safeGet(prop, "getName", ""));
                String fType = resolveTypeName(safeGet(prop, "getInstanceType", ""));
                if (!fName.isEmpty())
                    metadata.append("  - ").append(fName).append(" (").append(fType).append(")\n");
            }
            metadata.append("\n");
        }

        String header = "OBJECT TYPE: CDT (Custom Data Type)\n"
            + "OBJECT NAME: " + cdtDisplayName + "\n"
            + "NAMESPACE: " + safeGet(match, "getNamespace", "N/A") + "\n"
            + "---\n\n";

        String prompt = "You are a Business Analyst explaining an Appian CDT (Custom Data Type) to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a comprehensive explanation.\n"
            + "Do NOT use markdown symbols (##, **, *, --)\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Generate a detailed explanation with these sections:\n\n"
            + "1. PURPOSE - What business data structure does this CDT represent?\n"
            + "2. FIELDS - List each field and explain what business data it stores\n"
            + "3. BUSINESS USE CASES - How is this CDT used in the application?\n"
            + "4. DATA RELATIONSHIPS - How does this relate to other data structures?\n"
            + "5. BUSINESS VALUE - Why is this CDT important?\n"
            + "6. SUMMARY - Comprehensive 3-5 sentence summary\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on business meaning\n"
            + "- Use exact field names from the data";

        return header + callAI(prompt, apiKey, provider);
    }

    private String explainRecordType(TypeService ts, String name, String apiKey, String provider) throws Exception {
        StringBuilder metadata = new StringBuilder();
        Object match = null;
        java.util.List<String> rtNames = new java.util.ArrayList<>();

        try {
            // Use reflection to access getTypesPaging method
            int startIndex = 0;
            int batchSize = 100;
            while (true) {
                Object page = ts.getClass().getMethod("getTypesPaging", int.class, int.class, Integer.class, Integer.class)
                    .invoke(ts, startIndex, batchSize, null, null);
                if (page == null) break;
                Object[] results = (Object[]) page.getClass().getMethod("getResults").invoke(page);
                if (results == null || results.length == 0) break;
                for (Object dt : results) {
                    boolean isRT = safeGetBool(dt, "isRecordType");
                    if (!isRT) continue;
                    String localName = safeGet(dt, "getLocalName", safeGet(dt, "getName", ""));
                    rtNames.add(localName);
                    if (normalise(localName).equals(normalise(name)) || normalise(localName).contains(normalise(name))) {
                        match = dt;
                        break;
                    }
                }
                if (match != null) break;
                if (results.length < batchSize) break;
                startIndex += batchSize;
            }
        } catch (Exception e) {
            return "ERROR scanning types: " + e.getMessage();
        }

        if (match == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Record Type '" + name + "' not found.\n\n");
            sb.append("Available Record Types (use one of these exact names):\n");
            for (String n2 : rtNames) sb.append("  - ").append(n2).append("\n");
            if (rtNames.isEmpty()) sb.append("  No Record Types found.\n");
            return sb.toString();
        }

        metadata.append("OBJECT TYPE: Record Type\n");
        metadata.append("NAME: ").append(safeGet(match, "getLocalName", name)).append("\n");
        metadata.append("NAMESPACE: ").append(safeGet(match, "getNamespace", "N/A")).append("\n");
        metadata.append("DESCRIPTION: ").append(safeGet(match, "getLocalDescription", safeGet(match, "getDescription", "Not provided"))).append("\n");
        metadata.append("CREATED BY: ").append(safeGet(match, "getCreator", "N/A")).append("\n");
        metadata.append("CREATED ON: ").append(safeGet(match, "getCreationTime", "N/A")).append("\n\n");

        // Fields
        Object[] props = null;
        try { props = (Object[]) match.getClass().getMethod("getInstanceProperties").invoke(match); } catch (Exception ignored) {}
        if (props != null && props.length > 0) {
            metadata.append("FIELDS:\n");
            for (Object prop : props) {
                String fName = safeGet(prop, "getLocalName", safeGet(prop, "getName", ""));
                String fTypeId = safeGet(prop, "getInstanceType", "");
                String fType = resolveTypeName(fTypeId);
                if (!fName.isEmpty())
                    metadata.append("  - ").append(fName).append(" (").append(fType).append(")\n");
            }
            metadata.append("\n");
        }

        // Type properties (record actions, data source, views etc.)
        Object[] typeProps = null;
        try { typeProps = (Object[]) match.getClass().getMethod("getTypeProperties").invoke(match); } catch (Exception ignored) {}
        if (typeProps != null && typeProps.length > 0) {
            metadata.append("CONFIGURATION:\n");
            for (Object tp : typeProps) {
                String tpName = safeGet(tp, "getLocalName", safeGet(tp, "getName", ""));
                String tpVal  = safeGet(tp, "getValue", "");
                if (!tpName.isEmpty() && !tpVal.isEmpty())
                    metadata.append("  - ").append(tpName).append(": ").append(truncate(tpVal, 200)).append("\n");
            }
            metadata.append("\n");
        }

        String header = "OBJECT TYPE: Record Type\n"
            + "OBJECT NAME: " + safeGet(match, "getLocalName", name) + "\n"
            + "NAMESPACE: " + safeGet(match, "getNamespace", "N/A") + "\n"
            + "---\n\n";

        String prompt = "You are a Business Analyst explaining an Appian Record Type to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a comprehensive explanation.\n"
            + "Do NOT use markdown symbols (##, **, *, --)\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Generate a detailed explanation with these sections:\n\n"
            + "1. PURPOSE - What business entity does this record type represent?\n"
            + "2. DATA SOURCE - What database table or data source is connected?\n"
            + "3. FIELDS - List each field and explain what business data it stores\n"
            + "4. RECORD ACTIONS - What actions are configured (if any)?\n"
            + "5. RELATIONSHIPS - How does this relate to other record types?\n"
            + "6. BUSINESS USE CASES - How is this record type used in the application?\n"
            + "7. BUSINESS VALUE - Why is this record type important?\n"
            + "8. SUMMARY - Comprehensive 3-5 sentence summary\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on business meaning\n"
            + "- Use exact field names from the data\n"
            + "- If data is not available for a section, skip it";

        return header + callAI(prompt, apiKey, provider);
    }
    
    private String safeGet(Object obj, String methodName, String defaultValue) {
        try {
            Object result = obj.getClass().getMethod(methodName).invoke(obj);
            return result != null ? result.toString() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
    
    private boolean safeGetBool(Object obj, String methodName) {
        try {
            Object result = obj.getClass().getMethod(methodName).invoke(obj);
            return result != null && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private String callAI(String prompt, String apiKey, String provider) throws Exception {
        if (apiKey == null || apiKey.isEmpty())
            return "ERROR: API key not configured.";

        String p = provider != null ? provider.toLowerCase().trim() : "";
        boolean isClaude = p.equals("claude") || apiKey.startsWith("sk-ant");
        boolean isOpenAI = p.equals("openai") || p.equals("gpt") || (apiKey.startsWith("sk-") && !apiKey.startsWith("sk-ant"));
        boolean isGemini = p.equals("gemini") || apiKey.startsWith("AIza");

        String url, model;
        if (isClaude) {
            url   = "https://api.anthropic.com/v1/messages";
            model = "claude-haiku-4-5-20251001";
        } else if (isOpenAI) {
            url   = "https://api.openai.com/v1/chat/completions";
            model = "gpt-4o-mini";
        } else if (isGemini) {
            url   = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
            model = "gemini-1.5-flash";
        } else {
            url   = "https://api.groq.com/openai/v1/chat/completions";
            model = "llama-3.3-70b-versatile";
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        if (isClaude) {
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else if (!isGemini) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        String escapedPrompt = prompt
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");

        String body;
        if (isClaude) {
            body = "{\"model\":\"" + model + "\",\"max_tokens\":4096,\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}]}";
        } else if (isGemini) {
            body = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapedPrompt + "\"}]}]}";
        } else {
            body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],\"max_tokens\":4096}";
        }

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
            return "API error (" + status + "): " + response.toString();

        String json = response.toString();
        String marker;
        if (isClaude)       marker = "\"text\":\"";
        else if (isGemini)  marker = "\"text\": \"";
        else                marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return "Could not parse API response: " + truncate(json, 200);
        start += marker.length();
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == '"' && (end == 0 || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        String content = json.substring(start, Math.min(end, json.length()));
        content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        if (content.length() > 50000) content = content.substring(0, 50000) + "\n\n[Truncated]";
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
        return null;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
    
    private String resolveTypeName(String typeId) {
        switch (typeId) {
            case "1":  return "Number (Integer)";
            case "2":  return "Number (Double)";
            case "3":  return "Text";
            case "4":  return "Boolean";
            case "5":  return "Date and Time";
            case "6":  return "Time";
            case "7":  return "Date";
            case "8":  return "Document";
            case "11": return "User";
            case "12": return "Group";
            case "22": return "Number (Decimal)";
            default:   return typeId.isEmpty() ? "Unknown" : "Type(" + typeId + ")";
        }
    }

    private String extractRuleRef(String expr) {
        int idx = expr.indexOf("rule!");
        if (idx == -1) return "";
        int end = idx + 5;
        while (end < expr.length() && (Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_')) end++;
        return "rule!" + expr.substring(idx + 5, end);
    }
}
