# SpringBoot Agent

A free, local AI coding agent that builds Spring Boot projects for you, with a React web interface.

Built by **Vetrivel Balakrishnan**.

Describe the API you want in plain English. The agent creates the project, writes the code, builds it with Maven, runs it and tests it. It runs on your own computer with [Ollama](https://ollama.com), so there is no API key and nothing is sent to the cloud.

## What it can do

- Create a real Spring Boot project from [start.spring.io](https://start.spring.io) (Maven, Java 17)
- Generate the Entity, Repository, Service and REST Controller for any entity (full CRUD)
- List, read, write and edit files inside its workspace folder
- Run shell commands such as Maven builds and curl tests, **after you approve each one**
- Start and stop the generated Spring Boot app in the background
- Show every step live in the browser, with a file explorer and a file viewer

## Requirements

- JDK 17 or newer (`java -version`)
- [Ollama](https://ollama.com) running locally, with a model that supports tools, for example:
  ```
  ollama pull qwen3:1.7b
  ```
- Internet access (for start.spring.io and Maven downloads)
- [Gson 2.10.1](https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar) in the project folder (included if you downloaded the zip)

## Project layout

```
SpringBootAgent.java   the agent and its local web server
web/index.html         the React interface
gson-2.10.1.jar        JSON library
run.bat                Windows launcher
workspace/             projects created by the agent (created on first run)
```

## Run it

Windows PowerShell:
```
$env:OLLAMA_MODEL="qwen3:1.7b"
java -cp gson-2.10.1.jar SpringBootAgent.java
```

Windows Command Prompt:
```
set OLLAMA_MODEL=qwen3:1.7b
java -cp gson-2.10.1.jar SpringBootAgent.java
```

Or double-click `run.bat`.

Then open <http://localhost:7070>. Try:

> Create a Spring Boot project called student-api with a Student entity (name, age, email), build it, run it and test it.

Stop the agent with `Ctrl+C`. Closing the browser tab does not stop it.

## Settings (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `OLLAMA_MODEL` | `qwen3:4b` if installed | Model to use |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama address |
| `OLLAMA_CTX` | `16384` | Context size in tokens |
| `OLLAMA_THINK` | `false` | Let the model reason longer (slower) |
| `AGENT_PORT` | `7070` | Port of the web interface |
| `AGENT_WORKSPACE` | `workspace` | Folder the agent may read and write |
| `AGENT_CMD_TIMEOUT` | `600` | Seconds allowed per command |
| `AGENT_JAVA_VERSION` | `17` | Java version for new projects |
| `AGENT_AUTO_APPROVE` | `false` | Skip the Allow/Deny prompt (not recommended) |

## Safety

- File tools only work inside the workspace folder.
- **Shell commands are not limited to the workspace.** Once you click Allow, a command runs with your normal user permissions. Read every approval before allowing it.
- Keep "Run commands without asking" switched off.
- The server only listens on `127.0.0.1` and is meant for one user on one computer. Do not expose it to the internet.
- Use Git on any project you let the agent edit, so changes can be undone.
- Small local models can make mistakes or invent answers after a failed tool call. If a tool card says **Failed**, do not trust the text that follows.

## Troubleshooting

- **"Address already in use"**: another copy is still running. Stop it, or set `AGENT_PORT` to another number.
- **Page says Offline**: the agent stopped. Start it again and refresh the page.
- **A tool card says Failed for a project**: check that `AGENT_WORKSPACE` is the folder that contains the project, not the project itself.
- **Ollama error about tools**: choose a model that supports tool calling.

## License

Add a license of your choice (for example MIT) in a `LICENSE` file.
