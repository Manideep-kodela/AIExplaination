package com.appiancs.plugins.aiexplaination.function;

import com.appiancorp.suiteapi.security.external.SecureCredentialsStore;
import com.appiancorp.suiteapi.content.*;
import com.appiancorp.suiteapi.expression.annotations.AppianScriptingFunctionsCategory;
import com.appiancorp.suiteapi.expression.annotations.Function;
import com.appiancorp.suiteapi.expression.annotations.Parameter;
import com.appiancorp.suiteapi.process.*;
import com.appiancorp.suiteapi.type.TypeService;
import com.appiancorp.suiteapi.type.Datatype;
import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.util.*;

@AppianScriptingFunctionsCategory
public class ApplicationKTDocumentGenerator {

    @Function
    public String generateApplicationKT(
            ProcessDesignService pds,
            ContentService cs,
            SecureCredentialsStore scs,
            TypeService ts,
            @Parameter String applicationName,
            @Parameter String externalSystemKey) {

        if (applicationName == null || applicationName.trim().isEmpty())
            return "ERROR: applicationName is required.";
        if (externalSystemKey == null || externalSystemKey.trim().isEmpty())
            return "ERROR: externalSystemKey is required.";

        Map<String, String> credentials;
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
            return "ERROR: API Key not found in credentials.";
        if (provider == null || provider.trim().isEmpty())
            provider = "claude";

        try {
            // Collect all application objects
            ApplicationMetadata appMetadata = collectApplicationMetadata(pds, cs, ts, applicationName);
            
            // Generate KT document content using AI - return as formatted text
            return generateKTContent(appMetadata, apiKey, provider);
            
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private ApplicationMetadata collectApplicationMetadata(
            ProcessDesignService pds, 
            ContentService cs, 
            TypeService ts,
            String appName) throws Exception {
        
        ApplicationMetadata metadata = new ApplicationMetadata();
        metadata.applicationName = appName;
        
        // Collect Process Models
        ProcessModel.Descriptor[] allPMs = pds.listProcessModels();
        if (allPMs != null) {
            for (ProcessModel.Descriptor pm : allPMs) {
                String pmName = pm.toString();
                if (pmName != null && pmName.toLowerCase().contains(appName.toLowerCase())) {
                    ObjectDetail detail = new ObjectDetail();
                    detail.name = pmName;
                    detail.type = "Process Model";
                    detail.creator = pm.getCreatorUsername();
                    detail.lastModified = pm.getTimeStampUpdated() != null ? pm.getTimeStampUpdated().toString() : "N/A";
                    
                    // Get process details
                    try {
                        ProcessModel fullPM = pds.exportProcessModel(pm.getId());
                        detail.nodeCount = fullPM.getProcessNodes() != null ? fullPM.getProcessNodes().length : 0;
                    } catch (Exception ignored) {}
                    
                    metadata.processModels.add(detail);
                }
            }
        }
        
        // Collect Interfaces
        collectContentObjects(cs, appName, ContentConstants.TYPE_RULE, 
                ContentConstants.SUBTYPE_RULE_INTERFACE, "Interface", metadata.interfaces);
        
        // Collect Expression Rules
        collectContentObjects(cs, appName, ContentConstants.TYPE_RULE, 
                ContentConstants.SUBTYPE_RULE_FREEFORM, "Expression Rule", metadata.expressionRules);
        
        // Collect Integrations
        collectContentObjects(cs, appName, ContentConstants.TYPE_RULE, 
                ContentConstants.SUBTYPE_RULE_OUTBOUND_INTEGRATION, "Integration", metadata.integrations);
        
        // Collect CDTs
        collectCDTs(ts, appName, metadata.cdts);
        
        // Collect Record Types
        collectRecordTypes(ts, appName, metadata.recordTypes);
        
        return metadata;
    }

    private void collectContentObjects(ContentService cs, String appName, int type, int subtype, 
                                      String typeName, List<ObjectDetail> list) {
        try {
            Long rulesRoot = cs.getSystemId(ContentConstants.RULES_ROOT_SYSTEM_ID);
            Content[] results = cs.searchByRoot(rulesRoot, "", new ContentFilter(type));
            
            if (results != null) {
                for (Content c : results) {
                    if (c.getSubtype() != null && c.getSubtype() == subtype) {
                        String name = c.getName();
                        if (name != null && name.toLowerCase().contains(appName.toLowerCase())) {
                            ObjectDetail detail = new ObjectDetail();
                            detail.name = name;
                            detail.type = typeName;
                            detail.description = c.getDescription();
                            detail.creator = c.getCreator();
                            detail.lastModified = c.getUpdatedTimestamp() != null ? c.getUpdatedTimestamp().toString() : "N/A";
                            list.add(detail);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void collectCDTs(TypeService ts, String appName, List<ObjectDetail> list) {
        // CDT collection disabled due to SDK limitations
    }

    private void collectRecordTypes(TypeService ts, String appName, List<ObjectDetail> list) {
        // Record type collection disabled due to SDK limitations
    }

    private String generateKTContent(ApplicationMetadata metadata, String apiKey, String provider) throws Exception {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are a Business Analyst creating a comprehensive Knowledge Transfer (KT) document for an Appian application.\n\n");
        prompt.append("APPLICATION NAME: ").append(metadata.applicationName).append("\n\n");
        
        prompt.append("DISCOVERED OBJECTS:\n\n");
        
        prompt.append("Process Models (").append(metadata.processModels.size()).append("):\n");
        for (ObjectDetail pm : metadata.processModels) {
            prompt.append("  - ").append(pm.name).append(" (").append(pm.nodeCount).append(" nodes)\n");
        }
        prompt.append("\n");
        
        prompt.append("Interfaces (").append(metadata.interfaces.size()).append("):\n");
        for (ObjectDetail obj : metadata.interfaces) {
            prompt.append("  - ").append(obj.name).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("Expression Rules (").append(metadata.expressionRules.size()).append("):\n");
        for (ObjectDetail obj : metadata.expressionRules) {
            prompt.append("  - ").append(obj.name).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("Integrations (").append(metadata.integrations.size()).append("):\n");
        for (ObjectDetail obj : metadata.integrations) {
            prompt.append("  - ").append(obj.name).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("CDTs (").append(metadata.cdts.size()).append("):\n");
        for (ObjectDetail obj : metadata.cdts) {
            prompt.append("  - ").append(obj.name).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("Record Types (").append(metadata.recordTypes.size()).append("):\n");
        for (ObjectDetail obj : metadata.recordTypes) {
            prompt.append("  - ").append(obj.name).append("\n");
        }
        prompt.append("\n\n");
        
        prompt.append("Generate a comprehensive Business Requirements Document (BRD) / Knowledge Transfer document with these sections:\n\n");
        prompt.append("1. BUSINESS OBJECTIVES\n");
        prompt.append("   - What business problems does this application solve?\n");
        prompt.append("   - What are the key goals and objectives?\n\n");
        
        prompt.append("2. FUNCTIONAL REQUIREMENTS\n");
        prompt.append("   - Personas (who uses this application)\n");
        prompt.append("   - User Management & Authentication\n");
        prompt.append("   - Core Features and Capabilities\n");
        prompt.append("   - Business Workflows\n\n");
        
        prompt.append("3. PROCESS MODELS\n");
        prompt.append("   For each process model, describe:\n");
        prompt.append("   - Purpose and business value\n");
        prompt.append("   - High-level workflow\n");
        prompt.append("   - Key actors and roles\n");
        prompt.append("   - Integration points\n\n");
        
        prompt.append("4. USER INTERFACES\n");
        prompt.append("   - Key screens and their purpose\n");
        prompt.append("   - User journeys\n");
        prompt.append("   - Data entry and validation\n\n");
        
        prompt.append("5. DATA MODEL\n");
        prompt.append("   - Key CDTs and what they represent\n");
        prompt.append("   - Record Types and their purpose\n");
        prompt.append("   - Data relationships\n\n");
        
        prompt.append("6. INTEGRATIONS\n");
        prompt.append("   - External systems connected\n");
        prompt.append("   - Data flow and synchronization\n");
        prompt.append("   - Integration patterns\n\n");
        
        prompt.append("7. BUSINESS RULES\n");
        prompt.append("   - Key expression rules and their logic\n");
        prompt.append("   - Validation rules\n");
        prompt.append("   - Business calculations\n\n");
        
        prompt.append("8. NOTIFICATIONS & ALERTS\n");
        prompt.append("   - What notifications are sent\n");
        prompt.append("   - When and to whom\n\n");
        
        prompt.append("9. REPORTING & ANALYTICS\n");
        prompt.append("   - Available reports\n");
        prompt.append("   - Key metrics tracked\n\n");
        
        prompt.append("10. SECURITY REQUIREMENTS\n");
        prompt.append("    - Authentication mechanisms\n");
        prompt.append("    - Authorization and access control\n");
        prompt.append("    - Data protection\n\n");
        
        prompt.append("11. PERFORMANCE REQUIREMENTS\n");
        prompt.append("    - Expected load and scalability\n");
        prompt.append("    - Response time requirements\n\n");
        
        prompt.append("FORMATTING RULES:\n");
        prompt.append("- Use CAPITAL LETTERS for main section headings\n");
        prompt.append("- Use numbered lists (1., 2., 3.) for main sections\n");
        prompt.append("- Use bullet points (-) for sub-items\n");
        prompt.append("- Write in clear, business-friendly language\n");
        prompt.append("- Focus on WHAT and WHY, not HOW\n");
        prompt.append("- Be comprehensive and detailed\n");
        prompt.append("- Do NOT use markdown symbols (##, **, **)");
        
        return callAI(prompt.toString(), apiKey, provider);
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

        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
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
        conn.setReadTimeout(120000); // 2 minutes for large documents

        String escapedPrompt = prompt
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");

        String body;
        if (isClaude) {
            body = "{\"model\":\"" + model + "\",\"max_tokens\":8192,\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}]}";
        } else if (isGemini) {
            body = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapedPrompt + "\"}]}]}";
        } else {
            body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],\"max_tokens\":8192}";
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
        if (start == -1) return "Could not parse API response";
        start += marker.length();
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == '"' && (end == 0 || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        String content = json.substring(start, Math.min(end, json.length()));
        content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        return content;
    }

    // Inner classes for metadata
    private static class ApplicationMetadata {
        String applicationName;
        List<ObjectDetail> processModels = new ArrayList<>();
        List<ObjectDetail> interfaces = new ArrayList<>();
        List<ObjectDetail> expressionRules = new ArrayList<>();
        List<ObjectDetail> integrations = new ArrayList<>();
        List<ObjectDetail> cdts = new ArrayList<>();
        List<ObjectDetail> recordTypes = new ArrayList<>();
    }

    private static class ObjectDetail {
        String name;
        String type;
        String description;
        String creator;
        String lastModified;
        int nodeCount;
    }
}
