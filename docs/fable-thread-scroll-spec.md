# Thread scroll fixes — Fable spec, 2026-07-19

Two on-device reports from Chris. Implementation delegated (Task A: Opus,
Task B: Sonnet, sequential — both touch `ThreadScreen.kt`). Fable reviews the diff.

Repo rules that apply: no commit (Fable/Chris handles it), run
`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
and confirm green before finishing. Match surrounding comment style.

---

## Task A — sending a message must always scroll to it — [x]

**Report:** "I recently sent a message and all I saw was the 'drop to bottom' icon
appear and not the new message. It was there, but the screen did not automatically
move to it."

**Root cause (confirmed by reading, not guessed):** `ThreadViewModel.sendMessage`
inserts the optimistic row, then `_scrollToBottomEvent.tryEmit(Unit)`
(`ThreadViewModel.kt:1077` MMS path, `:1148` SMS path). The comment claims
insert-before-emit means "the message is already in the list when the UI receives
the event" — false: Room committing is not the UI flow having re-emitted, recomposed,
and laid out. `ThreadScrollToBottomEffect` (`ThreadScreen.kt:5227`) then runs
`listState.animateScrollToItem(0)` against the OLD list (reverseLayout: index 0 =
old newest). When the new row lands a frame later, the keyed LazyColumn re-anchors to
that old item — the sent message stays below the fold. The recovery effect
`ThreadNewMessageScrollEffect` (`:5241`) only snaps when `firstVisibleItemIndex <= 1`;
scrolled further up it just raises the FAB — exactly what Chris saw.

**Fix (decision made — do it this way):** carry the new message id in the event and
have the UI wait until that id exists in the render state before scrolling. This is
the established in-file pattern — see `scrollToMessageCentered`
(`ThreadScreen.kt:711-714`) and the `rememberUpdatedState` rationale comment above it.

1. `ThreadViewModel.kt:186-187` — `MutableSharedFlow<Unit>` → `MutableSharedFlow<Long>`
   (keep `extraBufferCapacity = 1`); public `SharedFlow<Long>`.
2. `:1077` and `:1148` — `_scrollToBottomEvent.tryEmit(tempId)`. Rewrite the
   "Signal scroll AFTER the insert" comments: the id lets the UI wait for the row to
   reach the composed list — insert order alone never guaranteed that.
3. `ThreadScreen.kt:491` — default param becomes `SharedFlow<Long> = MutableSharedFlow()`.
4. `:743` — pass `currentRenderState` (already defined at `:710`) into the effect:
   `ThreadScrollToBottomEffect(scrollToBottomEvent, listState, currentRenderState)`.
5. `ThreadScrollToBottomEffect` (`:5227`) — new shape:

   ```kotlin
   @Composable
   private fun ThreadScrollToBottomEffect(
       scrollToBottomEvent: kotlinx.coroutines.flow.Flow<Long>,
       listState: androidx.compose.foundation.lazy.LazyListState,
       renderState: androidx.compose.runtime.State<ThreadRenderState>   // match :710's actual type
   ) {
       LaunchedEffect(Unit) {
           scrollToBottomEvent.collect { sentId ->
               // Wait for the optimistic row to reach the composed list — scrolling
               // before it exists lands on the old newest item and the keyed list
               // re-anchors there when the row arrives (the reported no-scroll bug).
               withTimeoutOrNull(1_000) {
                   snapshotFlow { renderState.value.messageIdToIndex.containsKey(sentId) }
                       .first { it }
               }
               listState.animateScrollToItem(0)
           }
       }
   }
   ```

   `withTimeoutOrNull` guard: `collect` is sequential — an id that never appears must
   not wedge every later send's scroll. On timeout, scroll anyway.
   Verify the real type name of `uiState.renderState` and its `messageIdToIndex`
   member before writing (read `ThreadScreen.kt:702-734` and the uiState class).
6. `ThreadNewMessageScrollEffect` — DO NOT touch. Its near-bottom-snap/FAB-nudge
   behavior for incoming messages is intended.
7. Update the KDoc on `ThreadScrollToBottomEffect` (currently `:5222-5225`).

**Tests:** no new pure functions — no new tests (repo rule: test pure functions, not
implementation details). Full `./gradlew test` must stay green.

---

## Task B — scroll-to-latest FAB takes the thread's sent-bubble colors — [x]

**Request:** "I'd like the drop to bottom icon to take on my sent message bubble
colors to match the theme of the thread."

**Current:** `ScrollToLatestButton` (`ThreadScreen.kt:1467-1486`) hardcodes
`tertiaryContainer` / `onTertiaryContainer`.

**Fix (decision made):**

1. Add `containerColor: Color` and `contentColor: Color` params to
   `ScrollToLatestButton`; feed them to the `SmallFloatingActionButton`.
2. Call site (`:1375-1384`): pass the thread's resolved sent-bubble pair with the
   same fallback an un-customized sent bubble uses (see the Phase-I comment at
   `:618-626` — un-customized sent bubbles fill with `primaryContainer`):

   ```kotlin
   containerColor = bubbleAccentColors.sentContainer
       ?: MaterialTheme.colorScheme.primaryContainer,
   contentColor = bubbleAccentColors.sentContent
       ?: MaterialTheme.colorScheme.onPrimaryContainer,
   ```

   `bubbleAccentColors` (`:628-647`) is in scope at the call site and is already
   resolved against the chat background for legibility — no extra work.
3. No other call sites exist (private composable, single use).

**Tests:** none (pure UI wiring). Full `./gradlew test` green.

---

## Review checklist (Fable) — [x]

- [x] Diff matches spec; no drive-by changes
- [x] Comments updated where behavior changed (ViewModel emit sites, effect KDoc)
- [x] `./gradlew test` green (run after each task; final run covered both)
- [ ] Chris device-checks: send while scrolled up → screen follows the message;
      FAB matches sent-bubble color in a themed thread
