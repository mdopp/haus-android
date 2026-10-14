# Haus — Design Guideline

Der visuelle Standard für App-Screens **und** Widgets, damit alles wie *ein*
Produkt wirkt. Neue UI/Widgets richten sich hiernach; Abweichungen sind bewusst
zu begründen. Spiegelt das Web-Frontend von Haus.

> **Zwei Akzente (#130).** In der App ist der Akzent **orange**, sofern die
> Persona von Haus keinen eigenen nennt (`/napi/persona`, `accent`).
> Launcher-Icon, App-Icon-Shortcuts und Widgets werden *neben fremden* Icons
> gesehen; sie behalten das **Markenblau**.

## Palette (`res/values/colors.xml`)
| Rolle | Token | Hex |
|---|---|---|
| Hintergrund | `haus_bg` | `#070709` |
| Erhöht (Statusbar/Nav) | `haus_bg_elevated` | `#0C0C0E` |
| **Akzent (Primär, In-App)** | `haus_accent` | `#F97316` (Orange) |
| Akzent gedrückt | `haus_accent_dark` | `#EA580C` |
| Akzent-Tint (12 %) | `haus_accent_faint` | `#1FF97316` |
| **Markenblau** (Icon/Shortcuts/Widgets/Glow) | `haus_brand_blue` | `#3B82F6` |
| Markenblau gedrückt | `haus_brand_blue_dark` | `#2563EB` |
| Text | `haus_text` | `#F2F2F5` |
| Text gedämpft | `haus_text_muted` | `#9AA0AE` |
| Text schwach | `haus_text_faint` | `#6B7280` |
| Energie „liefert"/„verbunden" | `energy_supply` | `#66BB6A` |
| Energie „bezieht" | `energy_draw` | `#EF5350` |
| Batterie | `energy_battery` | `#7E9CFF` |

**Domänen-Akzente** (Geräte-Widgets, aktiver Zustand): Licht `#FFC107`,
Cover/Jalousie `#7E9CFF`, Schalter `#66BB6A`, Klima `#FF8A65`.

> Regel: **In-App-Akzent ist immer `haus_accent`, Akzent auf dem
> Android-Startbildschirm immer `haus_brand_blue`.** `#7E9CFF` ist die
> *Batterie/Cover*-Farbe — nicht als generischer Akzent verwenden.

## Tokens (`res/values/dimens.xml`)
- Radien: `radius_sm 12` (Felder/Chips/Kacheln) · `radius_md 16` (Buttons) · `radius_lg 20` (Cards).
- Abstände: `space_xs 8 · sm 12 · md 16 · lg 24 · xl 32`; Screen-Gutter 24dp.
- Typo: `text_hero 40 · headline 22 · title 17 · body 15 · label 13 · tile 12`.
- Markenblock: `brand_lockup_top · brand_figure_height · brand_rule_bottom ·
  hero_glow_size · text_hero` (Erstauftritt) und dieselben mit `_compact`
  (gekoppelt, #130) — `OnboardingHomeActivity.renderBrand` schaltet um.
- Text-Styles: `Text.Haus.Hero/Headline/Title/Body/Label/Tile` (`res/values/themes.xml`).

## Widgets — allgemein
- Hintergrund immer **`@drawable/widget_bg`** (22dp, dezenter Vertikal-Verlauf, 1dp-Hairline).
- **RemoteViews-sicher:** nur `LinearLayout/FrameLayout/RelativeLayout/GridLayout`,
  `TextView/ImageView/ProgressBar/Button/ImageButton/ListView` — **nie** ein
  nacktes `<View>` (bricht RemoteViews → „Widget kann nicht geladen werden").
- Icons tintbar, aktiver Zustand = Domänen-Akzent, inaktiv = gedämpft.
- Größenadaptiv (tiny/small/wide/medium/large); kein großer leerer Block.

## Auslöser-Kacheln (Kamera, Sprechen, künftige „öffnet X"-Kacheln) — **einheitlich!**
Kacheln, die nur eine Aktion auslösen, folgen **einem** Muster:

1. **Aufbau:** vertikal, zentriert — **Icon oben, einzeiliges Label darunter**. Root
   `widget_bg`, Padding `space_sm` (12dp).
2. **Icon:** gefüllter **Markenblau-Kreis** (`tile_accent_circle`, #3B82F6) mit
   weißem Glyph — signalisiert „antippen". **Gleiche Größe** überall (Kreis **52dp**,
   Glyph-Padding 12dp), **zentriert**. Kein flaches getöntes Glyph, keine
   abweichende Farbe (`#7E9CFF` etc.).
3. **Label:** **einzeilig**, `Text.Haus.Tile`-Größe (12–13sp) **bold**,
   `haus_text`, `maxLines=1`. **Keine zweite/abgeschnittene Zeile** (kein
   „Kamera öffnen"-Untertitel, der zu „Kamer…" trunkiert).
4. **Konsistenz:** gleiche Icon-Größe, gleiche Position, gleiche Label-Zeilenzahl,
   gleiche Abstände über *alle* Auslöser-Kacheln.

> Kurz: Kamera und Sprechen sollen **identisch** aufgebaut sein — nur Icon + Wort
> unterscheiden sich. Ein gemeinsames Kreis-Drawable (`tile_accent_circle`) und
> ein gemeinsames Kachel-Layout verwenden.

## Geräte-Widgets (Licht/Schalter/Cover)
- **Steuertasten füllen die Breite** (gewichtsbasiert, `layout_weight`), damit bei
  **keiner** Größe ein Button abgeschnitten wird — **nie** feste dp-Breiten, die
  überlaufen (sonst wird z.B. der ▼-Button rechts geclippt).
- **1×1 (TINY):** **Name behalten** (einzeilig, oben, ellipsiert) + der **Toggle
  ist ein tippbares, zustandsgetöntes Domänen-Icon** (kein Button-Kasten, kein „○") —
  Tap = Primäraktion, Name-Tap = PWA. Zustandsbalken unten wie gehabt (#57).
- **Nicht mögliche Aktionen ausgrauen:** eine Cover am Anschlag → die unmögliche
  Richtung ist deaktiviert/gedämpft (offen ⇒ ▲ grau & inert, zu ⇒ ▼ grau & inert).
  Ein grauer Button löst nichts aus.
- Zustand als Balken/Prozent unten; Akzent = Domänenfarbe im aktiven Zustand.

## App-Screens
Ein Bildschirm (`activity_home.xml`, #176): oben die Persona von Haus (Symbol,
Name, eine Linie in ihrem Akzent, der Gruß), darunter ungekoppelt die Kopplung
(Adresse, „Koppeln", QR-Code), gekoppelt die Widgets zum Hinzufügen — erst das
Haus, dann die Werkzeuge aus dem Katalog, zuletzt die Box. Konsistente
Radien/Abstände/Typo aus den Tokens; runde Karten `card_haus`, alle mit
demselben Innenabstand `card_padding`. Karten sagen in **einer** Zeile, was
etwas tut.

