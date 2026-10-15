# TinyMVVM agent commands

> **GENERATED** by `./tinymvvmctl docs` from the screens' agent declarations. Do not edit by hand; run `./tinymvvmctl docs` instead.

TinyMVVM is TinyApp rebuilt as MVVM: Jetpack ViewModels under a coordinator, no store.
It is driven with the same commands, and runs TinyApp's scenario files (`examples/tinyapp/scenarios/*.appctl`).

## Script syntax

- Commands are separated by `;` or newlines. `#` starts a comment.
- A command's argument is the rest of the line: `<command> a value with spaces`.
- Double quotes protect `;` and `#`: `<command> "a;b#c"`. Inside quotes, `\"` is a quote.
- After each command the app settles and one step is printed:

  ```text
  > open 2
    screen=items/2 title="Second item" saved=false cooldown=0
  ```

  The line holds `screen`, the screen's summary keys, `calls=` (mock calls made during the step),
  `error=` (if set) and `pending=` (effects waiting on the clock, e.g. a countdown; headlessly
  `advance <duration>` releases them).
- Exit codes: `0` all steps passed, `1` a command or `expect` failed, `2` usage or parse error, `3` internal error.

## Examples

```bash
./tinymvvmctl run "open 2; save"                       # save the second item
./tinymvvmctl test                                     # TinyApp's scenarios, on the MVVM app
```

Scenarios in `examples/tinyapp/scenarios/*.appctl` use the same syntax plus `expect` lines; `./tinymvvmctl test` runs them.

## Runtime commands (every screen)

| Command | Description |
|---|---|
| `expect k=v [k=v …]` | Assert on screen, any summary key, call=<client.method> (called during the previous step), error=<code\|none> or pending=<n>. A failed assertion fails the script. |
| `advance <duration>` | Move the app's clock forward, e.g. 500ms, 30s, 5m, 1h, firing the timers due. |
| `mock <client.method> <error>` | Make the next call to that method fail, e.g. mock items.fetch network. |
| `device open-url <url>` | Open a link in the app, as a tapped deep link or universal link. |
| `device push <name> [tap]` | Deliver a push notification while the app is in front; with tap, open the app from it. Pushes: item-updated (Item 2 was updated.). |

## Screens

### `items`

Screen: `Items`.
Summary keys: `items`, `loading`.

| Command | Description | From |
|---|---|---|
| `open <id>` | Open an item, e.g. open 2. *(disabled when items=0)* | Items |
| `refresh` | Load the list again. | Items |
| `retry` | Load the list again after a failure. *(disabled when error=none)* | Items |

### `items/<id>`

Screen: `ItemDetail`.
Summary keys: `title`, `saved`, `cooldown`.

| Command | Description | From |
|---|---|---|
| `save` | Save this item. A save starts a 3-second cooldown; saving again before it runs out reports error=cooldown. | ItemDetail |
| `back` | Go back to the list. | AppCoordinator |

## Mockable methods

`mock <method> <error>` makes the next call to the method throw that error.

| Method | Errors |
|---|---|
| `items.fetch` | `network`, `timeout` |

