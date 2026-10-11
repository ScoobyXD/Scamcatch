1. Publish only the public directory: index.html, styles.css, app.js, favicon.svg, and fonts/. The site needs no build step or package install.
2. Serve index.html for the root path. Serve app.js as text/javascript, styles.css as text/css, fonts/*.woff2 as font/woff2, and favicon.svg as image/svg+xml.
3. Fonts are self-hosted Newsreader and Hanken Grotesk variable fonts from @fontsource-variable (SIL Open Font License). Do not load fonts from Google.
4. The page has six snap-scrolling stages and a right-side section index: Live demo, How it works, The voice, Where it stands, Why it matters, Try it.
5. The live demo plays a romance-crypto chat, a ScamCatch warning, and a "Tap here to talk" voice screen, then replays after 16 seconds without interaction. Tapping opens the call screen.
6. Live voice connects through window.ScamCatchLive.start(context, { onStatus, onCaption }), which returns a session with end(). Until that exists, the call screen says live voice isn't connected.
7. The live voice context is the chat shown in the demo. ScamCatchDemo.context() returns it.
8. The chat looks like a generic messaging app. Do not use Facebook, Messenger, WhatsApp, or Meta names, logos, or exact layouts.
9. The $7.9 billion investment-scam figure for 2025 comes from the FTC's March 2026 testimony, linked on the page.
10. The website has no contact form, email address, analytics, or advertising scripts.
