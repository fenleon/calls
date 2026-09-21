# Native Phone panels — design reference for the Calls tool

Extracted 2026-09-20 from the LP3's native Phone tool captures
(`reference/lp3/screens/phone/*.png` + matching uiautomator dumps, proprietary/gitignored)
and the LightOS Hermes bundle (`com.lightos` `index.android.bundle`, bytecode v96 —
string table confirms wording). General grammar (palette, bars, row anatomy) is NOT
repeated here — it lives in `reference/lp3/notes/DESIGN.md` §2/§5/§6/§8/§15; this file
only pins the call panels' own geometry and states. All coordinates on the 1080×1240 screen.

## 1. Call screen (one design, three states)

`call-incoming` / `call-active`(connecting) / `call-connected` share the same layout.
Status line + peer name are centered; no top bar; no elapsed-time timer in the
connected state (DESIGN.md §8).

| Element | Bounds | Notes |
|---|---|---|
| Status line | "connected" [386,384][694,507]; "incoming..." [383,384][698,507] | lowercase, ink ≈123 px — Dialer-display scale (DESIGN.md §3), lighter weight than the peer name |
| Peer name / number | [450,547][630,697] (ink ≈150 px) | large centered; name when known, formatted number when not |
| Toggle row (connected) | icons at x 205/402/598/795, y 737–846 (80 wide) | mute · speaker · bluetooth · keypad-grid; **underline = active** (DESIGN.md §15 — same convention as the BT-connected glyph) |
| Hang-up × | [500,1120][580,1200] | single centered icon in the 160 px bottom bar |
| Incoming decline × | [85,1120][165,1200] | bottom-left (`actionBar-button-close`) |
| Incoming answer △ | [915,1120][995,1200] | bottom-right (`actionBar-button-confirm`) |

Wording (exact, from the Hermes string table + dumps): `connecting...`,
`connected`, `incoming...`, `call ended` (toast, ~1.5 s, lowercase — DESIGN.md §8),
`call failed`. No timer string in the connected UI.

State deltas:
- **incoming** — no toggle row; decline/answer replace hang-up.
- **connecting / connected** — 4-toggle row; connected underlines mute when muted,
  bluetooth when a BT device is connected.
- **in-call keypad** (`call-active.png`) — tapping keypad-grid replaces status +
  toggles with the dialer keypad (identical geometry, §2 below); bottom bar returns
  to a single centered × (native also shows a HIDE label variant — DESIGN.md §8).

## 2. Keypad (shared by dialer and in-call keypad)

DESIGN.md §6 pins the base grid; dump re-verified:

- Keys 240×200, 3×4 grid: columns x 180/420/660/900, rows y 281/481/681/881
  (last row 881–1080: `* 0 #`). Digits centered, ink ≈42–74 px wide, **no letter
  sub-labels**.
- Pure black background; nothing actionable is shown before there is input (§5).

## 3. Dialer states (outgoing path)

DESIGN.md §5/§6 carry the full rule; the dump data behind it:

- **Empty** — no top bar, no call button; bottom bar = single close ×.
- **Typed** — top bar `add-contact · typed-number · back`; number centered
  [302,61][779,167] (raw digits, adaptive shrink, one line, "…" overflow —
  `dialer-typed-long.png`); bottom bar call · backspace × · edit.
- In-call keypad digits use the same grid (no top bar, no typed-number display).

## 4. Tool mapping (Calls SPEC §4)

- One `CallScreen` with state variants (`incoming` / `connecting` / `connected`),
  geometry per §1; status strings lowercase, exact wording from §1.
- Toggle row → mute · speaker · bluetooth · keypad-grid; selection = underline
  (SDK `LightIcons` + the §15 BLUETOOTH/BLUETOOTH_CONNECTED pair).
- Hang-up × centered; incoming split decline/answer at the same y-band (1120–1200).
- Dialpad component reusable for dialer + in-call keypad (§2).

