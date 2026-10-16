# RideSmart (Android, native) — Project Context & Handoff

> Read this first. It explains what the project is, how it works, what's done, what's
> broken (and why), and how to continue — so any developer or AI/CLI tool can pick up
> without the original author re-explaining everything.

---

## 1. The goal (the "why")

**One app to compare live ride prices across Uber, Ola, Rapido, and Namma Yatri, so the
user doesn't have to open each app separately.** Enter pickup + drop once → see the best
option across all apps → book the one you choose.

The vision was: *enter the trip once, get the best suggestion instantly, book on approval.*

---

## 2. Two implementations exist

| Repo | Tech | Prices | UX | Status |
|---|---|---|---|---|
| **RideSmartAndroid** (this repo) | Native Kotlin + Jetpack Compose | **Live** (read from each app's screen) | Fiddly: tap each app, ~10–20s each, occasional retries | Works as a proof-of-concept |
| [RideSmartMobile](https://github.com/Varshini0511/RideSmartMobile) | React Native + Expo (Android/iOS/web) | **Estimated** (fare formulas) | Instant, single-touch, no special permissions | Works, but prices are approximate |

This repo is the **live-price** one. The RN repo is the **instant-estimate** one.

---

## 3. How the live-price app works (architecture)

There are no ride-pricing APIs available to individual developers (see §7), so this app
reads prices **off each ride app's screen** using Android's **Accessibility Service**.

### The flow (current design — "Option 1: tap-to-read")
1. User enters **pickup + drop** on the Home screen (Google Places autocomplete) and picks
   ride categories (Auto / Bike / Mini-Cab / Sedan-SUV).
2. Taps **Compare** → goes to the Results screen (no hands-off background automation).
3. For **each app**, the user taps **"tap to get live price"**. RideSmart then:
   - Opens that app on the route (deep link for Uber/Ola; types the destination in for
     Rapido/NY since they have no route link),
   - **Reads the ride list** via the accessibility service (scrolling the list so options
     below the fold are captured too),
   - **Snaps back** to RideSmart automatically,
   - Shows that app's prices as **✅ live**.
4. Prices are grouped by category, best-price/fastest suggestions shown, savings computed.
5. **Book** = opens the chosen app on the route for the user to confirm manually.

### Why "tap each app" instead of fully automatic
A fully hands-off version (RideSmart opens all 4 apps itself in the background) was built
first but **fails on aggressive OEMs (iQOO/Vivo, Xiaomi, Oppo)** — they freeze/kill the
app the moment it's backgrounded, so it gets stuck. User-initiated taps are never blocked,
so tap-to-read is the reliable design. (The old auto-cycle code still exists but is unused.)

---

## 4. Key files (where things live)

```
app/src/main/java/com/ridesmart/app/
├── MainActivity.kt            # Compose entry; HOME ↔ RESULTS; wires everything; state + persistence
├── data/
│   └── Models.kt              # ProviderName, RideType (4 categories), RideQuote, VehicleClassifier
├── service/
│   ├── RideAccessibilityService.kt  # ★ THE CORE. Reads ride app screens, extracts prices,
│   │                                #   scrolls lists, types routes, clicks, single/auto capture.
│   ├── PriceScraper.kt        # Converts captured prices → RideQuote for the current route
│   ├── DeepLinks.kt           # Opens each app on a route; routeLinkWorks() (Uber/Ola only)
│   ├── GeocodingService.kt    # Google Places (New) autocomplete + place details
│   ├── RecentPlaces.kt        # Remembers picked places (type "p" → "Phoenix")
│   ├── SessionStore.kt        # Persists route to disk (survives process kill)
│   ├── ScrapeKeepAliveService.kt # Foreground service to keep process alive during a read
│   ├── FareEstimator.kt       # DEAD CODE now (fallback estimates; no longer used)
│   └── RaceBooker.kt          # UNUSED/experimental auto-book-all + cancel-losers (risky, see §6)
├── ui/
│   ├── screens/HomeScreen.kt      # Route entry, category chips, Compare button
│   ├── screens/ResultsScreen.kt   # Live price status, suggestions, category groups, Debug popup
│   ├── components/RideCard.kt     # One ride card (name, price, ETA, Book)
│   └── theme/Theme.kt
└── util/SmartComparator.kt    # mergeQuotes, per-category suggestions, savings
```

### The two most important pieces
- **`RideAccessibilityService.kt`** — all the screen reading. If prices aren't captured,
  the bug is almost always here (extraction regex, scrolling, node traversal limits).
- **`VehicleClassifier` in `Models.kt`** — maps each app's vehicle name to a category.

### How price extraction works (so you can debug it)
- `extractVehicleRows()` collects all visible text nodes (with screen coordinates), then:
  - **Combined-line apps (Uber/Rapido):** each ride is one text node like
    `"selected,Uber Go AC,Fare ₹259.94,…"`. It scans for every `name → ₹price` pair in a
    node (name = last classifiable chunk before the price).
  - **Separate-label apps (Ola):** name and price are separate nodes → paired by being on
    the same screen row (y-coordinate).
- A **Debug popup** (top bar → "Debug") shows exactly what was read — including the raw
  screen text for any app that captured nothing. **Use it first when something doesn't read.**

---

## 5. Ride categories & classification

Four categories (calibrated to real app names — see `VehicleClassifier`):

| Category | Example vehicle names |
|---|---|
| **Auto** | Auto, Auto Priority, Auto Lite |
| **Bike** | Bike, Bike Pink, Scooty |
| **Mini / Cab** | Mini, Mini Non AC, Prime Plus, Cab Economy, Cab Priority, AC Cab, Uber Go AC, Go Priority |
| **Sedan / SUV** | Prime Sedan, Prime SUV, Cab Premium, Cab XL, Sedan, SUV, SUV Plus, Go Sedan, Uber XL |

Classification order matters: Auto → Bike → Sedan/SUV → Mini/Cab (so "Go Sedan" is
Sedan/SUV but "Go Priority" is Mini/Cab; "Cab XL" is Sedan/SUV but "Cab Economy" is Mini/Cab).

---

## 6. What works vs what's broken (honest status)

### Works
- ✅ Reading **live prices** from Uber, Ola, Rapido, Namma Yatri (tap-to-read).
- ✅ Correct categorization into the 4 buckets.
- ✅ Scrolling the list to capture Mini/Cab/Sedan options below the fold.
- ✅ Route + captured prices persist across app kill (SessionStore + disk).
- ✅ Best-price / fastest suggestions, savings, grouped display.
- ✅ Smooth scrolling (screen reading moved off the UI thread).
- ✅ Google Places autocomplete, recent places, current-location pickup.

### Broken / limited (fundamental, not just bugs)
- ⚠️ **Not single-touch / not instant.** Each app needs a tap + ~10–20s. Sometimes needs a
  retry or doesn't fetch. This is inherent to screen-scraping — can't be tuned away.
- ⚠️ **Google Pay blocks payments** when RideSmart's accessibility is enabled (it looks like
  a scam app to Google's anti-fraud). User must toggle accessibility OFF to pay. **This is
  the biggest real-world problem.** No money is ever at risk — it's a precautionary block.
- ⚠️ **Rapido / Namma Yatri custom pickup** requires typing both pickup+drop in-app (fragile).
  They're accurate when pickup = current GPS location.
- ⚠️ **Auto-booking / race-booking removed from use** — `RaceBooker` exists but auto-booking
  and auto-cancelling across apps is unreliable AND risky (cancellation fees, account bans).
  "Book" now just opens the chosen app for manual confirm.

---

## 7. Why it can't be "instant + reliable" (the core constraint)

The seamless apps (that compare fares in one screen instantly) get prices through **official
data feeds / business partnerships** with the ride companies. They never open the apps.

- **Uber / Ola / Rapido:** pricing APIs are closed to individual developers.
- **Namma Yatri:** built on **ONDC / Beckn** (India's open mobility network) — the *one*
  legitimate open path. You can register as an ONDC **buyer app** and get live quotes from
  on-network providers. But it requires **ONDC network-participant registration + legal/
  compliance + Beckn protocol integration** (a business/engineering project), and Uber/Ola
  aren't on ONDC, so it wouldn't include them.

**Bottom line:** exact + instant + all-4-apps is not achievable by an individual via
scraping. The realistic good-UX options are: (a) this live-but-fiddly scraper, (b) instant
**estimates** (the RN repo), or (c) a real **ONDC** app (bigger undertaking, open-network
providers only).

---

## 8. Required phone settings (for the live app to work)

The app only works with these ON (especially on iQOO/Vivo/Xiaomi/Oppo):

1. **Accessibility** → Downloaded apps → RideSmart → **ON** (iQOO: App info → ⋮ → "Allow
   restricted settings" first).
2. **Display over other apps** → RideSmart → **ON** (Special app access) — needed for the
   auto snap-back after reading.
3. Notifications + Location → Allow.
4. Battery → allow background power usage; disable "pause app if unused".

**Important:** turn Accessibility **OFF when not comparing** to avoid the Google Pay block.

---

## 9. Build & run

- **Android Studio** (recommended): open the project, let Gradle sync, Run ▶ on a device.
- **Command line** (JDK 21 required — Gradle 8.9 is incompatible with JDK 25):
  ```
  JAVA_HOME=<jdk-21> ./gradlew :app:assembleDebug
  ```
  APK output: `app/build/outputs/apk/debug/app-debug.apk`
- **Config:** `local.properties` needs `MAPS_API_KEY=<your Google Maps/Places key>`
  (gitignored; not in the repo). Enable **Places API (New)** for that key in Google Cloud,
  restricted to the app's package + SHA-1.
- SDK: compileSdk/targetSdk 35, minSdk 26. Kotlin + Compose + kotlin-parcelize.

---

## 10. Git / restore points

- `dc22b30` — current "Option 1" tap-to-read live version.
- `4b512d8` — earlier restore point (hands-off auto-scrape version).
- Revert with `git reset --hard <hash>`.

---

## 11. Suggested next steps (pick a direction)

1. **Polish the live scraper** — reduce retries, tune extraction per app (use the Debug
   popup to see raw text), improve the snap-back. Diminishing returns; the payment conflict
   and slowness remain.
2. **Pivot to instant estimates** — merge the live app's nicer UI with the RN repo's
   estimate engine for a fast, single-touch, no-permission app (approximate prices).
3. **Build on ONDC** — the only path to the real seamless product (register as a buyer app,
   integrate Beckn). Open-network providers only (no Uber/Ola).
4. **Remove dead code** — `FareEstimator.kt` (unused) and `RaceBooker.kt` (risky/unused).

---

## 12. Debugging tips

- **Nothing captured for an app?** Open the **Debug** popup (Results screen, top bar). It
  shows the raw screen text — compare it to the parser in `extractVehicleRows()`.
- **App frozen / stuck in a ride app?** It's the OEM freezing the process — a user tap to
  return to RideSmart recovers it; captured prices persist.
- **Crash on launch?** Likely the price-restore path; it's wrapped in `runCatching`, but
  check `SessionStore` / `RideAccessibilityService.restorePrices`.
- **Payments blocked (Google Pay)?** Expected when Accessibility is on. Turn it off; restart;
  Play Protect scan. Not a bug.
