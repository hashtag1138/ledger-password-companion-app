# Root Cause of the `show second` Crash in `app-passwords`

Date: `2026-06-03`

## Summary

The crash observed when running `show` on the second item does not come from the companion. The root cause is in `app-passwords`, in the index calculation used when selecting an item from `Passwords list`.

The key point is:

- the UI callback already receives the absolute index of the selected item;
- the code recomputes an absolute index with `page * nbPasswordsPerPage + index`;
- on the second item, that recomputation points past the end of the list;
- the next lookup then dereferences the wrong entry and crashes.

## Validated Cases

The bug was reproduced on a clean Speculos build without automatic population.

Minimal UI-only reproducer:

1. create `a`;
2. create `b`;
3. open `Passwords list`;
4. select the second item;
5. trigger `show`.

Observed behavior before the fix:

- the selection path crashes on the second item;
- the same `multi-entry -> show second` pattern already crashes without the companion.

Observed behavior after the fix:

- the selected nickname is correctly `b`;
- `show` and `type` operate on the expected item;
- the reproducer no longer crashes.

The issue was also revalidated on companion-driven flows using pushed metadata, including `["alpha", "beta"]`.

## Incorrect Callback

The faulty callback was:

```c
selector_callback((page * nbPasswordsPerPage) + index);
```

On Nano / Speculos, `index` is already the absolute index of the current choice in `CHOICES_LIST`.

Consequence:

- first item: `0 * 1 + 0 = 0`, so it still works;
- second item: `1 * 1 + 1 = 2`, so it goes out of bounds for a two-item list.

That exactly explains the observed symptom:

- `show` on the first item works;
- `show` on the second item crashes;
- the crash does not require malformed metadata.

## Proposed Fix

The fix is to forward `index` directly to `selector_callback()` and stop recomputing it from `page`.

Applied patch:

- [docs/app-passwords-show-second-index-fix.patch](/home/sofian/Sources/ledger-passwords-companion/docs/app-passwords-show-second-index-fix.patch:1)

Relevant change:

```c
UNUSED(page);
if (selector_callback) {
    selector_callback(index);
}
```

The patch also removes the now-unnecessary per-page choice count.

## Fixed Behavior

After the fix:

- selecting the second item correctly targets entry `1`;
- `show` and `type` operate on the correct nickname;
- the reproduced regression cases no longer crash.

## Scope

This fix targets the critical `multi-entry -> show second` reproducer and the class of bugs caused by wrong list indexing in `Passwords list`.

It does **not** automatically close the other findings from the fuzzing report, especially:

- malformed metadata still accepted by `LOAD_METADATAS`;
- deletion flows that can target the wrong item on some visually confusable corpora;
- transport/UI interleaving issues during prompts;
- persistent-state pollution in some Speculos restart scenarios.

## Conclusion

For this specific reproducer, the companion is not the source of the crash. The root cause is a bug in `app-passwords` list selection logic.
