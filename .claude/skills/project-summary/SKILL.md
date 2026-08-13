---
name: project-summary
description: Keep DanNet's PROJECT_SUMMARY.md accurate. Use after completing any change that adds/removes namespaces or dependencies, alters architecture or data flow, adds endpoints, UI components, MCP tools, or bootstrap datasets, or changes the development workflow — and whenever asked to "update the project summary". Small function-level edits do not qualify.
---

# Maintaining PROJECT_SUMMARY.md

PROJECT_SUMMARY.md is the codebase map that AI sessions (and humans) rely on.
It was originally generated and maintained by clojure-mcp's update prompts;
those don't run under Claude Code, so keeping it accurate is now part of
finishing substantial work.

## When to update

After completing work that changes what the file *claims*: new or removed
namespaces, new dependencies, changed architecture or data flow, new web
endpoints or UI components, new MCP server tools, changed build/test/dev
workflow, new bootstrap datasets. If a change only alters the inside of
existing functions, the summary is not affected — leave it alone.

## How to update

- Surgical edits only: touch the sections the change affects, preserve the
  overall heading structure and tone. Other tooling references section names
  (e.g. the browser-repl-debug skill grew out of "Browser REPL Debugging
  (shadow-cljs)"), so don't rename headings casually.
- Verify claims against the actual code before writing them — read the
  namespace, check deps.edn, or probe via the REPL. Don't describe the change
  from memory of what you intended; describe what landed.
- The file's register is factual and compact: what exists, where it lives,
  how it connects. No selling, no progress narrative, no changelog entries —
  git history covers history.
- Prefer removal over accretion: when a section describes something that no
  longer exists, delete it rather than layering corrections ("nothing left to
  take away").
- Keep it a summary. If a section needs deep detail, it likely belongs in
  doc/ with a pointer here.

## Structure to preserve

Top-level sections as of August 2026: Overview · Key Architecture Components
(one subsection per subsystem, with namespace names in the headings) · File
Structure · Dependencies · Development Workflow (Setup, REPL Development,
Frontend Development, Browser REPL Debugging, Running Tests, Building
Releases) · Conventions and Patterns · Extension Points · Integration
Examples · Performance Considerations · Related Documentation · Special
Instructions for AI/LLM Assistants.

After a large milestone (new subsystem, major refactor), suggest a full
review pass to Simon instead of silently rewriting large parts.
