# Todo

## Autocomplete
- Autocomplete POJO constructor This(T f, U g) { this.f = f; ... }

## Navigation
- Go-to-subclasses
- Test coverage codelens
- Go-to-implementation for overridden methods
- `Thing#close()` shows 0 references for `try (thing)`
- Use package graph to limit search for find-usages/goto-def

## Bugs 
- Deleting file doesn't clear it from javac
- External delete causes find-references to crash because it's still in FileStore.javaSources()
- `return json.get("name").` doesn't auto-complete
- `package |` should auto-complete based on package of other files in same folder, even if folder path doesn't match package path.
- Restart debug test doesn't work
- `assertThat` docstring shows `<code>...</code>`
- Debugger doesn't remove breakpoints

## Polish
- Error squigglies should be on method name, not entire method
- Underline vars that are not effectively final
- Not-used warning should hit all unreachable code