## 5. Native implementation (Hermes disassembly, 2026-09-20)

Extracted from the `com.lightos` Hermes bundle disassembly (`/tmp/lightos-disasm.txt`,
annotated hermes-dec; string comments verified against `/tmp/lightos-bundle-strings.txt`).
Function ids quoted so the source region can be re-found. Component names in
`CamelCase` are verbatim from the bytecode; anonymous env-slot components are
described by behavior only.

### 5.1 Screen chain (`Call` → state screens)

`Call` (#19834) is a Redux-connected class; state = `{showDialpad, showBluetoothSettings,
dialpadDisplay, muted:false, callReceivedWhileAsleep, recipientAddress}`.
`render` (#19844) dispatches by priority — **swapping > dialpad > bluetooth-settings >
connecting > incoming > active**:

| Condition (props.relevantCalls.*) | Renders | Props |
|---|---|---|
| `swappingCalls` non-empty | two-calls screen | `rejectCall, swapCalls, activeCall, incomingCall, setPressableIfProximityClose` |
| `state.showDialpad` | in-call dialpad screen | `display: state.dialpadDisplay, onPress: didPressDialpadKey, exitDialpad` |
| `state.showBluetoothSettings` | BluetoothSettingsWrapper (#19849) | `onPressBack: exitBluetoothSettings` |
| `outgoingCallsConnecting` non-empty | ConnectingCallState | `connectingCall, answerCall, disconnectCall, buttons, setPressableIfProximityClose` |
| `incomingCallsRinging` non-empty | IncomingCallState | `incomingCall, answerCall, rejectCall, setPressableIfProximityClose` |
| `callsActive` non-empty | ActiveCallState | `activeCall, disconnectCall, buttons, setPressableIfProximityClose` |

`buttons` = `<ActiveCallButtons onPressDialpad onPressBluetooth/>` (pass-through for
active; used by connecting too). The dialpad display string accumulates in
`didPressDialpadKey` (#19835), which also fires `actions.playDtmfTone(call, key + '')`.

### 5.2 Shared status display — `DisplayArea` (`src/tools/common/DisplayArea/index.js`, #19213)

All three call screens + keypad render through it. Tree (#19215):

```
<Main>
  <Box justifyContent='center' alignItems='center'>
    <Label label={label ?? ''} onLayout/>                    ← status line
    <Title title lightenTitle truncate={truncateTitle===undefined ? true : truncateTitle}/>
    {subTitle && <Title title={subTitle} titleVariant='detail'/>}
    <Bar fullWidth direction='row' justify='space-between'
         top={1} height={6} left={buttonPadding} right={buttonPadding}>   ← toggle-row band
      {buttons}
    </Bar>
  </Box>
