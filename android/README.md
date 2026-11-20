# FinTrack – Android (Kotlin + Jetpack Compose)

Native Android app, package `bd.shohagvai.fintrack`. Backend: **Supabase free tier** (Auth + Postgres + RLS + one Edge Function).
Schema: `supabase/migrations/`. Edge Functions: `ai-chat` (Gemini key kept as a Supabase secret) and `delete-account`.

## Features
- **Accounts:** email/password (with email confirmation) and Google sign-in.
- **Home:** balance, this month's income and expense, recent transactions.
- **Transactions:** manual add/edit/delete (income, expense, transfer), categories, payment mode, mood, date.
  - Filter by period: today, last 7 days, this month, this year, all, or a custom range.
  - Export the filtered list as Excel, PDF (Bengali) or CSV.
  - Share any single transaction as a Bengali PDF voucher with a QR code.
- **বাকি (credit ledger):** shops, items (quantity, unit price, total, purchase and due dates), partial payments, running balance. Separate from **ধার (debts)**.
- **Accounts and transfers, budgets, goals, investments, subscriptions, debt payoff planner.**
- **Auto-recurring entries:** recurring transactions are copied into each new month by a daily background job (WorkManager).
- **Bill reminders:** notifications for due bills, debts and credit (once per item per day).
- **Shared groups:** bill split, shopping list, mess.
- **Tools:** Zakat, tax (Bangladesh slabs), currency, loan/EMI, reports (daily, weekly, monthly, yearly, custom) with export.
- **AI সহকারী:** chat over your finances. Uses the `ai-chat` Edge Function when online, and falls back to local answers.
- **SMS import:** bKash / Nagad / Rocket / bank SMS become suggestions that you confirm. Nothing is saved automatically.
- **Security:** app lock with PIN plus fingerprint or face (device credential on Android 11+), auto-lock timeout, and lock-out after 5 wrong PINs (30 s, then 2 min, then 10 min). Changing the PIN requires the old PIN. The PIN is stored only as a salted hash.
- **Account deletion:** Settings → "অ্যাকাউন্ট স্থায়ীভাবে মুছুন". Requires the password and typing `DELETE`. The server deletes the auth user, and every user row is removed by `ON DELETE CASCADE`. Function: `supabase/functions/delete-account`.
- **Calculator:** safe in-app evaluator (`+ − × ÷ % ( )`), opened from the transaction form. It does not use `eval`.
- **Insights:** 30/60/90-day cash-flow forecast, unusual-spending alerts (more than 40% above the 3-month average), net worth, and a 30-day spending heatmap. These are local calculations.
- **Tags:** comma-separated tags on transactions, searchable.
- **Backup:** JSON file of every user table, shared from Settings. The PIN and session are never included.
- **Home-screen widget:** balance widget.
- **Settings:** profile, dark/light theme, accent colour, currency, privacy mode, security, export, sign out.

## Build
Requirements: JDK 17. Gradle 8.10.2 is provided by the wrapper (`./gradlew`).

```bash
cd android
./gradlew :app:assembleDebug
```
The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
CI (`.github/workflows/android.yml`) builds the APK on every push that touches `android/`.

## Google sign-in setup
1. In Google Cloud Console, create an OAuth **Web client** ID, and an **Android** client with package `bd.shohagvai.fintrack` and your signing key's SHA-1.
2. In Supabase: Authentication → Providers → Google, enable it, and enter the Web client ID and secret.
3. The Web client ID is public and is set in `android/gradle.properties` (`googleWebClientId`). Do not commit the client secret.

## Email confirmation
Confirmation links go to the GitHub Pages page `https://shohagvaii.github.io/FinTracks/` (source: `site/`, deployed by `.github/workflows/pages.yml`). Supabase's custom SMTP is used to send email.

## Typography
Hind Siliguri (Light, Regular, Medium, SemiBold, Bold) is bundled in `app/src/main/assets/fonts`, the same font as the web app. The PDF export uses Noto Sans Bengali.

## Notes
- The app uses only the **publishable** key, which is safe to ship. Never put the `service_role` key or the Gemini key in the app.
- Data is protected by Row Level Security: each user can read and write only their own rows.
- The session is stored in SharedPreferences.
