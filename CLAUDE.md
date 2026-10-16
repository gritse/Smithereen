## Code style

New Java code uses standard Java formatting, not the compact style inherited from upstream Smithereen:

- Spaces around assignment, comparison and arithmetic operators: `int t = width;`, `a == null`, `(rotation % 360 + 360) % 360`.
- A space after control keywords and before an opening brace: `if (a == null) {`, `for (Item item : items) {`, `} else {`,
  `} catch (IOException x) {`, `try (InputStream in = open()) {`, `switch (kind) {`.
- A space before the brace of classes, records and methods, none before the parameter list: `public record Foo(int a) {}`,
  `void run() {`, `foo(bar)`.
- Spaces around the lambda arrow: `x -> x.id()`.

Indentation stays tabs, as `.editorconfig` says, so new lines line up with the rest of the file.

Existing code stays as it is: do not reformat lines you are not otherwise changing. A file with both styles is fine; a
whole-file reformat buries the real change in the diff and makes merges from upstream Smithereen painful. A line you
rewrite gets the standard style; a one-token edit to an old line (an extra argument, a renamed variable) may keep its style.