</Main>
```

- **Label** (#19217): container `{top: 2, bottom: 1}` (grid units) wrapping
  `LightText variant='copy' lighten={true}` — the lowercase status line
  (`connected` / `incoming...` / `connecting...` / `call waiting...`). No raw
  fontSize in this file; 'copy' variant maps per DESIGN.md §3.
- **Title** (#19220, peer name): `LightText` with adaptive variant —
  `getTitleVariant` (#19219): `'subtitle'` if the text fits
  `gridUnitsToCharacterUnits(22)` (via `textWillOverflow`), else `'heading'`.
  When truncating (default true), a truncate helper is called with
  **max 22 chars, ellipsis side 'tail'**. `lighten` = `lightenTitle` prop.
- Buttons bar: grid units `height 6`, `top 1`; `buttonPadding` = left/right inset —
  **4** for connected/connecting, **0** for call-waiting; incoming has no
  buttonPadding (no buttons prop).
- Wording source per screen (#19881/#19948/#19962 render): label object literals
  `{'label': 'connected', 'title': null, 'buttonPadding': 4}`,
  `{'label': 'incoming...', 'title': null, 'truncateTitle': false}`,
  `{'label': 'connecting...', 'title': null, 'buttonPadding': 4}`,
  `{'label': 'call waiting...', 'title': null, 'buttonPadding': 0}` (WiFi-call
  screens add optional `WifiCallIndicator` when `details.isWifiCall`).
  `title` = `details.callerDisplayName || details.uri`.
- Toast: `'call ended'` rendered as `{'title': 'call ended', 'lightenTitle': true}` (#19988).
  Outgoing overlay status config (#662106 region): `{waitingTime: 3000,
  pendingMessage: 'connecting...', fulfilledMessage: 'connecting...',
  errorMessage: 'call failed'}`.
- ActiveCallState (#19875) keeps `state.timerSeconds` incremented by a 1000 ms
  `setInterval('INCALL_TIMER')`, but the render never displays it — consistent
  with §1 "no timer".

### 5.3 Toggle row — `ActiveCallButtons` (#19990, Fragment of 4 identical cells)

Order and wiring (exact):

1. **MUTE** — `Icons.MUTE`, `underline = props.microphoneMute`,
   onPress `setMicrophoneMute(!microphoneMute)`
2. **SPEAKER** — `Icons.SPEAKER`, `underline = props.speakerphoneOn`,
   onPress `setSpeakerphoneOn(!speakerphoneOn)`
3. **BLUETOOTH** — `Icons.BLUETOOTH`,
   `underline = (props.bluetoothConnectionStatus === 'Connected')`,
   onPress `onPressBluetooth` (opens BluetoothSettingsWrapper)
4. **DIALPAD** — `Icons.DIALPAD`, **no underline prop**,
   onPress `onPressDialpad`

Every cell: `onPress` + `vibration: true` on a wrapper pressable. Note the
underline (not a BLUETOOTH_CONNECTED glyph swap) carries the BT-connected state
here.

### 5.4 `CallSettingIcon` (`src/tools/Call/CallSettingIcon.js`, #20004)

Leaf render (#20006):

```
<UnderlineWrapper active={props.underline}>
  <View onLayout={...} bottom={0.5}>        ← grid units; positions the bar
    <LightIcons.Icon icon={props.icon} size={2}/>
  </View>
