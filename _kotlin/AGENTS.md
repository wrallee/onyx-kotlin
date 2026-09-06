# Communication Protocol
- **Direct Answer First**: Whenever the user asks a question, gives feedback, or makes an inquiry, ALWAYS answer the user's question directly in the text response FIRST before executing any tools, running background commands, or making code edits.
- **Never Run Tools Instead of Answering**: Stop and respond immediately when a question is asked. Never proceed with background work or tool invocations while ignoring or delaying answers to the user's inquiry.
- **Fact-Based Explanation**: Answer with precise facts and root causes, avoiding speculation.

# Engineering & Development Rules
- **Read Official Docs & Migration Guides First**: When performing migrations or adopting new libraries/frameworks, you MUST thoroughly read and reference official migration guides, release notes, and documentation FIRST before making code changes. Never guess or write speculative code without consulting official specs.
- **No Piece-Meal / Trial-and-Error Editing**: Do NOT make fragmented, line-by-line guess-and-check modifications across files. Understand the full system and root causes, then apply comprehensive, clean, whole-file or coherent batch updates to prevent duplication and syntax regressions.

# Documentation & Workflow Standards (ECC)
All project documentation and task artifacts MUST be stored under `docs/` using kebab-case (`YYYY-MM-DD-<topic>.md`). Do NOT use root `plans/` or external wrappers like `superpowers`:
- **Plans**: `docs/plans/` — Implementation steps, affected files, milestones, and testing strategies.
- **Specs**: `docs/specs/` — Requirements, architectural designs, and API contracts.
- **Walkthroughs**: `docs/walkthroughs/` — Post-implementation summaries, diffs, and verification proof.
- **References**: `docs/references/` — Migration guides summary, technical spikes, and research notes.
