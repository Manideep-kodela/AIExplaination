package com.appiancs.plugins.aiexplaination.function;

import com.appiancorp.suiteapi.applications.ApplicationService;
import com.appiancorp.suiteapi.applications.Application;
import com.appiancorp.type.AppianTypeLong;
import com.appiancorp.suiteapi.security.external.SecureCredentialsStore;
import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentFilter;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.expression.annotations.AppianScriptingFunctionsCategory;
import com.appiancorp.suiteapi.expression.annotations.Function;
import com.appiancorp.suiteapi.expression.annotations.Parameter;
import com.appiancorp.suiteapi.process.*;
import com.appiancorp.suiteapi.type.TypeService;
import com.appiancorp.suiteapi.type.Datatype;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.log4j.Logger;
import java.util.*;

@AppianScriptingFunctionsCategory
public class ApplicationExplainerFinal {

    private static final Logger LOG = Logger.getLogger(ApplicationExplainerFinal.class);

    private String derivePrefix(String name) {
        if (name == null || name.trim().isEmpty()) return "";
        StringBuilder prefix = new StringBuilder();
        for (String word : name.trim().split("\\s+"))
            if (!word.isEmpty()) prefix.append(Character.toUpperCase(word.charAt(0)));
        return prefix.toString();
    }

    @Function
    public String explainApplication(
            ApplicationService as,
            ProcessDesignService pds,
            ContentService cs,
            TypeService ts,
            SecureCredentialsStore scs,
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
            LOG.error("Failed to retrieve credentials for key: " + externalSystemKey, e);
            return "ERROR: Failed to retrieve credentials: " + e.getMessage();
        }

        String apiKey = credentials.get("apikey");
        String provider = credentials.get("provider");
        
        if (apiKey == null || apiKey.trim().isEmpty())
            return "ERROR: API Key not found in credentials.";
        if (provider == null || provider.trim().isEmpty())
            provider = "claude";