</UnderlineWrapper>
```

- **Underline mechanism**: the wrapper component receives `active` (bool) and draws
  a bar at `bottom: 0.5` grid units; the icon container's `onLayout` supplies the
  measured width. (Wrapper/View/Icon are anonymous env-slots — component names
  not recoverable; geometry values are exact.) Icon size = **2 grid units**.
- The component is wrapped in the SDK's tagged render helper:
  `{mode: Modes.DU, onUpdate: true, ghostWeighting: 0.2, tag: 'src/tools/Call/CallSettingIcon.js'}`
  — i.e. a DU-mode haptics/ghost tag with weighting 0.2 (same pattern tags
  Keypad, DisplayArea (0.2), DialerInputDisplay (0.5), DialDisplay).
- Hit area comes from the ActiveCallButtons pressable wrapper (`vibration: true`);
  no explicit hitSlop on the icon itself.

### 5.5 Bottom-bar icons (incoming / hang-up)

- Connected/connecting: `buttonActions` getter (#19879/#19960) = one item
  `{icon: Icons.CLOSE, onPress: disconnectCall}` — a plain × (not a
  phone-down glyph).
- Incoming (#19946): `[{icon: Icons.CLOSE, onPress: attemptCancel→rejectCall},
  {icon: Icons.ACCEPT, onPress: attemptAnswer→answerCall}]` — × left, △ right.

### 5.6 SVG paths (verbatim; bundle-verified, cross-checked against the
light-sdk vector drawables `sdk/ui/src/main/res/drawable/ic_*.xml` — same assets)

| Icon | Geometry |
|---|---|
| MUTE (40×40, 3 paths, fill) | `M36,23c0-0.8-0.7-1.5-1.5-1.5S33,22.2,33,23h0c0,0,0,0,0,0c0,7.2-5.8,13-13,13c-3,0-5.7-1-7.9-2.7L10,35.5    c2.7,2.2,6.2,3.5,10,3.5C28.8,39,36,31.8,36,23C36,23,36,23,36,23L36,23z` · `M15,26.3l11-11l3-3l8.8-8.8l-2.1-2.1l-6.8,6.8C28,4.1,24.4,1,20,1c-5,0-9,4-9,9v13c0,0.9,0.1,1.8,0.4,2.6l-3.1,3.1    C7.5,27,7,25,7,23c0,0,0,0,0,0h0c0-0.8-0.7-1.5-1.5-1.5S4,22.2,4,23h0c0,0,0,0,0,0c0,2.9,0.8,5.6,2.1,7.9l-3.9,3.9l2.1,2.1    l3.5-3.5l2.1-2.1l2.9-2.9L15,26.3z M14,10c0-3.3,2.7-6,6-6s6,2.7,6,6v1L14,23c0,0,0,0,0,0V10z` · `M26,23c0,3.3-2.7,6-6,6c-1,0-2-0.3-2.8-0.7L15,30.5c1.4,1,3.1,1.5,5,1.5c5,0,9-4,9-9v-6.5l-3,3V23z` |
| SPEAKER (80×80, stroke 4, round caps/joins, `currentColor`) | `M67.4004 12.2998C67.4004 12.2998 89.7004 36.8998 67.4004 67.2998` · `M58.4004 18.5C58.4004 18.5 75.6004 37.5 58.4004 61.1` · `M48.8008 25.3999C48.8008 25.3999 60.5008 38.2999 48.8008 54.2999` · `M17.8004 26.1998H2.40039V52.9998H17.8004L38.1004 70.3998V9.2998L17.8004 26.1998Z` |
| Speaker-muted extra (replaces the 3 arcs; same base horn) | `M47.4004 25.7998L75.8004 54.2998` · `M47.4004 54.2998L75.8004 25.7998` |
| BLUETOOTH (20×20, fill) | `M16.5,13.6l-3.3-1.9l-3-1.8l3-1.8l3.3-1.9c0.7-0.4,0.7-1.3,0-1.7l-3.3-1.9L8.5,0v5.5V9v0.1l-4-2.3L3,6v2l0.6,0.4L6.5,10   l-2.9,1.7L3,12v2l1.5-0.9l4-2.3V11v3.5V20l4.8-2.8l3.3-1.9C17.2,15,17.2,14,16.5,13.6z M10.2,5.5V3l2.1,1.2l2.1,1.2l-2.1,1.2   L10.2,8V5.5z M12.4,15.7L10.2,17v-2.5V12l2.1,1.2l2.1,1.2L12.4,15.7z` |
| DIALPAD (viewBox `0 0 40 40`; **9 × `<Circle fill='#fff' r=3.5>`**, cx/cy ∈ {3.5, 20, 36.5}²) | no path strings — circles at (3.5,3.5) (20,3.5) (36.5,3.5) (3.5,19) (20,20) (36.5,20) (3.5,36.5) (20,36.5) (36.5,36.5) [#9750] |
| CALL (40×40, fill; hang-up in native is CLOSE, this is the answer/dial glyph) | `M38.5,27.7l-11-3c-0.2,0-0.3-0.1-0.5-0.1c-0.7,0-1.4,0.4-1.7,1l-3.7,6.5C15.9,28.8,11.2,24.2,8,18.5l6.5-3.7   c0.8-0.5,1.2-1.4,0.9-2.2l-3-11c-0.2-0.9-1-1.5-1.9-1.5H2.1c-1.1,0-2.1,1-2,2.1c0,0.3,0,0.6,0.1,0.9h0c0,0,0,0,0,0.1   c0.3,3.7,1,7.2,2.2,10.5c0,0,0,0,0,0c0.2,0.5,0.4,1.1,0.6,1.6c0,0,0,0,0,0.1c0.4,1,0.8,1.9,1.3,2.9c0,0,0,0.1,0.1,0.1   c3.8,7.4,9.9,13.5,17.3,17.3c0,0,0.1,0.1,0.1,0.1c0.9,0.5,1.9,0.9,2.8,1.3c0,0,0.1,0,0.1,0.1c0.5,0.2,1,0.4,1.6,0.6   c0,0,0.1,0,0.1,0c3.3,1.2,6.8,1.9,10.4,2.2c0,0,0.1,0,0.1,0v0c0.3,0,0.6,0.1,0.9,0.1c0,0,0.1,0,0.1,0c1.1,0,2-0.9,2-2v-8.4   C40,28.7,39.4,27.9,38.5,27.7z M9.7,3.1l2.6,9.6l-5.7,3.3C4.7,11.9,3.5,7.6,3.1,3.1H9.7z M37,36.9c-4.6-0.4-8.9-1.6-12.8-3.4   l3.3-5.7l9.6,2.6V36.9z` |
| CLOSE × (viewBox `0 0 40 40`; `<Polygon>` points, fill) | `33.1,31 22.1,20 33.1,9 31,6.9 20,17.9 9,6.9 6.9,9 17.9,20 6.9,31 9,33.1 20,22.1 31,33.1` |
| ACCEPT △ (viewBox `0 0 51.44 44.58`; path with `transform: translate(-4.28 -7.71)`) | `M30,7.71,17.14,30,4.28,52.29H55.72ZM13,47.2l8.55-14.7L30,17.81,46.94,47.2Z` |
| DENY × (viewBox `0 0 43.23 43.23`; polygon, fill `#fff`) | `43.23 3.62 39.61 0 21.62 18.1 3.62 0 0 3.62 18.1 21.62 0 39.61 3.62 43.23 21.62 25.13 39.61 43.23 43.23 39.61 25.13 21.62 43.23 3.62` |
| CALL_MISSED ×-over-phone (40×40; polygon X + CALL path above) | `37.9,0 34.3,3.5 30.8,0 28.7,2.1 32.2,5.7 28.7,9.2 30.8,11.3 34.3,7.8 37.9,11.3 40,9.2 36.5,5.7  40,2.1` |
| MICROPHONE (40×40, 2 paths, fill) | `M32.9,22.97c0,0.01,0,0.02,0,0.03c0,7.18-5.82,13-13,13s-13-5.82-13-13c0-0.01,0-0.02,0-0.03h-3    c0,0.01,0,0.02,0,0.03c0,8.84,7.16,16,16,16s16-7.16,16-16c0-0.01,0-0.02,0-0.03H32.9z` · `M28.9,10v13c0,4.97-4.03,9-9,9s-9-4.03-9-9V10c0-4.97,4.03-9,9-9S28.9,5.03,28.9,10z M19.9,4     c-3.31,0-6,2.69-6,6v13c0,3.31,2.69,6,6,6s6-2.69,6-6V10C25.9,6.69,23.21,4,19.9,4z` |

