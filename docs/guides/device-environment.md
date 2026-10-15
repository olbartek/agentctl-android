# The device environment

Mobile apps react to the device as much as to taps: a permission the user denied, a system prompt waiting for an
answer, a deep link, a push, the app going to the background, the network dropping, a location fix, biometric
unlock. A script drives all of these with one runtime command, `device`, the same headlessly and in the running app.
The device is **simulated in the app's process**: your permission, connectivity, location and biometry clients read
AgentCtl's `AgentDevice` (`environment.device`) in their mock implementations, as your backends route through
`environment.mocks.call`. So no line touches the real OS: no real dialog, and nothing that kills the app or its
bridge. (Driving the real OS cannot do this: `pm revoke` kills the app, nothing delivers a push to one app, and the
bridge cannot answer a system dialog. The `app` wrappers in the last column do drive the real device, for demos and
real-OS checks.)

| Capability | Script command | What your app wires | What a step shows | Real OS (live only) |
|---|---|---|---|---|
| Permissions | `device permission <service> <not-determined\|granted\|denied\|limited>` | `permissions = listOf("notifications", …)`; fakes call `device.permission(service)` / `device.requestPermission(service)` (suspend) | what your screen's summary shows | `app permission grant\|revoke\|reset <permission>` |
| System prompts | `device prompt <allow\|deny\|limited>` | nothing more: a request while `not-determined` shows the prompt | `prompt=<service>` until answered; `expect prompt=…` | — |
| Deep and app links | `device open-url <url>` | `openUrl = { uri -> … }` returns the root action, or `null` for a link you don't handle | the screen the link opens | `app openurl <url>` (the app declares an intent filter for the scheme) |
| Push notifications | `device push <name> [tap]` | `pushes = listOf(AgentPush(name, help, received, opened))` | what receiving or tapping it does | — (`app push` exits 2 on Android) |
| Background / foreground | `device background`, `device foreground` | `scenePhase = { phase -> … }` returns the root action | screen commands fail while in the background | — |
| Relaunch | `device relaunch` | `relaunch = true`; build the hosts with an `AppFactory` so the clients survive | the fresh launch; what your clients stored, the device and the clock survive | `app launch --no-build` (the real process) |
| Network | `device network <online\|offline>` | `offlineErrorCode = "network"`; observers collect `device.onlineUpdates()` | offline, every mocked call fails with that code | — |
| Location | `device location <lat>,<lon>\|none` | `location = true`; fakes read `device.location` / `device.locationUpdates()` | what your screen does with it | `app location <lat>,<lon>` (an emulator only) |
| Biometry | `device biometry <enrolled\|unenrolled\|match\|fail>` | `biometry = true`; fakes call `device.authenticateBiometry()` (suspend) | `prompt=biometry` until `match` or `fail` | — |
| Appearance | — | — | — | `app appearance light\|dark` |

Every capability is opt-in: an app's `AgentDeviceConfig` (in `AppCtlConfig(deviceConfig = …)` and both hosts)
enables the subcommands it lists, and an app that enables none has no `device` command and prints exactly what it did
before. From AgentShop's
[`AgentShopConfig.kt`](../../examples/agentshop/ctl/src/main/kotlin/io/github/olbartek/agentctl/examples/agentshop/ctl/AgentShopConfig.kt)
and [`DeviceClients.kt`](../../examples/agentshop/shop/src/main/kotlin/io/github/olbartek/agentctl/examples/agentshop/clients/DeviceClients.kt):

```kotlin
AgentDeviceConfig(
    permissions = listOf("notifications", "location"),
    openUrl = AppFeature::actionOpening,
    pushes = listOf(
        AgentPush("order-shipped", help = "Order #1002 has shipped.", received = AppFeature.Action.OrderPushReceived,
            opened = AppFeature.Action.DeepLinkOpened(DeepLink.Order(1002))),
        AgentPush("promo", help = "A new promo code: SAVE10.", opened = AppFeature.Action.DeepLinkOpened(DeepLink.Shop)),
    ),
    scenePhase = { AppFeature.Action.ScenePhaseChanged(it) },
    relaunch = true,
    offlineErrorCode = "network",
    location = true,
    biometry = true,
)

// The notifications client's mock: the request waits for `device prompt allow|deny` while not-determined.
fun live(device: AgentDevice): NotificationsClient =
    NotificationsClient(requestAuthorization = { device.requestPermission("notifications") })
```

A refused permission, from AgentShop:

```
$ ./appctl run "login-as alice; tab profile; order-updates on; device prompt deny"
…
> order-updates on
  screen=home/profile name=Alice email=alice@example.com orderUpdates=off faceID=off alert=none prompt=notifications
> device prompt deny
  screen=home/profile name=Alice email=alice@example.com orderUpdates=off faceID=off alert=none error=notificationsDenied
```

Outside a host (a preview, a release build) the device is *undriven*: online, every request granted at once, so
nothing waits for a prompt no one can answer. The exact grammar, messages and exit codes are
[CONTRACT.md §2.4](../../CONTRACT.md#24-device-subcommand-arguments).
