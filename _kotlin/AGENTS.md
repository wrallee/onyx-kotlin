For Superpowers workflows, save implementation plans under
`docs/superpowers/plans/`, not `plans/`.

# Communication Protocol
- **Direct Answer First**: Whenever the user asks a question, gives feedback, or makes an inquiry, ALWAYS answer the user's question directly in the text response FIRST before executing any tools, running background commands, or making code edits.
- **Never Run Tools Instead of Answering**: Stop and respond immediately when a question is asked. Never proceed with background work or tool invocations while ignoring or delaying answers to the user's inquiry.
- **Fact-Based Explanation**: Answer with precise facts and root causes, avoiding speculation.


# Container Workload Safety

Do not start the complete Compose stack without checking available resources and required services.

Before running `docker compose up`, `docker compose build`, scaling services, or starting integration infrastructure:

- Check available memory, swap, CPU load, disk space, and running containers.
- Resolve the services, profiles, and dependencies that the command will start.
- Inspect declared memory, CPU, and PID limits. Do not assume an unlimited service is safe.
- Preserve enough capacity for the host, SSH, Docker Engine, and Codex.
- Start only the services required for the current task. Use explicit service names and profiles.
- Start dependency groups incrementally. Verify health and resource usage after each group.
- Stop before starting more services if memory, swap, CPU, I/O, disk, or health becomes unsafe.
- Do not bypass a failed resource check by retrying the unrestricted command.
- Do not use unbounded parallel builds or unbounded service scaling.
- Do not remove volumes or broadly stop unrelated containers without explicit user authorization.