Not attributed: a 20×20 fill path `M18.5,8.5H13L7.9,0H5.9L9,8.5H3.4L1.6,6H0l1,4l-1,4h1.5l1.8-2.5H9L5.9,20h2.1l5.1-8.5h5.5c0.8,0,1.5-0.7,1.5-1.5 S19.3,8.5,18.5,8.5z` exists in the bundle but its icon-name mapping was not
recovered (icon modules carry no names; attribution above is by name→drawable
match plus verbatim bundle presence). Everything in the table was found verbatim
in the bundle strings.

### 5.7 `Keypad` (`src/tools/common/Keypad.js`, #15229)

- Default keys: `[1,2,3,4,5,6,7,8,9,'*',0,'#']`; `defaultProps = {vibration: true}`.
- Container (#15237): `{width: 24, height: 20, left: 3, right: 3, direction: 'row', wrap: true}`
  (grid units) — i.e. full-width minus 3-unit margins, 4 rows × 3 cols.
- Key cell style (#15230): `{height: '25%', width: '33.33%', justifyContent: 'center',
  alignItems: 'center', opacity: disabled ? 0.5 : 1}`; `disabledKeys` prop greys keys.
- Pressable per key: `fillInner: true`, `hitSlop: {}` (empty object — defaults),
  `vibration` passed through, `testID: 'keypad-key-<key>'`.
- Digit glyph (#15233): if the key has `.name` → `<Icon size={2.125}>` (icon keys);
  else `LightText variant={keyVariant(key)} lineHeight={70}`.
  `keyVariant` (#15231): string of length ≤ 2 (or any number) → `'subtitle'`,
  else `'fine'` — all standard digits render the **'subtitle'** variant, `lineHeight 70`.
  **No letter sub-labels anywhere in the component** (§2 confirmed).
- Long-press alternates (onLongPress closure #15234): key `0` → `'+'`, key `'*'` → `','`;
  display glyph itself stays `0` / `*`.

### 5.8 Dialer number displays

**`DialerInputDisplay`** (`src/tools/Dialer/DialerInputDisplay.js`, #15501):
props `{showBackspace, showPlus, variant, label, onPressPlus, onPressBackspace}`.

- Row (#15502): `{height: 6, fullWidth, left: 1, right: 1, direction: 'row',
  verticalAlign: 'top', justify: any-button ? 'space-between' : 'center'}`;
  children = optional PlusButton (`onlyPlusShown`), Label, optional BackspaceButton
  (`onlyBackspaceShown`).
- Label (#15504): container `{top: 0.5, fullWidth: when ≤1 button shown}`,
  `LightText variant={props.variant}` (caller chooses the adaptive variant),
  `align: 'center'` when only one button or none shown; children =
  truncate helper with **max 14 chars, ellipsis side 'head'** (leading "…").
  Tag ghostWeighting 0.5.

**`DialDisplay`** (`src/tools/common/DialDisplay.js`, #35245):

- `{direction: 'row', justify: 'start', width: 14}` + onLayout +
  `testID: 'DialDisplay-…'`; first `<View width={7}>` holding
  `LightText variant='title' align='right' lineHeight={FONT_SIZES.title}`
  (main digits), then `<View left={0.5}>` with `LightText variant='heading'
  lineHeight={FONT_SIZES.title}` (secondary segment). Split 7 + 0.5-gap + rest
  of the 14-unit width; both lines share the 'title' font size.
  (The two text children come from a helper closure — exact string formatting
  not recovered; not found: max-length constant for DialDisplay.)

### 5.9 Contradictions / refinements vs §1–4

- §1 toggle row: confirmed exactly (order MUTE·SPEAKER·BLUETOOTH·DIALPAD,
  underline = active). BT-connected state uses the **same underline**, not a
  BLUETOOTH_CONNECTED glyph swap — §4's "§15 BLUETOOTH/BLUETOOTH_CONNECTED pair"
  wording overstates it for the call screen (the pair exists in the SDK, but the
  call screen passes `underline` to one BLUETOOTH icon).
- §1 "no timer": confirmed at render level, but `ActiveCallState` does maintain a
  `timerSeconds` counter (1 s tick) that is simply never rendered.
- §1 wording confirmed verbatim, plus one string not in §1: `'call waiting...'`
  (second-call screen, `buttonPadding: 0` — toggles flush to the edges).
- §2 "keys 240×200": native constants are proportional, not fixed px — container
  24×20 grid units with 3-unit side margins, cells 33.33%×25%. 240×200 px is the
  rendered consequence on the 1080×1240 screen, consistent.
- §3 "…" overflow: the typed-number label truncates at **14 chars with a leading
  ("head") ellipsis**, not a trailing one. Worth re-checking
  `dialer-typed-long.png` — if the capture shows a trailing "…", the capture
  predates or differs from this bundle.
- Incoming screen passes `truncateTitle: false` — the peer name is never
  ellipsized while ringing (active/connecting truncate at 22, tail side).
