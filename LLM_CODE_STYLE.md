# LLM Code Style Preferences

## Clojure Style Guidelines

### Conditionals

- Use `if` for single condition checks, not `cond`
- Only use `cond` for multiple condition branches
- Prefer `if-let` and `when-let` for binding and testing a value in one step
- Consider `when` for conditionals with single result and no else branch
- consider `cond->`, and `cond->>`

### Variable Binding

- Minimize code points by avoiding unnecessary `let` bindings for simple values
- However, do use `let` bindings to keep more complex expressions from disrupting the application logic
- Use threading macros (`->`, `->>`, etc.) to eliminate intermediate bindings
- Prefer transducers for threading macros with significantly more steps than usual

### Parameters & Destructuring

- Use destructuring in function parameters when accessing elements in collections (maps, vectors, etc.)
- Also use destructuring in let bindings when accessing elements in collections
- For maps: `[{:keys [zloc match-form] :as ctx}]` for regular keywords
- For vectors: `[[first-elem & rest-elems]]` or `[[type & args]]` instead of calling `first` and `rest`
- Sequential destructuring examples:
    - `[[x y z]]` instead of `(let [x (first v) y (second v) z (nth v 2)])`
    - `[[_ _ third-elem]]` to skip unwanted elements
    - `[[head & tail]]` for recursive processing
- Prefer destructuring over explicit calls to `first`, `second`, `nth`, etc.

### Control Flow

- Track actual values instead of boolean flags where possible
- Use early returns with `when` rather than deeply nested conditionals
- Return `nil` for "not found" conditions rather than objects with boolean flags (i.e. nil punning)

### Comments

- Use comments sparingly unless they are docstrings or explain the purpose of a larger section of code (no superfluous
  comments)
- The same goes for println statements that essentially act as comments
- Docstrings should use basic markdown formatting, e.g. `x` and `y` might indicate function params
- Docstrings should generally have 1-2 lines explaining what the function returns and which params it takes
- Other optional parts of the docstring should appear as a separate paragraph of text separated by newlines

### Nesting

- Minimize nesting levels by using proper control flow constructs
- Use threading macros (`->`, `->>`) for sequential operations

### Function Design

- Functions should generally do one thing
- Keep functions small and easily testable
    - split up larger functions and compose them instead
- Pure functions are preferred over functions with side-effects
- Functions with side effects should be appended by !
- Function should return useful values that can be used by callers
- Keep function input simple
    - maps are generally fine
    - sets are better than maps if keys aren't needed
    - vectors are **only** needed when order actually matters
    - small, basic functions should mostly accept simple values (strings, keywords), not data structures

### Library Preferences

- Prefer `clojure.string` functions over Java interop for string operations
    - Use `str/ends-with?` instead of `.endsWith`
    - Use `str/starts-with?` instead of `.startsWith`
    - Use `str/includes?` instead of `.contains`
    - Use `str/blank?` instead of checking `.isEmpty` or `.trim`
- Follow Clojure naming conventions (predicates end with `?`)
- Favor built-in Clojure functions that are more expressive and idiomatic

### REPL Best Practices

- Always reload namespaces with `:reload` flag: `(require '[namespace] :reload)`
- Always change into namespaces that you are working on

### Testing Best Practices

- Always reload namespaces before running tests with `:reload` flag: `(require '[namespace] :reload)`
- Test both normal execution paths and error conditions

### Using Shell Commands

- Prefer the idiomatic `clojure.java.shell/sh` for executing shell commands
- Always handle potential errors from shell command execution
- Use explicit working directory for relative paths: `(shell/sh "cmd" :dir "/path")`
- For testing builds and tasks, run `clojure -X:test` instead of running tests piecemeal
- When capturing shell output, remember it may be truncated for very large outputs
- Consider using shell commands for tasks that have mature CLI tools like diffing or git operations

### Context Maintenance

- Use `clojure_eval` with `:reload` to ensure you're working with the latest code
- always switch into `(in-ns ...)` the namespace that you are working on
- Keep function and namespace references fully qualified when crossing namespace boundaries

## Special Instructions for AI/LLM Assistants

### REPL Interaction Guidelines

- **Shadow-cljs**: Don't attempt to start shadow-cljs - the developer will handle this manually for now
- **Code Evaluation**: Keep REPL evaluations short and focused - test one specific aspect at a time rather than
  exhaustive testing
- **Comment Style**: When writing or rewriting Clojure code, match the comment density of the surrounding code - observe
  the existing ratio of comments to code in nearby functions
- **Rich Comment Blocks**: When creating example code, add it to Rich Comment Blocks in the relevant namespace rather
  than creating code in a user/dev NS
- **Relevant Namespace**: When creating function definitions or other defs, put them directly in the relevant
  namespace (and not in a comment block)
