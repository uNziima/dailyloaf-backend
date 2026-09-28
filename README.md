# Daily Loaf Delivery: Backend and Delivery OS

The ordering, operations and delivery system for **Daily Loaf Delivery (Pty) Ltd**, a hyperlocal bread delivery startup I co-founded in Madadeni, Newcastle, KwaZulu-Natal.

Customers pre-order bread over WhatsApp or a Google Form. Orders, customers, stock and finances live in Google Sheets. On delivery mornings the driver works from a phone app, the Delivery OS, that reads and updates the same data.

> **Status: paused before launch.** The full system was built and deployed to production. The business was paused before its first delivery because of funding and delivery-transport constraints. The code is shared here as a portfolio piece.

## What it does

- **Two-track ordering.** New customers order through a Google Form. Returning customers simply message the WhatsApp number in plain language, and the message parser turns that into an order.
- **Order reference lookup.** Every order gets a 7-digit reference that customers can quote to check their order.
- **Payment choice per order.** PayShap or cash on delivery.
- **"Please call me" callbacks.** A customer can request a call, which alerts both founders and logs the request.
- **GPS location capture.** Customers can share their WhatsApp location, which is geocoded and saved against their record for the driver.
- **Evening reminders.** A scheduler sends an 8pm reminder before each delivery day.
- **Unrecognised-message handling.** Anything the parser cannot understand gets a yes/no prompt and an options menu instead of silence.
- **Broadcasts.** Operational messages to all customers, tagged as no-reply.
- **Waitlist.** Receives sign-ups from the landing page.
- **Delivery OS.** A mobile web app (PWA) for the driver: the day's deliveries, mark delivered or not delivered, protected by an access key.

## Architecture

```
 Customer (WhatsApp)          Landing page            Driver's phone
        │                          │                        │
        ▼                          ▼                        ▼
 ┌──────────────────────────────────────────────────────────────────┐
 │  DailyLoafServer  (Java, com.sun.net.httpserver)                   │
 │  /webhook  /waitlist  /deliveries  /deliver  /not-delivered        │
 │  /pay  /send  /broadcast  /config  /health  /delivery-os           │
 └───────────────┬───────────────────────────────┬──────────────────┘
                 │                               │
                 ▼                               ▼
      WhatsApp Cloud API                 Google Sheets API
      (WhatsAppClient)            (SheetsClient + GoogleAuthClient,
                                    GeocodingClient for locations)
                 ▲
                 │
       ReminderScheduler (8pm reminders)
```

## Engineering decisions

- **Plain Java, no frameworks.** The HTTP server, JSON handling, Google service-account authentication (signed JWT exchanged for an access token, cached until near expiry) and the API clients are all written with the standard library. This kept the deployment small and meant I understood every layer.
- **Google Sheets as the database.** The founders could read, correct and report on the data without any extra tooling, which suited a two-person pre-launch business. The trade-off was accepted knowingly, with a relational database planned for scale.
- **Configuration never in the repository.** Every token and key is read from `config.properties` locally or from environment variables in production. Only a placeholder example file is committed.
- **Validation before anything is written.** Incoming orders pass through `OrderValidator` before they reach the sheet.
- **Containerised deployment.** A small Dockerfile compiles and packages the app, and it ran on Railway.

## Project structure

```
src/com/dailyloaf/
├── Main.java               Starts the scheduler and the HTTP server
├── config/                 Loads configuration from file or environment
├── handlers/               One class per HTTP endpoint
├── model/                  Customer, IncomingMessage, OrderStatus
├── scheduler/              Evening reminder scheduler
├── server/                 HTTP server and static file serving
├── sheets/                 Google Sheets, Google auth and geocoding clients
├── util/                   Lightweight JSON helper
├── validation/             Order validation rules
└── whatsapp/               Message parsing and the WhatsApp client
delivery-os/                Driver PWA (HTML, CSS, vanilla JavaScript)
```

About 4,100 lines of Java across 23 classes, plus about 1,300 lines for the Delivery OS.

## Tech stack

Java 21 · WhatsApp Cloud API · Google Sheets API · Google Geocoding API · HTML, CSS and vanilla JavaScript · Docker · Railway

## Running it locally

You will need Java 21, a WhatsApp Cloud API test number and a Google service account with access to a spreadsheet.

```
cp config.properties.example config.properties   # then fill in your own values
find src -name "*.java" > sources.txt
javac -d out @sources.txt
java -cp out com.dailyloaf.Main
```

Then check `http://localhost:8080/health`. Or with Docker:

```
docker build -t dailyloaf .
docker run -p 8080:8080 --env-file .env dailyloaf
```

## What I would do differently

- Move from Google Sheets to PostgreSQL once order volume made concurrent edits a risk
- Add automated tests around the message parser and order validation, the two places most likely to break quietly
- Replace hard-coded message text with templates so copy changes do not need a redeploy

## Author

**Ulikhaya Mazibuko**, co-founder and technology and operations lead.
[ulikhayamazibuko.com](https://ulikhayamazibuko.com) · [LinkedIn](https://www.linkedin.com/in/ulikhaya-mazibuko-43623b261)
