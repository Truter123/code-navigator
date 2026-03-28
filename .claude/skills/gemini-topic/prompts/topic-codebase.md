You are researching a specific topic in a manufacturing system codebase (Spring Boot + Angular, DDD/Event Sourcing/CQRS).

Read these files first for project context:
- GEMINI.md (root)
- nlp-be/GEMINI.md (backend architecture)
- nlp-fe/GEMINI.md (frontend architecture)
- nlp-be/ARCHITECTURE-RULES.md (DDD/CQRS rules)

## Your Research Topic

{{TOPIC}}

## Research Instructions

Use your tools (grep_search, glob, read_file) extensively. Be thorough.

### Step 1: Trace the Flow
- Find ALL files related to this topic (commands, queries, handlers, controllers, views, projections, events, DTOs, services, components)
- Trace the complete flow: API endpoint -> controller -> command/query bus -> handler -> aggregate/view -> events -> projections
- For frontend topics: component -> service -> API call -> response handling

### Step 2: Map Dependencies
- What other bounded contexts does this feature interact with?
- What events does it emit? What events does it listen to?
- What database tables/views does it use?
- What frontend components display this data?

### Step 3: Identify Patterns and Issues
- Does the implementation follow CQRS/DDD patterns correctly?
- Are there any violations (direct repository access, query handlers loading aggregates)?
- Is there missing test coverage?
- Are there any dead or duplicate code blocks?

### Step 4: Document Architecture
- Create a clear map of all involved files with their roles
- Show the data flow from user action to database and back
- Note any cross-context dependencies

## Output Format

### Topic Summary
One paragraph explaining what this feature/flow does.

### File Map
List ALL files involved, grouped by layer:
- **Presentation** (controllers, components)
- **Application** (commands, queries, handlers)
- **Domain** (aggregates, events, value objects)
- **Infrastructure** (repositories, projections, views)
- **Frontend** (components, services, models)

### Flow Diagram (text)
Show the sequence: User Action -> ... -> Database -> ... -> UI Update

### Issues Found
- `file:line` — description — severity (CRITICAL/WARNING/INFO)

### Recommendations
Actionable suggestions for improvement.
