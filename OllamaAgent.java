/*
 * My Own Coding Agent in Java using OLLAMA (100% free, local, no API key)
 *
 * What it can do:
 *   - list files, read files, write/create files inside a "workspace" folder
 *   - run shell commands (asks YOUR approval first)
 *   - loops (plan -> act -> check result) until the task is done
 *
 * Setup (Windows):
 *   1. Ollama must be running (open the Ollama app, or run: ollama serve)
 *   2. Put gson-2.10.1.jar in the same folder as this file.
 *   3. Pick a model that supports tools (run "ollama list" to see yours), e.g.
 *        qwen2.5-coder, qwen3, llama3.1, llama3.2, mistral-nemo
 *      then run:
 *        set OLLAMA_MODEL=qwen2.5-coder:7b
 *        java -cp gson-2.10.1.jar OllamaAgent.java
 *
 * If OLLAMA_MODEL is not set, the first installed model is used.
 *
 * Optional environment variables:
 *   OLLAMA_URL       (default: http://localhost:11434)
 *   OLLAMA_CTX       (default: 8192, context size in tokens)
 *   AGENT_WORKSPACE  (default: workspace)
 */

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.*;

public class OllamaAgent {

    static final String BASE_URL = envOr("OLLAMA_URL", "http://localhost:11434");
    static final int NUM_CTX = Integer.parseInt(envOr("OLLAMA_CTX", "8192"));
    static final Path WORKSPACE = Paths.get(envOr("AGENT_WORKSPACE", "workspace")).toAbsolutePath().normalize();
    static final int MAX_STEPS = 25;

    static final Gson GSON = new Gson();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Scanner IN = new Scanner(System.in);
    static String model;

    static final String SYSTEM_PROMPT =
        "You are an expert coding agent working inside the folder: " + WORKSPACE + "\n"
        + "You can list, read and write files, and run shell commands using your tools.\n\n"
        + "Rules:\n"
        + "- Always look at existing files (list_files / read_file) before changing them.\n"
        + "- Write complete, working code. Use write_file to create or overwrite files.\n"
        + "- After writing code, compile/run it or its tests with run_command to verify it works,\n"
        + "  then fix any errors you see.\n"
        + "- File paths are relative to the workspace. Never touch files outside it.\n"
        + "- Never run destructive commands.\n"
        + "- When finished, give a short summary of what you did and which files changed.\n";

    static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ------------------------------------------------------------------ TOOLS

    static Path safePath(String path) {
        Path full = WORKSPACE.resolve(path).normalize();
        if (!full.startsWith(WORKSPACE)) throw new IllegalArgumentException("Path is outside the workspace.");
        return full;
    }

