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
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY_HERE";

    @Function
    public String explainAppianObject(
            @Parameter String objectType,
            @Parameter String objectName) {

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
                    return explainRecordType(objectName, "");
                case "listcdts":
                    return listCdts();
                case "cdt": case "customdatatype": case "datatype":
                    return explainCdt(objectName);
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

        return header + callGroq(prompt);
    }

    private String listCdts() {
        try {
            ServiceContext sc = ServiceLocator.getAdministratorServiceContext();
            ContentService cs = ServiceLocator.getContentService(sc);
            Long root = cs.getSystemId(ContentConstants.RULES_ROOT_SYSTEM_ID);
            Content[] all = cs.searchByRoot(root, null, new ContentFilter(ContentConstants.TYPE_RULE));
            StringBuilder sb = new StringBuilder("ALL RULE SUBTYPES FOUND:\n\n");
            java.util.Map<Integer, java.util.List<String>> bySubtype = new java.util.TreeMap<>();
            if (all != null) {
                for (Content c : all) {
                    int sub = c.getSubtype() != null ? c.getSubtype() : -1;
                    bySubtype.computeIfAbsent(sub, k -> new java.util.ArrayList<>()).add(c.getName());
                }
            }
            for (java.util.Map.Entry<Integer, java.util.List<String>> e : bySubtype.entrySet()) {
                sb.append("subtype=").append(e.getKey()).append(" (").append(e.getValue().size()).append(" objects):\n");
                for (String n : e.getValue()) sb.append("  - ").append(n).append("\n");
            }
            if (bySubtype.isEmpty()) sb.append("Nothing found.");
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String explainCdt(String name) throws Exception {
        ServiceContext sc = ServiceLocator.getAdministratorServiceContext();
        ContentService cs = ServiceLocator.getContentService(sc);
        Long root = cs.getSystemId(ContentConstants.RULES_ROOT_SYSTEM_ID);
        Content[] all = cs.searchByRoot(root, name, new ContentFilter(ContentConstants.TYPE_RULE));

        Content match = null;
        java.util.List<String> available = new java.util.ArrayList<>();
        if (all != null) {
            for (Content c : all) {
                if (c.getSubtype() == null) continue;
                if (c.getSubtype() != 7) continue; // CDT subtype
                available.add(c.getName());
                if (normalise(c.getName()).equals(normalise(name))) { match = c; break; }
            }
        }

        if (match == null) {
            StringBuilder sb = new StringBuilder("CDT '" + name + "' not found.");
            if (!available.isEmpty()) {
                sb.append("\n\nSimilar CDTs found:\n");
                for (String n : available) sb.append("  - ").append(n).append("\n");
            }
            return sb.toString();
        }

        // Get full version with attributes
        Content full = null;
        try { full = cs.getVersion(match.getId(), ContentConstants.VERSION_CURRENT); } catch (Exception ignored) {}

        StringBuilder metadata = new StringBuilder();
        metadata.append("OBJECT TYPE: CDT (Custom Data Type)\n");
        metadata.append("NAME: ").append(match.getName()).append("\n");
        metadata.append("DESCRIPTION: ").append(match.getDescription() != null ? match.getDescription() : "Not provided").append("\n");
        metadata.append("CREATED BY: ").append(match.getCreator() != null ? match.getCreator() : "N/A").append("\n");
        metadata.append("CREATED ON: ").append(match.getCreatedTimestamp() != null ? match.getCreatedTimestamp().toString() : "N/A").append("\n\n");

        if (full != null && full.getAttributes() != null) {
            for (String key : new String[]{"xsd", "definition", "body", "content", "expression"}) {
                Object val = full.getAttributes().get(key);
                if (val != null && !val.toString().trim().isEmpty()) {
                    metadata.append("DEFINITION:\n").append(truncate(val.toString().trim(), 3000)).append("\n\n");
                    break;
                }
            }
        }

        String cdtHeader = "OBJECT TYPE: CDT (Custom Data Type)\n"
            + "OBJECT NAME: " + match.getName() + "\n"
            + "CREATED BY: " + (match.getCreator() != null ? match.getCreator() : "N/A") + "\n"
            + "CREATED ON: " + (match.getCreatedTimestamp() != null ? match.getCreatedTimestamp().toString() : "N/A") + "\n"
            + "---\n\n";
        String prompt = "You are a Business Analyst explaining an Appian CDT to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a structured explanation. No markdown symbols.\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Answer these sections exactly:\n\n"
            + "1. PURPOSE\n"
            + "What real-world entity does this CDT represent? (e.g., Employee, Leave Request, Order)\n\n"
            + "2. INPUTS\n"
            + "What fields does this CDT contain? List each field and what business data it stores.\n\n"
            + "3. OUTPUTS\n"
            + "What does this CDT produce or return when used in the application?\n\n"
            + "4. LOGIC / BEHAVIOR\n"
            + "How is this CDT used? (as process variable, rule input, data store entity, etc.)\n\n"
            + "5. DEPENDENCIES\n"
            + "What other CDTs, rules, or objects reference or use this CDT?\n\n"
            + "6. BUSINESS IMPACT\n"
            + "How does this CDT help the business? What would break if it did not exist?\n\n"
            + "7. SUMMARY\n"
            + "2-3 sentences. Example: Stores employee leave request data including dates, reason, and status.\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on WHY and WHAT\n"
            + "- Use exact field names from the data";

        return cdtHeader + callGroq(prompt);
    }

    private String explainRecordType(String name, String apiKey) throws Exception {
        ServiceContext sc = ServiceLocator.getAdministratorServiceContext();
        StringBuilder metadata = new StringBuilder();
        Object match = null;
        java.util.List<String> rtNames = new java.util.ArrayList<>();

        try {
            Object ts = ServiceLocator.class.getMethod("getTypeService", ServiceContext.class).invoke(null, sc);
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

        String rtHeader = "OBJECT TYPE: Record Type\n"
            + "OBJECT NAME: " + safeGet(match, "getLocalName", name) + "\n"
            + "CREATED BY: " + safeGet(match, "getCreator", "N/A") + "\n"
            + "CREATED ON: " + safeGet(match, "getCreationTime", "N/A") + "\n"
            + "---\n\n";
        String prompt = "You are a Business Analyst explaining an Appian Record Type to a non-technical stakeholder.\n"
            + "Based STRICTLY on the data below, generate a structured explanation. No markdown symbols.\n\n"
            + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
            + "Answer these sections exactly:\n\n"
            + "1. PURPOSE\n"
            + "What business object does this Record Type represent? (e.g., Leave Request, Employee, Order)\n\n"
            + "2. INPUTS\n"
            + "What fields are defined? List each field and what business data it stores.\n\n"
            + "3. OUTPUTS\n"
            + "What views, reports, or actions does this Record Type expose to users?\n\n"
            + "4. LOGIC / BEHAVIOR\n"
            + "What data source is it connected to? What relationships exist with other record types?\n\n"
            + "5. DEPENDENCIES\n"
            + "What actions, interfaces, rules, or other objects are linked to this Record Type?\n\n"
            + "6. BUSINESS IMPACT\n"
            + "How does this Record Type help the business? What would break if it did not exist?\n\n"
            + "7. SUMMARY\n"
            + "2-3 sentences. Example: Represents leave requests with list view, detail view, and approval actions.\n\n"
            + "RULES:\n"
            + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
            + "- Simple English, focus on WHY and WHAT\n"
            + "- Use exact field names from the data";

        return rtHeader + callGroq(prompt);
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


        String contentHeader = "OBJECT TYPE: " + displayType + "\n"
            + "OBJECT NAME: " + objName + "\n"
            + "CREATED BY: " + creator + "\n"
            + "CREATED ON: " + created + "\n"
            + "---\n\n";

        String prompt;
        if (displayType.equals("Expression Rule")) {
            prompt = "You are a Business Analyst explaining an Appian Expression Rule to a non-technical stakeholder.\n"
                + "Based STRICTLY on the data provided below, generate a structured explanation.\n"
                + "Do NOT add anything not present in the data. Do NOT use markdown symbols.\n\n"
                + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
                + "Generate output in EXACTLY this structure. Every section is mandatory.\n"
                + "If data is not available for a section, write: Not applicable\n\n"
                + "1. BUSINESS PURPOSE\n"
                + "Explain in 2-3 sentences WHY this rule exists and what business need it fulfills.\n\n"
                + "2. KEY FUNCTIONALITY\n"
                + "List the main things this rule does, step by step, in plain English.\n\n"
                + "3. INPUTS\n"
                + "List each rule input (ri!), its inferred data type, and what it represents in business terms.\n\n"
                + "4. OUTPUTS\n"
                + "What does this rule return or produce?\n\n"
                + "5. INTERNAL LOGIC\n"
                + "Explain the decisions, conditions, and flow in simple business terms.\n\n"
                + "6. DEPENDENCIES\n"
                + "List other rules, constants, or objects this rule uses.\n\n"
                + "7. USER INTERACTION\n"
                + "Describe any user interaction. If none, write: Not applicable\n\n"
                + "8. EXTERNAL SYSTEMS\n"
                + "List any external APIs or databases involved. If none, write: Not applicable\n\n"
                + "9. BUSINESS IMPACT\n"
                + "How does this rule help the business? What would break if it did not exist?\n\n"
                + "10. SUMMARY\n"
                + "Write 2-3 clear sentences summarizing what this rule is and why it matters.\n\n"
                + "FORMATTING RULES:\n"
                + "- Use PLAIN CAPITAL section headings exactly as shown above\n"
                + "- Use a dash (-) for bullet points\n"
                + "- No markdown, no bold, no code blocks\n"
                + "- Simple English only";
        } else {
            prompt = "You are a Business Analyst explaining an Appian " + displayType + " to a non-technical stakeholder.\n"
                + "Based STRICTLY on the data below, generate a structured explanation. No markdown symbols.\n\n"
                + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
                + "Answer these sections exactly:\n\n"
                + "1. PURPOSE\n"
                + "Why does this " + displayType + " exist? What user action or business need does it serve?\n\n"
                + "2. INPUTS\n"
                + "What data does the user enter or what parameters does this receive?\n\n"
                + "3. OUTPUTS\n"
                + "What happens after submission or execution? What does it produce or trigger?\n\n"
                + "4. LOGIC / BEHAVIOR\n"
                + "What happens inside? Describe validations, conditions, dynamic behavior, or API calls in plain English.\n\n"
                + "5. DEPENDENCIES\n"
                + "What other rules, CDTs, constants, integrations, or record types does this use?\n\n"
                + "6. BUSINESS IMPACT\n"
                + "How does this help the business or user? What would break if it did not exist?\n\n"
                + "7. SUMMARY\n"
                + "2-3 sentences describing what this " + displayType + " does in plain business terms.\n\n"
                + "RULES:\n"
                + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
                + "- Simple English, focus on WHY and WHAT\n"
                + "- Use exact names from the data";
        }

        return contentHeader + callGroq(prompt);
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
            case "9":  return "Folder";
            case "10": return "Community";
            case "11": return "User";
            case "12": return "Group";
            case "14": return "Process Model";
            case "18": return "Data Store Entity";
            case "22": return "Number (Decimal)";
            case "26": return "Boolean";
            default:   return typeId.isEmpty() ? "Unknown" : "Type(" + typeId + ")";
        }
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
