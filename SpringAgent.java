/*
 * SpringAgent - a free, local coding agent (Java + Ollama) that builds Spring Boot projects.
 *
 * Built-in abilities:
 *   - create_spring_boot_project : downloads a real project from start.spring.io (Maven, Java 17)
 *   - generate_crud              : writes Entity + Repository + Service + REST Controller from templates
 *   - list_files / read_file / write_file / edit_file
 *   - run_command                : build/test with Maven (asks YOUR approval first)
 *   - start_server / stop_server : runs the app in the background and reads its log
 *
 * Setup (Windows, cmd):
 *   1. Ollama running (check: ollama list). Internet is needed for start.spring.io + Maven downloads.
 *   2. gson-2.10.1.jar in the same folder as this file. JDK 17+.
 *   3. set OLLAMA_MODEL=qwen3:4b
 *      java -cp gson-2.10.1.jar SpringAgent.java
 *
 * Optional environment variables:
 *   OLLAMA_MODEL         model name (default: qwen3:4b if installed, else first chat model)
 *   OLLAMA_URL           default http://localhost:11434
 *   OLLAMA_CTX           context tokens, default 16384
 *   OLLAMA_THINK         true/false, default false (false = faster)
 *   AGENT_WORKSPACE      default "workspace"
 *   AGENT_CMD_TIMEOUT    seconds per command, default 600
 *   AGENT_JAVA_VERSION   Java version for new projects, default 17
 *   AGENT_AUTO_APPROVE   true = do not ask y/n before commands (default false)
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
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class SpringAgent {

    // ---------------------------------------------------------------- CONFIG
    static final String BASE_URL = envOr("OLLAMA_URL", "http://localhost:11434");
    static final int NUM_CTX = Integer.parseInt(envOr("OLLAMA_CTX", "16384"));
    static final boolean THINK = Boolean.parseBoolean(envOr("OLLAMA_THINK", "false"));
    static final int CMD_TIMEOUT = Integer.parseInt(envOr("AGENT_CMD_TIMEOUT", "600"));
    static final String JAVA_VERSION = envOr("AGENT_JAVA_VERSION", "17");
    static final boolean AUTO_APPROVE = Boolean.parseBoolean(envOr("AGENT_AUTO_APPROVE", "false"));
    static final Path WORKSPACE = Paths.get(envOr("AGENT_WORKSPACE", "workspace")).toAbsolutePath().normalize();
    static final int MAX_STEPS = 30;
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    static final String MVN = WINDOWS ? "mvnw.cmd" : "sh mvnw";
    static final Set<String> HIDDEN = Set.of("target", ".git", ".mvn", ".idea", "node_modules");

    static final Gson GSON = new Gson();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Scanner IN = new Scanner(System.in);
    static String model;
    static Process server;

    static final String SYSTEM_PROMPT =
        "You are an expert Spring Boot coding agent. Workspace folder: " + WORKSPACE + "\n"
        + "Operating system: " + (WINDOWS ? "Windows (cmd)" : "Linux/Mac (sh)") + ". Java " + JAVA_VERSION + ", Maven wrapper command: " + MVN + "\n\n"
        + "To build a Spring Boot project, follow this exact workflow:\n"
        + "1. Call create_spring_boot_project(name, dependencies). Default dependencies: web,data-jpa,h2.\n"
        + "2. For every entity the user wants, call generate_crud(project, entity, fields). Fields look like \"name:String,price:Double,active:Boolean\".\n"
        + "   It writes the Entity, Repository, Service and REST Controller for you. Do NOT rewrite those files by hand.\n"
        + "3. Build: run_command(\"cd PROJECT && " + MVN + " -q -DskipTests package\"). If it fails, read the error, fix the file with edit_file, build again.\n"
        + "4. Run: start_server(PROJECT). Then test with run_command(\"curl http://localhost:8080/api/<plural>\") and a POST. Then stop_server.\n"
        + "5. Finish with a short summary: project folder, endpoints, and how to run it (" + MVN + " spring-boot:run).\n\n"
        + "Rules:\n"
        + "- Paths are relative to the workspace. Never touch files outside it.\n"
        + "- Use edit_file for small changes to existing files; use write_file only for new files.\n"
        + "- Look at files with read_file before editing them. Never run destructive commands.\n"
        + "- Never run 'spring-boot:run' with run_command (it never ends). Use start_server.\n";

    static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ----------------------------------------------------------- BASIC TOOLS

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
                        .filter(p -> {
                            for (Path part : base.relativize(p)) if (HIDDEN.contains(part.toString())) return false;
                            return true;
                        })
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

    static String editFile(String path, String oldText, String newText) {
        try {
            Path full = safePath(path);
            String text = Files.readString(full, StandardCharsets.UTF_8);
            if (oldText.isEmpty()) return "ERROR: old_text is empty.";
            int first = text.indexOf(oldText);
            if (first < 0) return "ERROR: old_text not found in " + path + ". Use read_file and copy the exact text.";
            if (text.indexOf(oldText, first + 1) >= 0) return "ERROR: old_text appears more than once. Include more surrounding lines.";
            Files.writeString(full, text.substring(0, first) + newText + text.substring(first + oldText.length()), StandardCharsets.UTF_8);
            return "OK: edited " + path;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static boolean approve(String what) {
        if (AUTO_APPROVE) return true;
        System.out.println("\n[agent wants to] " + what);
        System.out.print("Allow? (y/n): ");
        return IN.nextLine().trim().equalsIgnoreCase("y");
    }

    static String tail(String s, int max) {
        return s.length() <= max ? s : "...[earlier output cut]\n" + s.substring(s.length() - max);
    }

    static String runCommand(String command) {
        if (!approve("run: " + command)) return "DENIED by user.";
        try {
            ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
            pb.directory(WORKSPACE.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getOutputStream().close();
            StringBuffer out = new StringBuffer();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append('\n');
                } catch (IOException ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
            boolean done = proc.waitFor(CMD_TIMEOUT, TimeUnit.SECONDS);
            if (!done) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
            }
            reader.join(3000);
            String text = tail(out.toString().trim(), 6000);
            if (text.isEmpty()) text = "(no output)";
            return done ? text + "\n[exit code " + proc.exitValue() + "]"
                        : text + "\n[ERROR: killed after " + CMD_TIMEOUT + " seconds]";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------ SPRING BOOT PROJECT TOOL

    static String createSpringBootProject(String nameArg, String depsArg) {
        try {
            String name = nameArg.toLowerCase().replaceAll("[^a-z0-9-]", "");
            if (name.isEmpty()) name = "demo";
            String pkgPart = name.replaceAll("[^a-z0-9]", "");
            if (pkgPart.isEmpty() || Character.isDigit(pkgPart.charAt(0))) pkgPart = "app" + pkgPart;
            String deps = (depsArg == null || depsArg.isBlank() ? "web,data-jpa,h2" : depsArg)
                    .toLowerCase().replaceAll("[^a-z0-9,-]", "");

            Path target = safePath(name);
            if (Files.exists(target) && Files.list(target).findAny().isPresent()) {
                return "ERROR: folder '" + name + "' already exists and is not empty. Pick another name or continue working in it.";
            }

            String url = "https://start.spring.io/starter.zip?type=maven-project&language=java&packaging=jar"
                    + "&groupId=com.example&artifactId=" + name + "&name=" + name
                    + "&packageName=com.example." + pkgPart + "&baseDir=" + name
                    + "&javaVersion=" + JAVA_VERSION + "&dependencies=" + deps;
            HttpResponse<byte[]> resp = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = resp.body();
            boolean isZip = data.length > 2 && data[0] == 'P' && data[1] == 'K';
            if (resp.statusCode() != 200 || !isZip) {
                return "ERROR: start.spring.io returned " + resp.statusCode() + ": "
                        + new String(data, StandardCharsets.UTF_8).substring(0, Math.min(400, data.length));
            }
            unzip(data, WORKSPACE);

            String props = "spring.application.name=" + name + "\nserver.port=8080\n";
            if (deps.contains("h2")) {
                props += "spring.datasource.url=jdbc:h2:mem:testdb\n"
                       + "spring.datasource.driverClassName=org.h2.Driver\n"
                       + "spring.datasource.username=sa\n"
                       + "spring.datasource.password=\n"
                       + "spring.jpa.hibernate.ddl-auto=update\n"
                       + "spring.jpa.show-sql=true\n"
                       + "spring.h2.console.enabled=true\n";
            }
            Files.writeString(target.resolve("src/main/resources/application.properties"), props);

            String[] base = findBasePackage(target);
            return "OK: created project '" + name + "' in workspace/" + name + " (dependencies: " + deps + ").\n"
                    + "Base package: " + (base == null ? "com.example." + pkgPart : base[0]) + "\n"
                    + "Next: call generate_crud(project=\"" + name + "\", entity=..., fields=...).";
        } catch (java.net.ConnectException e) {
            return "ERROR: cannot reach start.spring.io. Check the internet connection.";
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }

    static void unzip(byte[] data, Path root) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Returns {basePackage, directoryOfApplicationClass} or null. */
    static String[] findBasePackage(Path projectDir) throws IOException {
        Path src = projectDir.resolve("src/main/java");
        if (!Files.isDirectory(src)) return null;
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.contains("@SpringBootApplication")) {
                    Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
                    if (m.find()) return new String[]{m.group(1), p.getParent().toString()};
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------- CRUD GENERATOR

    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("string", "String"), Map.entry("integer", "Integer"), Map.entry("int", "Integer"),
            Map.entry("long", "Long"), Map.entry("double", "Double"), Map.entry("float", "Double"),
            Map.entry("boolean", "Boolean"), Map.entry("bool", "Boolean"),
            Map.entry("localdate", "LocalDate"), Map.entry("date", "LocalDate"));

    static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String uncap(String s) { return Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    static String plural(String word) {
        String w = word.toLowerCase();
        if (w.matches(".*[^aeiou]y")) return w.substring(0, w.length() - 1) + "ies";
        if (w.matches(".*(s|x|ch|sh)")) return w + "es";
        return w + "s";
    }

    static String generateCrud(String project, String entityArg, String fieldsArg) {
        try {
            Path proj = safePath(project);
            String[] base = findBasePackage(proj);
            if (base == null) return "ERROR: no Spring Boot project found in '" + project + "'. Call create_spring_boot_project first.";
            String pkg = base[0];
            Path srcDir = Paths.get(base[1]);

            String cleaned = entityArg == null ? "" : entityArg.replaceAll("[^A-Za-z0-9]", "");
            if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) return "ERROR: invalid entity name '" + entityArg + "'.";
            String entity = cap(cleaned);
            String plural = plural(entity);

            List<String[]> fields = new ArrayList<>();   // {type, name}
            String spec = (fieldsArg == null || fieldsArg.isBlank()) ? "name:String" : fieldsArg;
            for (String piece : spec.split(",")) {
                String[] kv = piece.trim().split(":");
                String fname = kv[0].trim().replaceAll("[^A-Za-z0-9]", "");
                if (fname.isEmpty() || !Character.isLetter(fname.charAt(0)) || fname.equalsIgnoreCase("id")) continue;
                String type = kv.length > 1 ? TYPES.get(kv[1].trim().toLowerCase()) : "String";
                if (type == null) return "ERROR: unsupported type '" + kv[1].trim() + "'. Use String, Integer, Long, Double, Boolean or LocalDate.";
                fields.add(new String[]{type, uncap(fname)});
            }
            if (fields.isEmpty()) return "ERROR: no valid fields given.";

            Path entityFile = srcDir.resolve("model/" + entity + ".java");
            if (Files.exists(entityFile)) return "ERROR: " + entity + " already exists. Use edit_file to change it.";

            StringBuilder decls = new StringBuilder(), accessors = new StringBuilder(), copies = new StringBuilder();
            boolean usesDate = false;
            for (String[] f : fields) {
                if (f[0].equals("LocalDate")) usesDate = true;
                decls.append("    private ").append(f[0]).append(' ').append(f[1]).append(";\n");
                accessors.append("\n    public ").append(f[0]).append(" get").append(cap(f[1])).append("() { return ").append(f[1]).append("; }\n")
                         .append("    public void set").append(cap(f[1])).append('(').append(f[0]).append(' ').append(f[1])
                         .append(") { this.").append(f[1]).append(" = ").append(f[1]).append("; }\n");
                copies.append("            existing.set").append(cap(f[1])).append("(updated.get").append(cap(f[1])).append("());\n");
            }

            String entityCode = """
                    package @@PKG@@.model;

                    import jakarta.persistence.*;
                    @@DATEIMPORT@@
                    @Entity
                    @Table(name = "@@PLURAL@@")
                    public class @@E@@ {

                        @Id
                        @GeneratedValue(strategy = GenerationType.IDENTITY)
                        private Long id;

                    @@DECLS@@
                        public @@E@@() { }

                        public Long getId() { return id; }
                        public void setId(Long id) { this.id = id; }
                    @@ACCESSORS@@}
                    """;
            String repoCode = """
                    package @@PKG@@.repository;

                    import @@PKG@@.model.@@E@@;
                    import org.springframework.data.jpa.repository.JpaRepository;

                    public interface @@E@@Repository extends JpaRepository<@@E@@, Long> { }
                    """;
            String serviceCode = """
                    package @@PKG@@.service;

                    import @@PKG@@.model.@@E@@;
                    import @@PKG@@.repository.@@E@@Repository;
                    import org.springframework.stereotype.Service;

                    import java.util.List;
                    import java.util.Optional;

                    @Service
                    public class @@E@@Service {

                        private final @@E@@Repository repository;

                        public @@E@@Service(@@E@@Repository repository) {
                            this.repository = repository;
                        }

                        public List<@@E@@> findAll() { return repository.findAll(); }

                        public Optional<@@E@@> findById(Long id) { return repository.findById(id); }

                        public @@E@@ create(@@E@@ item) {
                            item.setId(null);
                            return repository.save(item);
                        }

                        public Optional<@@E@@> update(Long id, @@E@@ updated) {
                            return repository.findById(id).map(existing -> {
                    @@COPIES@@            return repository.save(existing);
                            });
                        }

                        public boolean delete(Long id) {
                            if (!repository.existsById(id)) return false;
                            repository.deleteById(id);
                            return true;
                        }
                    }
                    """;
            String controllerCode = """
                    package @@PKG@@.controller;

                    import @@PKG@@.model.@@E@@;
                    import @@PKG@@.service.@@E@@Service;
                    import org.springframework.http.HttpStatus;
                    import org.springframework.http.ResponseEntity;
                    import org.springframework.web.bind.annotation.*;

                    import java.util.List;

                    @RestController
                    @RequestMapping("/api/@@PLURAL@@")
                    public class @@E@@Controller {

                        private final @@E@@Service service;

                        public @@E@@Controller(@@E@@Service service) {
                            this.service = service;
                        }

                        @GetMapping
                        public List<@@E@@> getAll() {
                            return service.findAll();
                        }

                        @GetMapping("/{id}")
                        public ResponseEntity<@@E@@> getById(@PathVariable("id") Long id) {
                            return service.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
                        }

                        @PostMapping
                        public ResponseEntity<@@E@@> create(@RequestBody @@E@@ item) {
                            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(item));
                        }

                        @PutMapping("/{id}")
                        public ResponseEntity<@@E@@> update(@PathVariable("id") Long id, @RequestBody @@E@@ item) {
                            return service.update(id, item).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
                        }

                        @DeleteMapping("/{id}")
                        public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
                            return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
                        }
                    }
                    """;

            String[][] outputs = {
                    {"model/" + entity + ".java", entityCode},
                    {"repository/" + entity + "Repository.java", repoCode},
                    {"service/" + entity + "Service.java", serviceCode},
                    {"controller/" + entity + "Controller.java", controllerCode}};
            List<String> written = new ArrayList<>();
            for (String[] o : outputs) {
                String code = o[1].replace("@@PKG@@", pkg).replace("@@E@@", entity).replace("@@PLURAL@@", plural)
                        .replace("@@DATEIMPORT@@", usesDate ? "import java.time.LocalDate;\n" : "")
                        .replace("@@DECLS@@", decls.toString()).replace("@@ACCESSORS@@", accessors.toString())
                        .replace("@@COPIES@@", copies.toString());
                Path out = srcDir.resolve(o[0]);
                Files.createDirectories(out.getParent());
                Files.writeString(out, code, StandardCharsets.UTF_8);
                written.add(WORKSPACE.relativize(out).toString().replace('\\', '/'));
            }
            return "OK: generated CRUD for " + entity + " (" + fields.size() + " fields). Files:\n  " + String.join("\n  ", written)
                    + "\nEndpoints: GET/POST /api/" + plural + ", GET/PUT/DELETE /api/" + plural + "/{id}";
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }

    // ---------------------------------------------------- BACKGROUND SERVER

    static String startServer(String project) {
        if (server != null && server.isAlive()) return "A server is already running. Call stop_server first.";
        if (!approve("start the Spring Boot server for '" + project + "' (" + MVN + " spring-boot:run)")) return "DENIED by user.";
        try {
            Path proj = safePath(project);
            Path log = proj.resolve("server.log");
            ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd", "/c", MVN + " spring-boot:run")
                                        : new ProcessBuilder("sh", "-c", MVN + " spring-boot:run");
            pb.directory(proj.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            server = pb.start();
            server.getOutputStream().close();

            long deadline = System.currentTimeMillis() + 180_000;
            String text = "";
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
                text = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
                if (text.contains("Started ") && text.contains(" seconds")) {
                    return "OK: server is running at http://localhost:8080 (H2 console: /h2-console)\n" + tail(text, 1500);
                }
                if (!server.isAlive() || text.contains("APPLICATION FAILED TO START")) {
                    return "ERROR: server failed to start. Log:\n" + tail(text, 3000);
                }
            }
            return "Server is still starting after 3 minutes. Log so far:\n" + tail(text, 2000);
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }

    static String stopServer() {
        if (server == null || !server.isAlive()) return "No server is running.";
        server.descendants().forEach(ProcessHandle::destroyForcibly);
        server.destroyForcibly();
        return "OK: server stopped.";
    }

    // ------------------------------------------------------------ DISPATCH

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    static String callTool(String name, JsonObject a) {
        switch (name) {
            case "list_files":                 return listFiles(str(a, "directory"));
            case "read_file":                  return readFile(str(a, "path"));
            case "write_file":                 return writeFile(str(a, "path"), str(a, "content"));
            case "edit_file":                  return editFile(str(a, "path"), str(a, "old_text"), str(a, "new_text"));
            case "run_command":                return runCommand(str(a, "command"));
            case "create_spring_boot_project": return createSpringBootProject(str(a, "name"), str(a, "dependencies"));
            case "generate_crud":              return generateCrud(str(a, "project"), str(a, "entity"), str(a, "fields"));
            case "start_server":               return startServer(str(a, "project"));
            case "stop_server":                return stopServer();
            default:                           return "ERROR: unknown tool " + name;
        }
    }

    // ------------------------------------------------- TOOL DECLARATIONS

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
        JsonArray t = new JsonArray();
        t.add(tool("create_spring_boot_project",
                "Create a new Spring Boot Maven project (downloaded from start.spring.io) inside the workspace.",
                params("name", "Project folder name, lowercase, e.g. student-api",
                       "dependencies", "Comma separated Spring Initializr ids. Default: web,data-jpa,h2. Others: validation, mysql, postgresql, lombok, security."),
                List.of("name")));
        t.add(tool("generate_crud",
                "Generate Entity, Repository, Service and REST Controller (full CRUD) for an entity in an existing Spring Boot project.",
                params("project", "Project folder name created earlier",
                       "entity", "Entity class name, singular, e.g. Student",
                       "fields", "Comma separated name:Type pairs, e.g. name:String,age:Integer,email:String. Types: String, Integer, Long, Double, Boolean, LocalDate. Do not include id."),
                List.of("project", "entity", "fields")));
        t.add(tool("list_files", "List files and folders in a workspace directory (hides target/.git).",
                params("directory", "Folder relative to the workspace, \".\" for the root"), List.of()));
        t.add(tool("read_file", "Read a text file from the workspace.",
                params("path", "File path relative to the workspace"), List.of("path")));
        t.add(tool("write_file", "Create a NEW file (or fully overwrite one) in the workspace.",
                params("path", "File path relative to the workspace", "content", "Full file content"),
                List.of("path", "content")));
        t.add(tool("edit_file", "Replace one exact piece of text in an existing file. old_text must match exactly once.",
                params("path", "File path relative to the workspace", "old_text", "Exact existing text to replace", "new_text", "Replacement text"),
                List.of("path", "old_text", "new_text")));
        t.add(tool("run_command", "Run a shell command in the workspace (builds, tests, curl). User approves each command. Never use for spring-boot:run.",
                params("command", "Command, e.g. cd student-api && " + MVN + " -q -DskipTests package"), List.of("command")));
        t.add(tool("start_server", "Start the Spring Boot app in the background and wait until it is running. Returns the startup log.",
                params("project", "Project folder name"), List.of("project")));
        t.add(tool("stop_server", "Stop the Spring Boot app started with start_server.", params(), List.of()));
        return t;
    }

    // ------------------------------------------------------------- OLLAMA

    static String pickModel() throws Exception {
        String wanted = System.getenv("OLLAMA_MODEL");
        if (wanted != null && !wanted.isBlank()) return wanted;
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/tags")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonArray models = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonArray("models");
        List<String> chat = new ArrayList<>();
        if (models != null) {
            for (JsonElement m : models) {
                String n = m.getAsJsonObject().get("name").getAsString();
                if (!n.contains("embed") && !n.contains("moondream") && !n.contains("llava")) chat.add(n);
            }
        }
        if (chat.isEmpty()) throw new IllegalStateException("No chat models installed. Run: ollama pull qwen3:4b");
        for (String n : chat) if (n.equals("qwen3:4b")) return n;
        return chat.get(0);
    }

    static JsonObject callOllama(JsonArray messages) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("tools", toolDeclarations());
        body.addProperty("stream", false);
        body.addProperty("think", THINK);
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", NUM_CTX);
        options.addProperty("temperature", 0.2);
        body.add("options", options);

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/chat")).timeout(Duration.ofMinutes(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Ollama error " + resp.statusCode() + ": " + resp.body()
                    + "\n(If it says the model does not support tools, use qwen3:4b or qwen2.5-coder.)");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    static String abbreviate(String s) {
        return s.length() > 140 ? s.substring(0, 140) + "..." : s;
    }

    static void runTask(JsonArray messages, String task) throws Exception {
        messages.add(message("user", task));
        for (int step = 0; step < MAX_STEPS; step++) {
            System.out.println("[thinking...]");
            JsonObject msg = callOllama(messages).getAsJsonObject("message");
            messages.add(msg);

            JsonArray calls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
                    ? msg.getAsJsonArray("tool_calls") : new JsonArray();
            if (calls.size() == 0) {
                String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString().trim() : "";
                System.out.println("\nAgent> " + text + "\n");
                return;
            }
            for (JsonElement el : calls) {
                JsonObject fn = el.getAsJsonObject().getAsJsonObject("function");
                String name = fn.get("name").getAsString();
                JsonObject args = new JsonObject();
                if (fn.has("arguments")) {
                    JsonElement a = fn.get("arguments");
                    args = a.isJsonObject() ? a.getAsJsonObject() : JsonParser.parseString(a.getAsString()).getAsJsonObject();
                }
                System.out.println("[tool] " + name + " " + abbreviate(args.toString()));
                String result = callTool(name, args);
                System.out.println("       -> " + abbreviate(result.replace('\n', ' ')));
                JsonObject toolMsg = message("tool", result);
                toolMsg.addProperty("tool_name", name);
                messages.add(toolMsg);
            }
        }
        System.out.println("\nAgent> Stopped after " + MAX_STEPS + " steps. Tell me to continue if needed.\n");
    }

    public static void main(String[] args) throws Exception {
        try {
            model = pickModel();
        } catch (java.net.ConnectException e) {
            System.out.println("Cannot reach Ollama at " + BASE_URL + ". Open the Ollama app and try again.");
            return;
        } catch (Exception e) {
            System.out.println("Problem talking to Ollama: " + e);
            return;
        }
        Files.createDirectories(WORKSPACE);
        Runtime.getRuntime().addShutdownHook(new Thread(SpringAgent::stopServer));
        System.out.println("Spring Boot agent ready. Model: " + model + " | Workspace: " + WORKSPACE);
        System.out.println("Example: Create a Spring Boot project called student-api with a Student entity (name, age, email), build it, run it and test it.");
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