    static String listFiles(String directory) {
        try {
            Path base = safePath(directory == null || directory.isBlank() ? "." : directory);
            try (Stream<Path> s = Files.walk(base)) {
                String out = s.filter(p -> !p.equals(base))
                        .map(p -> WORKSPACE.relativize(p).toString().replace('\\', '/') + (Files.isDirectory(p) ? "/" : ""))
                        .sorted().limit(300).collect(Collectors.joining("\n"));
                return out.isEmpty() ? "(empty)" : out;
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String readFile(String path) {
        try {
            String text = Files.readString(safePath(path), StandardCharsets.UTF_8);
            return text.length() > 20000 ? text.substring(0, 20000) + "\n...[truncated]" : text;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String writeFile(String path, String content) {
        try {
            Path full = safePath(path);
            if (full.getParent() != null) Files.createDirectories(full.getParent());
            Files.writeString(full, content, StandardCharsets.UTF_8);
            return "OK: wrote " + content.length() + " characters to " + path;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String runCommand(String command) {
        System.out.println("\n[agent wants to run] " + command);
        System.out.print("Allow? (y/n): ");
        if (!IN.nextLine().trim().equalsIgnoreCase("y")) return "DENIED by user.";
        try {
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.directory(WORKSPACE.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null && sb.length() < 8000) sb.append(line).append('\n');
                out = sb.toString().trim();
            }
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "ERROR: command timed out after 120 seconds.";
            }
            return (out.isEmpty() ? "(no output)" : out) + "\n[exit code " + proc.exitValue() + "]";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String callTool(String name, JsonObject args) {
        switch (name) {
            case "list_files":  return listFiles(str(args, "directory"));
            case "read_file":   return readFile(str(args, "path"));
            case "write_file":  return writeFile(str(args, "path"), str(args, "content"));
            case "run_command": return runCommand(str(args, "command"));
            default:            return "ERROR: unknown tool " + name;
        }
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ------------------------------------------------- TOOL DECLARATIONS (JSON)

    static JsonObject tool(String name, String description, LinkedHashMap<String, String> params, List<String> required) {
        JsonObject props = new JsonObject();
        params.forEach((k, v) -> {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", v);
            props.add(k, p);
        });
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        required.forEach(req::add);
        schema.add("required", req);

        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        fn.add("parameters", schema);
        JsonObject t = new JsonObject();
        t.addProperty("type", "function");
        t.add("function", fn);
        return t;
    }

    static LinkedHashMap<String, String> params(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static JsonArray toolDeclarations() {
        JsonArray tools = new JsonArray();
        tools.add(tool("list_files", "List files and folders in a directory of the workspace.",
                params("directory", "Folder path relative to the workspace. Use \".\" for the root."), List.of()));
        tools.add(tool("read_file", "Read a text file from the workspace and return its contents.",
                params("path", "File path relative to the workspace."), List.of("path")));
        tools.add(tool("write_file", "Create or overwrite a file in the workspace with the given content.",
                params("path", "File path relative to the workspace (folders are created automatically).",
                       "content", "The full text to write into the file."),
                List.of("path", "content")));
        tools.add(tool("run_command", "Run a shell command inside the workspace and return its output. The user must approve every command.",
                params("command", "The shell command to run, e.g. \"javac Main.java && java Main\"."),
                List.of("command")));
        return tools;
    }

    // ----------------------------------------------------------- OLLAMA CALLS

    static String pickModel() throws Exception {
        String wanted = System.getenv("OLLAMA_MODEL");
        if (wanted != null && !wanted.isBlank()) return wanted;
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/tags")).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonArray models = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonArray("models");
        if (models == null || models.size() == 0) {
            throw new IllegalStateException("No models installed. Run: ollama pull qwen2.5-coder");
        }
        System.out.println("Installed models:");
        for (JsonElement m : models) System.out.println("  - " + m.getAsJsonObject().get("name").getAsString());
        return models.get(0).getAsJsonObject().get("name").getAsString();
    }

    static JsonObject callOllama(JsonArray messages) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("tools", toolDeclarations());
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", NUM_CTX);
        body.add("options", options);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/chat"))
                .timeout(Duration.ofMinutes(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Ollama error " + resp.statusCode() + ": " + resp.body()
                    + "\n(If it says the model does not support tools, pick another model, e.g. qwen2.5-coder or llama3.1)");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ------------------------------------------------------------ AGENT LOOP

    static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    static void runTask(JsonArray messages, String task) throws Exception {
        messages.add(message("user", task));

        for (int step = 0; step < MAX_STEPS; step++) {
            System.out.println("[thinking...]");
            JsonObject response = callOllama(messages);
            JsonObject msg = response.getAsJsonObject("message");
            messages.add(msg);   // keep the assistant turn exactly as returned

            JsonArray calls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
                    ? msg.getAsJsonArray("tool_calls") : new JsonArray();

            if (calls.size() == 0) {
                String text = msg.has("content") ? msg.get("content").getAsString().trim() : "";
                System.out.println("\nAgent> " + text + "\n");
                return;
            }

            for (JsonElement el : calls) {
                JsonObject fn = el.getAsJsonObject().getAsJsonObject("function");
                String name = fn.get("name").getAsString();
                JsonObject args = new JsonObject();
                if (fn.has("arguments")) {
                    JsonElement a = fn.get("arguments");
                    args = a.isJsonObject() ? a.getAsJsonObject()
                            : JsonParser.parseString(a.getAsString()).getAsJsonObject();
                }
                System.out.println("[tool] " + name + " " + abbreviate(args.toString()));
                String result = callTool(name, args);

                JsonObject toolMsg = message("tool", result);
                toolMsg.addProperty("tool_name", name);
                messages.add(toolMsg);
            }
        }
        System.out.println("\nAgent> Stopped after " + MAX_STEPS + " steps. Tell me to continue if needed.\n");
    }

    static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    public static void main(String[] args) throws Exception {
        try {
            model = pickModel();
        } catch (java.net.ConnectException e) {
            System.out.println("Cannot reach Ollama at " + BASE_URL + ". Open the Ollama app (or run: ollama serve) and try again.");
            return;
        } catch (Exception e) {
            System.out.println("Problem talking to Ollama: " + e);
            return;
        }
        Files.createDirectories(WORKSPACE);
        System.out.println("Coding agent ready. Model: " + model);
        System.out.println("Workspace: " + WORKSPACE);
        System.out.println("Type your task (or 'exit').\n");

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        while (true) {
            System.out.print("You> ");
            if (!IN.hasNextLine()) break;
            String task = IN.nextLine().trim();
            if (task.equalsIgnoreCase("exit") || task.equalsIgnoreCase("quit")) break;
            if (task.isEmpty()) continue;
            try {
                runTask(messages, task);
            } catch (Exception e) {
                System.out.println("\n[error] " + e.getMessage() + "\n");
            }
        }
    }
}
