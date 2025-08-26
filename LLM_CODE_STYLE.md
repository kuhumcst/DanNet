# LLM Code Style Guide

This document outlines the preferred Clojure coding style for code generation. Focus on clarity, composability, and
practical utility.

## Namespace Organization

### Declaration Style

```clojure
(ns dk.cst.xml-hiccup
  "Comprehensive docstring explaining purpose and behavior.
  
  Additional context in subsequent paragraphs (if needed)."
  (:require [clojure.string :as str]
    [clojure.walk :as walk]))
```

**Key Points:**

- Comprehensive namespace docstring after namespace symbol
- Standard aliases: `str`, `walk`, `json`, `io`
- Group `:require` statements logically (core libraries first)

### Naming

- Reverse domain notation: `dk.cst.project-name`
- Kebab-case: `hiccup-tools.elem`

## Function Design

### Documentation

```clojure
(defn parse
  "Convert `xml` (String, File, or InputStream) into Hiccup data.
  
  For File objects, provide :file-meta map in `opts` to specify metadata:
     (parse xml {:file-meta {:path :absolute}})"
  ([xml] (parse xml nil))
  ([xml opts]
   ;; implementation
   ))
```

**Patterns:**

- Comprehensive docstrings with parameter types and examples
- Wrap parameters in backticks: `xml`, `:file-meta`
- Present tense, active voice ("Convert", not "Converts")
- Multi-arity docstring on function name, not individual arities
- Include concrete examples for non-obvious usage

### Forward Declarations

```clojure
(declare attr-val-table prefix-elem anchor-elem)

(defn complex-function [opts data]
  (attr-val-table opts data))  ; Can call function declared above

(defn attr-val-table [opts data]
  ;; implementation that might call complex-function
  )
```

Use `declare` for mutual recursion and complex interdependencies.

### Naming Conventions

```clojure
;; Each example demonstrates a specific naming pattern:
(defn keywordize [s] ...)           ; Simple verb for transform functions
(defn ->tokenizer-xf [...] ...)     ; Arrow prefix for constructors
(defn node->hiccup [node] ...)      ; Arrow infix shows transform direction
(defn attribute-objects [node] ...) ; Plural noun for data extraction
(defn persist-results! [data] ...)  ; Bang suffix for side effects
(defn valid? [x] ...)               ; Question suffix for predicates
```

### Parameter Handling

```clojure
;; destructuring a vector parameter directly
(defn process-pair [[first second]]
  (str first " and " second))

;; multi-arity preferred for different use cases
(defn tf-idf
  ([idf-result document]     ; Process single document
   (calculate-single idf-result document))
  ([documents]       ; Process batch
   (calculate-batch documents)))

;; options map only when truly needed (many optional params)
(defn parse
  ([xml] (parse xml {}))     ; Delegate to fuller arity
  ([xml {:keys [file-meta encoding] :as opts}]
   ;; Use file-meta and encoding here
   ))
```

Simpler arity should delegate to more complex one.

## Control Flow

### Conditionals

```clojure
;; when for single-branch conditional (no else needed)
(when data
  (println "Processing...")
  (process data))
```

### Loops

```clojure
;; Clear accumulator pattern with termination check first
(defn transform-until-nil [items]
  (loop [remaining items
     result []]
    (if (or (empty? remaining)
        (nil? (first remaining)))
      result
      (recur (rest remaining)
         (conj result (transform (first remaining)))))))
```

Consider sequence functions (map, filter, reduce) before explicit loops.

## Data Processing

### Pipelines

```clojure
;; thread-last macro for clear data transformation pipeline
(->> data
     (filter valid?)
     (map normalize)
     (group-by :category))

;; transducer for efficient single-pass processing
;; Avoids intermediate collections
(into []
  (comp (filter valid?)
    (map process-item))
  items)
```

## Error Handling

### Defensive Programming

```clojure
;; Safely handles both "keyword" and "namespace:keyword" formats
(defn keywordize [s]
  (let [[s1 s2] (str/split s #":" 2)]  ; Limit split to 2 parts
    (if s2
      (keyword s1 s2)    ; namespace/name format
      (keyword s1))))    ; simple keyword

;; nil-safe navigation with some->
(defn get-user-email [data]
  (some-> data
    :user
    :contact
    :email))
```

Handle edge cases explicitly and prefer safe operations that won't throw exceptions.

## Resource Management

### Dynamic Vars and I/O

