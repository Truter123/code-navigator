# Briefing Enhancements: Richer Indexer + Enhanced Output

## Problem

The current briefing output is shallow — endpoints show only base paths, services and records are not included, and Angular components lack detail. The indexer stores only the first 10 lines of each class as `code_snippet`, discarding method signatures, HTTP annotations, and record fields that JavaParser already parses.

## Design Principles

- Extract structured data during indexing via JavaParser AST — not regex on source files
- New `methods` table in SQLite — keeps `nodes` table clean
- BriefingGenerator queries `methods` table for richer output
- No new dependencies

---

## Schema Change: `methods` table

```sql
CREATE TABLE IF NOT EXISTS methods (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    return_type TEXT,
    parameters TEXT,
    annotations TEXT,
    visibility TEXT
)

CREATE INDEX IF NOT EXISTS idx_methods_node ON methods(node_id)
```

### What gets stored

| Node Type | Rows stored | name | return_type | parameters | annotations | visibility |
|-----------|-------------|------|-------------|------------|-------------|------------|
| CONTROLLER | Public methods | `getAll` | `ResponseEntity<List<HabitResponse>>` | `` | `GET /habits` | `public` |
| SERVICE | Public methods | `findAll` | `List<Habit>` | `` | null | `public` |
| REPOSITORY | Interface methods | `findById` | `Optional<Habit>` | `UUID id` | null | `public` |
| RECORD | Record parameters (fields) | `id` | `UUID` | null | null | `field` |

### HTTP annotation format

Class-level `@RequestMapping("/habits")` combined with method-level:
- `@GetMapping("")` → `GET /habits`
- `@PostMapping("/{id}/relapse")` → `POST /habits/{id}/relapse`
- `@DeleteMapping("/{id}")` → `DELETE /habits/{id}`
- `@RequestMapping(method = RequestMethod.GET, value = "/{id}")` → `GET /habits/{id}`

---

## Indexer Enhancement: `MethodExtractor`

New class `com.codenavigator.indexer.MethodExtractor` that extracts methods/fields from JavaParser AST nodes.

### For ClassOrInterfaceDeclaration (Controller, Service, Repository)

```java
decl.getMethods().stream()
    .filter(m -> m.isPublic() || decl.isInterface())
    .map(m -> new MethodRecord(
        nodeId,
        m.getNameAsString(),
        m.getTypeAsString(),
        formatParameters(m.getParameters()),
        extractHttpAnnotation(decl, m),  // combines class + method annotations
        m.isPublic() ? "public" : "interface"
    ))
```

### For RecordDeclaration

```java
decl.getParameters().forEach(p -> new MethodRecord(
    nodeId,
    p.getNameAsString(),
    p.getTypeAsString(),
    null,
    null,
    "field"
))
```

### HTTP annotation extraction

```java
private String extractHttpAnnotation(ClassOrInterfaceDeclaration classDecl, MethodDeclaration method) {
    String basePath = extractAnnotationValue(classDecl, "RequestMapping");
    
    for (String httpMethod : List.of("Get", "Post", "Put", "Delete", "Patch")) {
        String path = extractAnnotationValue(method, httpMethod + "Mapping");
        if (path != null) {
            String fullPath = (basePath != null ? basePath : "") + path;
            return httpMethod.toUpperCase() + " " + fullPath;
        }
    }
    return null;
}
```

### Integration with ProjectIndexer

After extracting nodes, run MethodExtractor on the same CompilationUnit:

```java
// In ProjectIndexer.indexFull() and indexIncremental():
var methodExtractor = new MethodExtractor();
var methods = methodExtractor.extract(cu, nodes);  // takes nodes to get nodeIds
store.saveMethods(methods);
```

---

## GraphStore Changes

New methods:

