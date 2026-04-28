# Postmark — Claude Code Instructions

## Project
Android SMS app. Kotlin + Jetpack Compose.
See BRIEFING.md for full context before starting any task.

## How to approach problems

### Before implementing anything
1. Read the relevant existing code first
2. State the problem in one sentence from the user's perspective
3. List 2-3 possible approaches ranked by simplicity
4. Identify the minimum number of moving parts needed
5. Only then implement — starting with the simplest approach

### Prefer deletion over addition
- Always ask: can this be solved by removing code?
- If a fix requires adding >20 lines, look for a simpler path
- Delete dead code, tests for removed approaches, and 
  unused helpers as part of every fix

### Challenge assumptions
Before implementing, list assumptions the solution makes.
Question whether each assumption is actually necessary.
The calendar date scroll fix is a good example — the 
assumption that offset math was needed turned out to be 
false because the calendar already guaranteed the date exists.

### Testing
- Write tests for pure functions, not implementation details
- If you're deleting an approach, delete its tests too
- Run ./gradlew test after every change and confirm passing

### Never
- Add complexity to work around a simpler root fix
- Maintain parallel data structures when one will do
- Hardcode pixel values — always derive from layout
- Use fallbackToDestructiveMigration on Room database

## Code style
- Kotlin idiomatic — use ?.let, when expressions, 
  extension functions over utility classes
- StateFlow over LiveData everywhere
- Hilt for all dependency injection — no manual DI
- Pure functions extracted to domain layer for testability
- No Mockito, no MockK, no Turbine in tests

## When stuck
If a problem has more than 3 moving parts, stop and 
describe the problem — there is probably a simpler solution.