        try {
            Application app = null;
            String appUuid = null;
            boolean found = false;
            int startIndex = 0;
            int batchSize = 100;

            // 1. Try direct lookup by name
            try {
                app = as.getApplication(applicationName.trim());
                if (app != null) { appUuid = app.getUuid(); found = true; }
            } catch (Exception e) {
                LOG.debug("Direct lookup failed for app name: " + applicationName, e);
            }

            // 2. Try getApplicationByUuid in case it's a real UUID
            if (!found) {
                try {
                    app = as.getApplicationByUuid(applicationName.trim());
                    if (app != null) { appUuid = app.getUuid(); found = true; }
                } catch (Exception e) {
                    LOG.debug("UUID lookup failed for: " + applicationName, e);
                }
            }

            // 3. Fallback: page through ALL applications using availableItems count
            if (!found) {
                try {
                    // First call to get total count - use true to include all apps
                    com.appiancorp.suiteapi.common.ResultPage firstPage = as.getApplicationsPaging(0, batchSize, null, null, true);
                    long totalAvailable = firstPage != null ? firstPage.getAvailableItems() : 0;

                    // Process first page
                    if (firstPage != null) {
                        Object[] results = firstPage.getResults();
                        if (results != null) {
                            for (Object obj : results) {
                                if (!(obj instanceof Application)) continue;
                                Application tempApp = (Application) obj;
                                String tempName = tempApp.getName();
                                if (tempName != null && tempName.trim().equalsIgnoreCase(applicationName.trim())) {
                                    appUuid = tempApp.getUuid();
                                    try { app = as.getApplicationByUuid(appUuid); } catch (Exception e2) { app = tempApp; }
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }

                    // Page through remaining results
                    startIndex = batchSize;
                    while (!found && startIndex < totalAvailable) {
                        com.appiancorp.suiteapi.common.ResultPage page = as.getApplicationsPaging(startIndex, batchSize, null, null, true);
                        if (page == null) break;
                        Object[] results = page.getResults();
                        if (results == null || results.length == 0) break;
                        for (Object obj : results) {
                            if (!(obj instanceof Application)) continue;
                            Application tempApp = (Application) obj;
                            String tempName = tempApp.getName();
                            if (tempName != null && tempName.trim().equalsIgnoreCase(applicationName.trim())) {
                                appUuid = tempApp.getUuid();
                                try { app = as.getApplicationByUuid(appUuid); } catch (Exception e2) { app = tempApp; }
                                found = true;
                                break;
                            }
                        }
                        startIndex += batchSize;
                    }
                } catch (Exception e) {
                    LOG.warn("Error during application paging search for: " + applicationName, e);
                }
            }
            
            if (app == null) {
                StringBuilder availableApps = new StringBuilder("ERROR: Application not found with name '");
                availableApps.append(applicationName).append("'\n\n");
                availableApps.append("Available applications:\n");
                try {
                    com.appiancorp.suiteapi.common.ResultPage fp = as.getApplicationsPaging(0, batchSize, null, null, true);
                    long total = fp != null ? fp.getAvailableItems() : 0;
                    availableApps.append("(Total in system: ").append(total).append(")\n");
                    int si = 0;
                    while (true) {
                        com.appiancorp.suiteapi.common.ResultPage page = (si == 0) ? fp : as.getApplicationsPaging(si, batchSize, null, null, true);
                        if (page == null) break;
                        Object[] results = page.getResults();
                        if (results == null || results.length == 0) break;
                        for (Object obj : results) {
                            if (obj instanceof Application) {
                                String n = ((Application) obj).getName();
                                if (n != null) availableApps.append("  - ").append(n).append("\n");
                            }
                        }
                        si += batchSize;
                        if (si >= total) break;
                    }
                } catch (Exception e) {
                    LOG.warn("Error listing available applications", e);
                }
                return availableApps.toString();
            }

            String appName = app.getName() != null ? app.getName() : "Unknown Application";
            String appDesc = app.getDescription() != null ? app.getDescription() : "Not provided";
            // Use app's actual prefix if set, otherwise derive from name
            String appPrefix = (app.getPrefix() != null && !app.getPrefix().trim().isEmpty())
                ? app.getPrefix().trim()
                : derivePrefix(appName);

            StringBuilder metadata = new StringBuilder();
            metadata.append("APPLICATION NAME: ").append(appName).append("\n");
            metadata.append("DESCRIPTION: ").append(appDesc).append("\n");
            metadata.append("PREFIX: ").append(appPrefix.isEmpty() ? "N/A" : appPrefix).append("\n");
            metadata.append("UUID: ").append(appUuid).append("\n\n");

            // Get process model UUIDs from application (these match pm.getUuid())
            Set<String> pmUuids = new HashSet<>();
            try {
                java.lang.reflect.Method method = app.getClass().getMethod("getObjectsByType", Long.class);
                Object result = method.invoke(app, AppianTypeLong.PROCESS_MODEL);
                if (result instanceof Set)
                    for (Object item : (Set<?>) result)
                        if (item != null) pmUuids.add(item.toString());
            } catch (Exception e) {
                LOG.warn("Could not retrieve process model UUIDs for app: " + appName, e);
                metadata.append("Note: Could not retrieve process model UUIDs: ").append(e.getMessage()).append("\n");
            }

            metadata.append("Total process models in application: ").append(pmUuids.size()).append("\n");

            // Get content item UUIDs and resolve to Content objects
            Set<Content> appContentObjects = new HashSet<>();
            try {
                java.lang.reflect.Method method = app.getClass().getMethod("getObjectsByType", Long.class);
                Object result = method.invoke(app, AppianTypeLong.CONTENT_ITEM);
                if (result instanceof Set) {
                    Set<?> uuidSet = (Set<?>) result;
                    if (!uuidSet.isEmpty()) {
                        String[] uuids = new String[uuidSet.size()];
                        int i = 0;
                        for (Object item : uuidSet)
                            if (item != null) uuids[i++] = item.toString();
                        Long[] ids = cs.getIdsByUuid(uuids);
                        if (ids != null && ids.length > 0) {
                            java.util.List<Long> validIds = new java.util.ArrayList<>();
                            for (Long id : ids) if (id != null) validIds.add(id);
                            if (!validIds.isEmpty()) {
                                Content[] contents = cs.getContent(validIds.toArray(new Long[0]));
                                if (contents != null)
                                    for (Content c : contents)
                                        if (c != null) appContentObjects.add(c);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Could not resolve content items for app: " + appName, e);
                metadata.append("Note: Could not resolve content items: ").append(e.getMessage()).append("\n");
            }

            metadata.append("Total content objects resolved: ").append(appContentObjects.size()).append("\n\n");

            // Use prefix-based filtering (most reliable with current SDK)
            int pmCount = 0, interfaceCount = 0, ruleCount = 0, integrationCount = 0, cdtCount = 0, recordCount = 0;
            
            Map<String, String> pmDetails = new LinkedHashMap<>();
            Map<String, String> interfaceDetails = new LinkedHashMap<>();
            Map<String, String> ruleDetails = new LinkedHashMap<>();
            Map<String, String> integrationDetails = new LinkedHashMap<>();
            Map<String, String> cdtDetails = new LinkedHashMap<>();
            Map<String, String> recordDetails = new LinkedHashMap<>();

            // Process Models
            try {
                ProcessModel.Descriptor[] allPMs = pds.listProcessModels();
                
                if (allPMs != null) {
                    for (ProcessModel.Descriptor pm : allPMs) {
                        String pmName = pm.toString();
                        String pmUuid = pm.getUuid();
                        
                        if (pmName != null && !pmName.isEmpty() && pmUuid != null) {
                            boolean matches = pmUuids.contains(pmUuid);
                            if (!matches && pmUuids.isEmpty() && !appPrefix.isEmpty())
                                matches = pmName.startsWith(appPrefix + " ") || pmName.startsWith(appPrefix + "_");
                            if (matches) {
                                pmCount++;
                                String creator = pm.getCreatorUsername() != null ? pm.getCreatorUsername() : "N/A";
                                String updated = pm.getTimeStampUpdated() != null ? pm.getTimeStampUpdated().toString() : "N/A";
                                pmDetails.put(pmName, "Created by: " + creator + ", Last updated: " + updated);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error reading Process Models for app: " + appName, e);
                metadata.append("ERROR reading Process Models: ").append(e.getMessage()).append("\n");
            }

            // Fetch all rules content once (recursive via searchByRoot)
            Content[] allRulesContent = null;
            try {
                Long rulesRoot = cs.getSystemId(ContentConstants.RULES_ROOT_SYSTEM_ID);
                allRulesContent = cs.searchByRoot(rulesRoot, "%", new ContentFilter(ContentConstants.TYPE_RULE));
            } catch (Exception e) {
                LOG.error("Error reading rules content", e);
                metadata.append("ERROR reading rules content: ").append(e.getMessage()).append("\n");
            }

            // Interfaces
            if (allRulesContent != null) {
                try {
                    for (Content c : allRulesContent) {
                        if (c.getSubtype() != null && c.getSubtype() == ContentConstants.SUBTYPE_RULE_INTERFACE) {
                            String ifName = c.getName();
                            if (ifName == null) continue;
                            boolean matches = false;
                            // Method 1: Match by resolved content objects from application
                            for (Content ac : appContentObjects) {
                                if (ac.getId() != null && ac.getId().equals(c.getId())) { matches = true; break; }
                            }
                            // Method 2: Also check prefix if not matched by UUID
                            if (!matches && !appPrefix.isEmpty())
                                matches = ifName.startsWith(appPrefix + "_") || ifName.startsWith(appPrefix + " ");
                            if (matches) {
                                interfaceCount++;
                                interfaceDetails.put(ifName, "Created by: " + (c.getCreator() != null ? c.getCreator() : "N/A"));
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error reading Interfaces", e);
                    metadata.append("ERROR reading Interfaces: ").append(e.getMessage()).append("\n");
                }
            }

            // Expression Rules
            if (allRulesContent != null) {
                try {
                    for (Content c : allRulesContent) {
                        if (c.getSubtype() != null && c.getSubtype() == ContentConstants.SUBTYPE_RULE_FREEFORM) {
                            String ruleName = c.getName();
                            if (ruleName == null) continue;
                            boolean matches = false;
                            // Method 1: Match by UUID
                            for (Content ac : appContentObjects) {
                                if (ac.getId() != null && ac.getId().equals(c.getId())) { matches = true; break; }
                            }
                            // Method 2: Also check prefix if not matched by UUID
                            if (!matches && !appPrefix.isEmpty())
                                matches = ruleName.startsWith(appPrefix + "_") || ruleName.startsWith(appPrefix + " ");
                            if (matches) {
                                ruleCount++;
                                ruleDetails.put(ruleName, "Created by: " + (c.getCreator() != null ? c.getCreator() : "N/A"));
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error reading Expression Rules", e);
                    metadata.append("ERROR reading Expression Rules: ").append(e.getMessage()).append("\n");
                }
            }

            // Integrations
            if (allRulesContent != null) {
                try {
                    for (Content c : allRulesContent) {
                        if (c.getSubtype() != null && c.getSubtype() == ContentConstants.SUBTYPE_RULE_OUTBOUND_INTEGRATION) {
                            String intName = c.getName();
                            if (intName == null) continue;
                            boolean matches = false;
                            // Method 1: Match by UUID
                            for (Content ac : appContentObjects) {
                                if (ac.getId() != null && ac.getId().equals(c.getId())) { matches = true; break; }
                            }
                            // Method 2: Also check prefix if not matched by UUID
                            if (!matches && !appPrefix.isEmpty())
                                matches = intName.startsWith(appPrefix + "_") || intName.startsWith(appPrefix + " ");
                            if (matches) {
                                integrationCount++;
                                integrationDetails.put(intName, "Created by: " + (c.getCreator() != null ? c.getCreator() : "N/A"));
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error reading Integrations", e);
                    metadata.append("ERROR reading Integrations: ").append(e.getMessage()).append("\n");
                }
            }

            // CDTs - skipped due to SDK limitations
            // try {
            //     if (!appPrefix.isEmpty()) {
            //         String namespace = "urn:com:appian:types:" + appPrefix;
            //         Datatype[] cdts = ts.getTypesByNamespace(namespace);
            //         if (cdts != null) {
            //             for (Datatype dt : cdts) {
            //                 try {
            //                     if (!dt.isListType() && !dt.isRecordType()) {
            //                         String cdtName = dt.getName();
            //                         if (cdtName != null) {
            //                             cdtCount++;
            //                             String desc = dt.getDescription() != null ? dt.getDescription() : "No description";
            //                             cdtDetails.put(cdtName, desc);
            //                         }
            //                     }
            //                 } catch (Exception ignored) {}
            //             }
            //         }
            //     }
            // } catch (Exception e) {
            //     metadata.append("ERROR reading CDTs: ").append(e.getMessage()).append("\n");
            // }

            // Record Types - skipped due to SDK limitations
            // try {
            //     if (!appPrefix.isEmpty()) {
            //         String namespace = "urn:com:appian:types:" + appPrefix;
            //         Datatype[] records = ts.getTypesByNamespace(namespace);
            //         if (records != null) {
            //             for (Datatype dt : records) {
            //                 try {
            //                     if (dt.isRecordType()) {
            //                         String recName = dt.getName();
            //                         if (recName != null) {
            //                             recordCount++;
            //                             String desc = dt.getDescription() != null ? dt.getDescription() : "No description";
            //                             recordDetails.put(recName, desc);
            //                         }
            //                     }
            //                 } catch (Exception ignored) {}
            //             }
            //         }
            //     }
            // } catch (Exception e) {
            //     metadata.append("ERROR reading Record Types: ").append(e.getMessage()).append("\n");
            // }

            // Build detailed metadata
            metadata.append("=== PROCESS MODELS (").append(pmCount).append(") ===\n");
            if (pmCount > 0) {
                for (Map.Entry<String, String> entry : pmDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No Process Models found\n");
            }
            metadata.append("\n");

            metadata.append("=== INTERFACES (").append(interfaceCount).append(") ===\n");
            if (interfaceCount > 0) {
                for (Map.Entry<String, String> entry : interfaceDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No Interfaces found\n");
            }
            metadata.append("\n");

            metadata.append("=== EXPRESSION RULES (").append(ruleCount).append(") ===\n");
            if (ruleCount > 0) {
                for (Map.Entry<String, String> entry : ruleDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No Expression Rules found\n");
            }
            metadata.append("\n");

            metadata.append("=== INTEGRATIONS (").append(integrationCount).append(") ===\n");
            if (integrationCount > 0) {
                for (Map.Entry<String, String> entry : integrationDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No Integrations found\n");
            }
            metadata.append("\n");

            metadata.append("=== CUSTOM DATA TYPES (").append(cdtCount).append(") ===\n");
            if (cdtCount > 0) {
                for (Map.Entry<String, String> entry : cdtDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No CDTs found\n");
            }
            metadata.append("\n");

            metadata.append("=== RECORD TYPES (").append(recordCount).append(") ===\n");
            if (recordCount > 0) {
                for (Map.Entry<String, String> entry : recordDetails.entrySet()) {
                    metadata.append("  - ").append(entry.getKey()).append("\n");
                    metadata.append("    ").append(entry.getValue()).append("\n");
                }
            } else {
                metadata.append("  No Record Types found\n");
            }
            metadata.append("\n");

            metadata.append("=== SUMMARY ===\n");
            metadata.append("Total Objects: ").append(pmCount + interfaceCount + ruleCount + integrationCount + cdtCount + recordCount).append("\n");
            metadata.append("  - Process Models: ").append(pmCount).append("\n");
            metadata.append("  - Interfaces: ").append(interfaceCount).append("\n");
            metadata.append("  - Expression Rules: ").append(ruleCount).append("\n");
            metadata.append("  - Integrations: ").append(integrationCount).append("\n");
            metadata.append("  - CDTs: ").append(cdtCount).append("\n");
            metadata.append("  - Record Types: ").append(recordCount).append("\n");

            String header = "APPLICATION EXPLANATION\n"
                + "APPLICATION: " + appName + "\n"
                + "UUID: " + appUuid + "\n"
                + "PREFIX: " + (appPrefix.isEmpty() ? "N/A" : appPrefix) + "\n"
                + "---\n\n";

            String prompt = "You are a Business Analyst explaining an Appian Application to a non-technical stakeholder.\n"
                + "Based STRICTLY on the data below, generate a comprehensive application-level explanation.\n"
                + "Do NOT use markdown symbols (##, **, *, --)\n\n"
                + "TECHNICAL DATA:\n" + metadata.toString() + "\n\n"
                + "Generate a detailed explanation with these sections:\n\n"
                + "1. APPLICATION PURPOSE\n"
                + "What is the overall business purpose of this application? What problem does it solve?\n\n"
                + "2. APPLICATION SCOPE\n"
                + "What are the key functionalities and features provided by this application?\n\n"
                + "3. ARCHITECTURE OVERVIEW\n"
                + "Describe the application architecture based on the object counts and types.\n"
                + "Explain how Process Models, Interfaces, Rules, Integrations, CDTs, and Record Types work together.\n\n"
                + "4. KEY WORKFLOWS\n"
                + "Based on the Process Models listed, describe the main business workflows.\n\n"
                + "5. USER INTERFACES\n"
                + "Based on the Interfaces listed, what user experiences are provided?\n\n"
                + "6. DATA MODEL\n"
                + "Based on CDTs and Record Types, what data structures exist and what do they represent?\n\n"
                + "7. INTEGRATIONS\n"
                + "Based on the Integrations listed, what external systems are connected?\n\n"
                + "8. BUSINESS RULES\n"
                + "Based on Expression Rules, what business logic and calculations are implemented?\n\n"
                + "9. BUSINESS VALUE\n"
                + "What business value does this application deliver? What would be the impact if it didn't exist?\n\n"
                + "10. COMPREHENSIVE SUMMARY\n"
                + "Write a detailed 7-10 sentence summary covering:\n"
                + "- What the application does and why it exists\n"
                + "- Who uses it and how\n"
                + "- Key workflows from start to finish\n"
                + "- How all components work together\n"
                + "- Business impact and value delivered\n\n"
                + "RULES:\n"
                + "- Plain CAPITAL headings, dash (-) for bullets, no markdown\n"
                + "- Simple English, focus on business value not technical details\n"
                + "- Be thorough and detailed\n"
                + "- Use exact object names from the data\n"
                + "- Explain how objects are linked and work together";

            return header + callAI(prompt, apiKey, provider);

        } catch (Exception e) {
            LOG.error("Unexpected error in explainApplication for: " + applicationName, e);
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private String callAI(String prompt, String apiKey, String provider) throws Exception {
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
        if (isClaude)      marker = "\"text\":\"";
        else if (isGemini) marker = "\"text\": \"";
        else               marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return "Could not parse API response: " + json.substring(0, Math.min(200, json.length()));
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
}