```java
public record MethodRecord(String nodeId, String name, String returnType,
                            String parameters, String annotations, String visibility) {}

public void saveMethod(MethodRecord method) { ... }
public void deleteMethodsByNodeId(String nodeId) { ... }
public List<MethodRecord> findMethodsByNodeId(String nodeId) { ... }
public List<MethodRecord> findMethodsByNodeIds(List<String> nodeIds) { ... }
```

Schema init adds the `methods` table. Incremental sync deletes methods for changed nodes before re-extracting.

---

## Enhanced BriefingGenerator Output

### `endpoints.md` (enhanced)

```markdown
# Endpoints

## HabitController (/habits)
  GET    /habits                → List<HabitResponse>
  POST   /habits                → HabitResponse
  POST   /habits/{id}/relapse   → HabitResponse
  DELETE /habits/{id}           → Void

## GymController (/gym/sessions)
  GET    /gym/sessions          → List<WorkoutSession>
  POST   /gym/sessions          → WorkoutSession
  DELETE /gym/sessions/{id}     → Void
```

Query: For each CONTROLLER node, get methods where `annotations` is not null. Parse annotation into HTTP method + path. Use `return_type` for response.

### `models.md` (new)

```markdown
# Models

## com.kamil.woa.habits
- **Habit**: id: UUID, name: String, currentStreak: int, longestStreak: int, lastCleanDate: LocalDate, createdAt: Instant
- **HabitResponse**: id: UUID, name: String, currentStreak: int, longestStreak: int, lastCleanDate: LocalDate
- **CreateHabitRequest**: name: String

## com.kamil.woa.gym
- **WorkoutSession**: id: UUID, date: LocalDate, notes: String
- **WorkoutExercise**: id: UUID, sessionId: UUID, name: String, order: int
- **WorkoutSet**: id: UUID, exerciseId: UUID, weight: double, reps: int
```

Query: For each RECORD node, get methods where `visibility = 'field'`. Group by package.

### `services.md` (new)

```markdown
# Services

## HabitService
  findAll() → List<Habit>
  create(String name) → Habit
  relapse(UUID id) → Habit
  delete(UUID id) → void

## GymService
  findAll() → List<WorkoutSession>
  create(WorkoutSession session) → WorkoutSession
```

Query: For each SERVICE node, get methods where `visibility = 'public'`.

### `components.md` (new)

```markdown
# Components

## HabitsPageComponent
  uses: HabitService → HabitController

## PronunciationDashboardComponent
  uses: PronunciationService → PronunciationController

## WordListComponent
  uses: WordDeepDiveService → WordController

## WordPracticePageComponent
  uses: WordDeepDiveService → WordController
```

Query: For each FE_COMPONENT node, follow USES_SERVICE edges to get FE_SERVICE, then follow CALLS_API edges to get CONTROLLER. No methods table needed — pure graph traversal.

---

## Files Changed

| File | Action | What |
|------|--------|------|
| `graph/GraphStore.java` | Modify | Add `methods` table schema, CRUD methods for MethodRecord |
| `graph/GraphStore.MethodRecord` | Add | New inner record |
| `indexer/MethodExtractor.java` | Create | Extract methods/fields from JavaParser AST |
| `indexer/ProjectIndexer.java` | Modify | Call MethodExtractor after NodeExtractor |
| `briefing/BriefingGenerator.java` | Modify | Enhanced endpoints, new models/services/components generators |
| `mcp/CodeNavigatorMcpServer.java` | Modify | `cg_node` tool can optionally show methods |

## Test Files

| File | What |
|------|------|
| `indexer/MethodExtractorTest.java` | Test extraction from sample Java files |
| `graph/GraphStoreTest.java` | Test method CRUD operations |
| `briefing/BriefingGeneratorTest.java` | Test enhanced output |

## Implementation Order

1. GraphStore schema + MethodRecord CRUD
2. MethodExtractor (core extraction logic)
3. ProjectIndexer integration
4. Enhanced BriefingGenerator (endpoints, models, services, components)
5. Re-index woa and verify output