```clojure
;; dynamic var for infrastructure that many functions need
(def ^:dynamic *db-connection* nil)

;; helper function wrapping common I/O pattern
(defn write-file [f m]
  (spit f (with-out-str (pprint/write m))))

;; side-effect function with ! suffix and progress feedback
(defn persist-results! [results]
  (io/make-parents "out/results/")  ; Ensure directory exists
  (doseq [[idx result] (map-indexed vector results)]
     (write-file (str "out/results/" idx ".edn") result)
     (when (zero? (mod idx 100))     ; Progress every 100 items
       (println "processed" idx "items")))
  (println "completed:" (count results) "total items"))
```

**Patterns:**

- Dynamic vars only for infrastructure (DB connections, servers)
- `!` suffix for side effects
- Progress feedback for long operations
- Ensure directories exist before writing
- Pretty-print EDN with `pprint/write` wrapped in `with-out-str`

## Comments and Development

### Comment Blocks

```clojure
;; REPL-friendly development examples
(comment
  ;; Core functions - test these interactively
  (def test-docs [{:text "hello"} {:text "world"}])
  (tf test-docs)
  (df test-docs)

  ;; Utility functions - verify behavior
  (normalize-frequencies (frequencies [:a :b :a :c]))
  ;; => {:a 0.5, :b 0.25, :c 0.25}

  #_.)  ; prevents parinfer from moving the last parenthesis
```

End with `#_.` for to make the final statement of the comment block easy to run.

### Implementation Comments

```clojure
;; Handles most Latin scripts including punctuation
(def space+punctuation #"(\d|\s|\p{Punct})+")

;; TODO: proper <pre> support - currently strips formatting
(defn process-html [html]
  ;; current implementation
  )
```

- regex documentation for complex patterns
- TODO marker for known limitations

### Development Data

```clojure
;; sample data for REPL testing
(def sample-data
  {:users [{:id 1 :name "Alice"}
           {:id 2 :name "Bob"}]})

;; Only computed when first dereferenced with @
(def processed-data
  (delay
    (expensive-computation sample-data)))
```

Use `delay` for expensive computations that might not be needed.

## Advanced Patterns (Use Sparingly)

### When to Use Advanced Patterns

**Cross-Platform Code** - Only for JVM/ClojureScript libraries:

```clojure
;; reader conditional for platform-specific code
#?(:clj  (.parse parser input)    ; JVM version
   :cljs (.parse js/JSON input))   ; Browser version
```

**Complex Destructuring** - Only for tree/Hiccup processing:

```clojure
;; destructuring with optional middle element
;; Handles both [:div "text"] and [:div {:id "x"} "text"]
(defn parse-hiccup [[tag & [attr & children :as rem]]]
  (if (map? attr)
    {:tag tag :attrs attr :children children}
    {:tag tag :attrs {} :children rem}))
```

**Transducers** - Only for reusable pipelines or performance-critical code:

```clojure
;; composable transducer that can be reused
(defn ->frequencies-xf [tokenizer-xf]
  (comp tokenizer-xf
    (map frequencies)
    (map normalize)))

;; Usage: can be applied to different collections efficiently
;; (into [] (->frequencies-xf tokenizer) documents)
```

**Metadata** - Only when truly needed:

```clojure
;; attaching metadata to preserve file info
(defn read-with-meta [file]
  (with-meta (slurp file)
         {:filename (.getName file)
      :path     (.getPath file)
      :modified (.lastModified file)}))
```

Most applications should probably use simpler alternatives than these advanced patterns.

## Summary

**Core Principles:**

- Every public function documented with examples
- Systematic naming: kebab-case with meaningful affixes (`->`, `-xf`, `!`, `?`)
- Multi-arity functions over complex options maps
- Defensive programming with explicit nil handling
- Rich `comment` blocks for REPL development
- Clear separation of concerns

**Key Departures from Common Clojure:**

- More comprehensive documentation required
- Systematic use of naming affixes
- Preference for multi-arity over destructuring
- Preserve collection types with `(empty coll)`

# Additional Instructions for AI/LLM Assistants
- **Shadow-cljs**: Don't attempt to start shadow-cljs - the developer will handle this manually for now
- **Code Evaluation**: Keep REPL evaluations short and focused - test one specific aspect at a time rather than
  exhaustive testing
- **Comment Style**: When writing or rewriting Clojure code, match the comment density of the surrounding code - observe
  the existing ratio of comments to code in nearby functions
- **Rich Comment Blocks**: When creating example code, add it to Rich Comment Blocks in the relevant namespace rather
  than creating code in a user/dev NS
- **Relevant Namespace**: When creating function definitions or other defs, put them directly in the relevant
  namespace (and not in a comment block)
