# FinTracks – Android (Kotlin + Jetpack Compose)

Native Android app. Backend: **Supabase free tier** (Auth + Postgres + RLS).
Schema: `supabase/migrations/20261119000000_init.sql`.

## Features (v1.0)
- Email/password sign-up and login, plus Google sign-in (needs setup below)
- Home dashboard: balance, this month's income and expense, recent transactions
- Transactions: add, filter, delete (income / expense, categories, payment mode, date)
- **বাকি (credit ledger)**: shops, items bought on credit (item, quantity, unit price, total, purchase date, due date), payments, running balance, status (বাকি আছে / মেয়াদোত্তীর্ণ / পরিশোধিত), manual entry
- SMS suggestions: receives bKash / Nagad / Rocket / bank SMS and asks for confirmation (it never saves automatically)
- Settings: name, dark/light theme, accent colour (sky / emerald / rose / amber), privacy mode, sign out

## Build
Requirements: JDK 17, Gradle 8.10.2 (or Android Studio, which bundles it).

```bash
cd android
gradle wrapper --gradle-version 8.10.2   # once, creates ./gradlew
./gradlew :app:assembleDebug
```
The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
CI (`.github/workflows/android.yml`) builds the same APK on every push that touches `android/`.

## Google sign-in setup (optional)
1. In Google Cloud Console create an OAuth **Web client** ID.
2. In Supabase: Authentication → Providers → Google, enable it and enter the client ID and secret.
3. Put the Web client ID in `~/.gradle/gradle.properties`:
   `googleWebClientId=XXXX.apps.googleusercontent.com`

Without step 3 the Google button shows a notice and email login still works.

## Notes
- The app uses only the **publishable** key, which is safe to ship. Never put the `service_role` key in the app.
- Data is protected by Row Level Security: each user can only read and write their own rows.
- The session is stored in SharedPreferences. For stricter storage, move it to EncryptedSharedPreferences later.
- Not in v1.0 yet (planned): PIN/biometric lock, accounts and transfers, recurring auto-entries, budgets, goals, debts, loans, investments, reports, offline cache, and the other items from the roadmap.
