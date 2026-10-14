/* ScamCatch homepage: demo timeline, scam scenarios, call screen, section index. */
(() => {
  const card = document.querySelector('.card-demo');
  if (!card) return;

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const PHASES = ['trust', 'ask', 'warn', 'voice'];
  const thread = card.querySelector('.thread');
  const typing = card.querySelector('.typing');
  let stepped = [...card.querySelectorAll('.msg[data-step]')];
  let flagged = card.querySelector('.msg.flagged');
  const storyLines = [...card.querySelectorAll('.story-line')];
  const trackerItems = [...card.querySelectorAll('.tracker li')];
  const callStatus = card.querySelector('.call-status');
  const caption = card.querySelector('.caption');
  const phone = card.querySelector('.phone');
  const chat = card.querySelector('.chat');
  const chatHead = card.querySelector('.chat-head');
  const composer = card.querySelector('.composer');
  const alertText = card.querySelector('.alert > span');
  const sheetQuote = card.querySelector('.sheet-quote');
  const scamButtons = [...document.querySelectorAll('[data-action="scenario"]')];

  /* ---- Scam scenarios ----
     thread rows: ['stamp', day, time] · ['them', text] · ['me', text] · ['read', time] · ['photo', what the photo shows]
     steps: the messages that arrive one by one before the flagged one
     flagged: the message ScamCatch catches
     avatar: a letter for a saved contact, 'photo' for a contact photo, null for an unknown number
     app: the chat look, 'imessage', 'whatsapp' (dark) or 'messenger' (dark). Styled after those apps, never their names or logos.
     pace: optional; below 1 plays the trust and ask parts faster (romance, the first scam people see, uses it) */
  const SCENARIOS = {
    romance: {
      key: 'romance_crypto_investment',
      app: 'whatsapp', contact: 'Simon', avatar: 'S', first: 'Trust', pace: 0.6,
      trust: ['It starts with someone who feels real.', 'Romance scammers spend weeks earning trust before money ever comes up.'],
      ask: ['Then comes the ask.', 'Often it’s crypto, through a “friend” who’s doing really well. And they’ll match whatever you put in.'],
      thread: [
        ['stamp', 'Tuesday', '9:20 PM'],
        ['them', 'I showed my mom your picture. She said you have kind eyes 😊'],
        ['me', 'Stop 🙈 that’s so sweet'],
        ['them', 'I mean it. I’ve never met anyone like you'],
        ['stamp', 'Yesterday', '11:42 PM'],
        ['them', 'Goodnight beautiful. Talking to you is the best part of my day'],
        ['me', 'Same here 🥰'],
        ['read', '11:43 PM'],
        ['stamp', 'Today', '8:13 AM'],
      ],
      steps: ['Morning ☀️ I couldn’t stop thinking about you', 'My friend’s asking us to invest in his Monero fund. He’s made a fortune with it 🚀'],
      flagged: 'I’ll put in $40,000 if you put in $40,000. Let’s build our future together ❤️',
      alert: 'Simon wants you to match his $40,000 in a Monero investment. Someone you met online asking you to invest is a classic romance scam.',
    },
    'bank-favor': {
      key: 'bank_login_transfer_favor',
      app: 'messenger', contact: 'Mark', avatar: 'M', first: 'Trust',
      trust: ['It starts with a friend in a bind.', 'They’re far from home, stressed, and you’re the one person they say they trust.'],
      ask: ['Then they hand you the keys.', 'Their bank login, a transfer to make, a deadline. It feels like helping a friend.'],
      thread: [
        ['stamp', 'Sunday', '6:05 PM'],
        ['them', 'Made it to the job site. Signal out here is terrible 😅'],
        ['me', 'Glad you got there safe! Call when you can'],
        ['stamp', 'Yesterday', '9:48 PM'],
        ['them', 'You always make my day better. I’m so lucky to have you in my life 🙏'],
        ['me', 'Aw, that means a lot 🤗'],
        ['read', '9:49 PM'],
        ['stamp', 'Today', '8:13 AM'],
      ],
      steps: ['I need a huge favor. My bank froze my transfers while I’m overseas 😩'],
      flagged: 'I’m giving you my bank login. Could you log in and transfer $100,000 to my supplier? You’re an angel 🙏',
      alert: 'Mark wants you to log into his bank and move $100,000. Moving money for someone else can make you part of a scam.',
    },
    'false-charge': {
      key: 'false_charge_fraud_department_safe_account',
      app: 'imessage', contact: '+1 (888) 555-0193', avatar: null, first: 'The scare',
      trust: ['It starts with a charge you didn’t make.', 'A text says your account was just charged, then a “fraud department” steps in to help.'],
      ask: ['Then they tell you to empty it.', 'Move everything to a “protected” account, today. And don’t tell your bank why.'],
      thread: [
        ['stamp', 'Today', '7:52 AM'],
        ['them', 'FRAUD ALERT: A $1,249.99 charge was just made on your account. Not you? Reply NO to reach our fraud department.'],
        ['me', 'NO. I didn’t make that!'],
        ['them', 'This is Daniel in the Fraud Department. Someone has access to your accounts, and more charges are pending.'],
        ['me', 'Oh no. What do I do?'],
      ],
      steps: [
        'Stay calm, we’re taking safety precautions now. We opened a protected account in your name.',
        'Move your full balance there today. It comes back to you once the fraud is stopped.',
        'Don’t tell the bank why. Someone at your branch is part of this. If they ask, say it’s for a home renovation.',
      ],
      flagged: 'Withdraw everything today and send it to the protected account. Text me the moment it’s done.',
      alert: 'Daniel wants you to empty your account and lie to your bank about why. Real fraud departments never ask you to move your money or keep it from your bank.',
    },
    'tech-support': {
      key: 'tech_support_remote_access',
      app: 'imessage', contact: '+1 (844) 555-0127', avatar: null, first: 'The scare',
      trust: ['It starts with a warning on your screen.', 'A pop-up says your computer is infected and gives you a number to call for help.'],
      ask: ['Then they ask to get inside.', 'First your computer, then your bank account. All in the name of fixing it.'],
      thread: [
        ['stamp', 'Today', '7:40 AM'],
        ['them', 'This is Kevin from PC Security Support. Thanks for calling about the virus warning on your computer.'],
        ['me', 'Yes, the screen told me to call this number'],
        ['them', 'Our scan shows hackers on your network. They can see your bank accounts.'],
        ['me', 'Oh no. Please help me'],
      ],
      steps: ['Don’t worry, I’m a certified technician. I just need to connect to your computer.'],
      flagged: 'Install the remote access app, then log into your online banking so I can secure it.',
      alert: 'Kevin wants control of your computer and your bank login. Real tech support never asks for either.',
    },
    irs: {
      key: 'irs_back_taxes_impersonation',
      app: 'imessage', contact: '+1 (202) 555-0163', avatar: null, first: 'The threat',
      trust: ['It starts with a threat.', 'Someone claiming to be the IRS says you owe back taxes and a warrant is out.'],
      ask: ['Then they tell you how to pay.', 'Gift cards, a wire, or crypto. Right now, before you can check.'],
      thread: [
        ['stamp', 'Today', '7:58 AM'],
        ['them', 'IRS FINAL NOTICE: You owe $3,412 in back taxes. A warrant has been issued for your arrest.'],
        ['me', 'What? I filed my taxes'],
        ['them', 'Our records show errors in your 2023 return. Officers are scheduled to visit your home today.'],
        ['me', 'Please, what can I do?'],
      ],
      steps: ['You can settle now and the warrant will be cancelled.'],
      flagged: 'Buy $3,412 in gift cards and text me the card numbers within 2 hours.',
      alert: 'The IRS doesn’t text threats or ask for gift cards. This is someone pretending to be the IRS.',
    },
    'jury-duty': {
      key: 'jury_duty_arrest_warrant',
      app: 'imessage', contact: '+1 (415) 555-0148', avatar: null, first: 'The threat',
      trust: ['It starts with an officer’s name.', 'Someone from “the sheriff’s office” says you missed jury duty and will be arrested.'],
      ask: ['Then comes the bond.', 'Pay right now, by wire or gift card, and the warrant goes away.'],
      thread: [
        ['stamp', 'Today', '7:46 AM'],
        ['them', 'This is Deputy Ray Collins, County Sheriff’s Office. You missed your jury duty date on the 14th.'],
        ['me', 'I never got a summons'],
        ['them', 'A bench warrant was issued. You will be arrested unless this is resolved today.'],
        ['me', 'Please, I didn’t know'],
      ],
      steps: ['I can hold the warrant if you pay the bond now.'],
      flagged: 'Wire the $10,000 bond to the court clerk now, or officers will come to your home.',
      alert: 'Courts don’t text you to demand payment or threaten arrest. This is a jury duty scam.',
    },
    lottery: {
      key: 'lottery_prize_advance_fee',
      app: 'imessage', contact: '+1 (646) 555-0112', avatar: null, first: 'The prize',
      trust: ['It starts with good news.', 'You’ve won millions and a new car, from a contest you don’t remember entering.'],
      ask: ['Then there’s a fee.', 'Taxes, delivery, “processing.” A little money now to unlock a lot later.'],
      thread: [
        ['stamp', 'Today', '7:35 AM'],
        ['them', 'CONGRATULATIONS! This is Publishers Clearing House. You have won $2,500,000 and a new car! 🎉'],
        ['me', 'Is this real??'],
        ['them', 'Yes! Our prize team will deliver your check this Friday.'],
        ['me', 'Oh my goodness. What do I need to do?'],
      ],
      steps: ['First we just need to cover the taxes and delivery fee on your winnings.'],
      flagged: 'Send $850 in gift cards today to release your prize. Don’t tell anyone until it arrives!',
      alert: 'Real prizes never cost money to collect. Paying first to get your winnings is a prize scam.',
    },
    celebrity: {
      key: 'celebrity_impersonation',
      app: 'messenger', contact: 'Brad Pitt', avatar: 'photo', first: 'Trust',
      trust: ['It starts with a star who noticed you.', 'A famous name messages you privately and asks you to keep it a secret.'],
      ask: ['Then comes the ask.', 'Frozen accounts, medical bills, a plane ticket. A star who suddenly needs your money.'],
      thread: [
        ['stamp', 'Sunday', '10:15 PM'],
        ['them', 'I don’t usually message fans. But your comment really touched me ❤️'],
        ['me', 'Wait… is this really you??'],
        ['photo', 'a selfie he says is him'],
        ['them', 'Only for you. Please keep this between us 🤫'],
        ['me', 'I promise 🥹'],
        ['read', '10:21 PM'],
        ['stamp', 'Today', '8:13 AM'],
      ],
      steps: ['I want to fly you out to meet me, but my accounts are frozen while my lawyers sort things out 😔'],
      flagged: 'Could you send $20,000 for my medical bills? I’ll pay you back the moment it clears.',
      alert: 'Celebrities don’t message fans asking for money. This is someone pretending to be Brad Pitt.',
    },
  };
  const UNKNOWN_AVATAR = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="9.6" r="4.3"/><path d="M3.6 24c.4-4.9 3.9-8 8.4-8s8 3.1 8.4 8Z"/></svg>';
  let photoCount = 0;
  function photoArt(viewBox) {
    photoCount += 1;
    const sky = `sky${photoCount}`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><defs>`
      + `<linearGradient id="${sky}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd8a3"/><stop offset=".42" stop-color="#f39c6c"/><stop offset=".62" stop-color="#d4697a"/><stop offset="1" stop-color="#4c4a85"/></linearGradient></defs>`
      + `<rect width="80" height="100" fill="url(#${sky})"/>`
      + '<circle cx="60" cy="55" r="10" fill="#fff1d0" opacity=".85"/>'
      + '<rect y="62" width="80" height="38" fill="#3e4c88"/><path d="M44 66h30M50 70h22M40 75h28" stroke="#ffd7a6" stroke-width="1" opacity=".55"/>'
      + '<path fill="#211d33" d="M6 100c1-13 9-21 20-24l4-3v-5c-4-3-6-8-6-13 0-9 6-15 14-15s14 6 14 15c0 5-2 10-6 13v5l4 3c11 3 19 11 20 24Z"/>'
      + '<path fill="#211d33" d="M23 44c0-8 6-13 15-13s15 5 15 13l9 1c1 2-1 3-3 3H23Z"/>'
      + '</svg>';
  }
  /* ---- Chat looks: header and message bar for each app style ---- */
  const svg = (paths, extra = '') => `<svg viewBox="0 0 24 24" aria-hidden="true"${extra}>${paths}</svg>`;
  const LINE = 'fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';
  const ICON = {
    back: '<svg class="back" viewBox="0 0 12 20" aria-hidden="true"><path d="M10 2 2 10l8 8" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    video: svg(`<path ${LINE} d="M15 8.5V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-1.5l5 3.5V5Z"/>`),
    phone: svg(`<path ${LINE} d="M5.4 3.6h3l1.5 4-2.1 1.5a11.5 11.5 0 0 0 5.3 5.3l1.5-2.1 4 1.5v3a2 2 0 0 1-2.1 2A15.6 15.6 0 0 1 3.3 5.7a2 2 0 0 1 2.1-2.1Z"/>`),
    mic: svg('<path fill="currentColor" d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-2.1a7 7 0 0 0 6-6.9Z"/>'),
    plus: svg('<path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>'),
    emoji: svg(`<circle ${LINE} cx="12" cy="12" r="8.6"/><path ${LINE} d="M8.6 14.2a4.2 4.2 0 0 0 6.8 0"/><circle fill="currentColor" cx="9.2" cy="9.8" r="1.1"/><circle fill="currentColor" cx="14.8" cy="9.8" r="1.1"/>`),
    clip: svg(`<path ${LINE} d="m16.6 8.2-7.4 7.4a2 2 0 0 1-2.8-2.8l7.9-7.9a3.6 3.6 0 0 1 5.1 5.1l-8.3 8.3a5.2 5.2 0 0 1-7.3-7.3L11 4"/>`),
    camera: svg(`<path ${LINE} d="M4 8.2h3.1L8.6 6h6.8l1.5 2.2H20V19H4Z"/><circle ${LINE} cx="12" cy="13.4" r="3.2"/>`),
    image: svg(`<rect ${LINE} x="3.8" y="5" width="16.4" height="14" rx="2.4"/><circle fill="currentColor" cx="9" cy="10" r="1.6"/><path ${LINE} d="m4.5 17.2 4.8-4.4 3.7 3.2 2.3-2 4.3 3.6"/>`),
    plusCircle: svg('<circle fill="currentColor" cx="12" cy="12" r="9.6"/><path d="M12 7.6v8.8M7.6 12h8.8" stroke="#000" stroke-width="2.1" stroke-linecap="round"/>'),
    like: svg('<path fill="currentColor" d="M3.5 10h3.2v10H3.5Zm5 10h7.8a2.2 2.2 0 0 0 2.1-1.7l1.5-6.3A2.2 2.2 0 0 0 17.8 9.3h-4.3l.6-3.2A2 2 0 0 0 10.3 5L8.5 10Z"/>', ' class="like"'),
    ticks: '<svg viewBox="0 0 18 11" aria-hidden="true"><path d="M1.2 6.2 4 9l6.3-7.4M7.6 8.8 8.4 9.6 15 1.6" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  };
  const LOOKS = {
    imessage: {
      head: `${ICON.back}<span class="who"><span class="avatar"></span><b><span class="name"></span> <i>›</i></b></span>${svg(`<path ${LINE} stroke-width="1.7" d="M15 8.5V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-1.5l5 3.5V5Z"/>`, ' class="head-icon"')}`,
      bar: `<span class="plus">${ICON.plus}</span><span class="field"><span class="placeholder">iMessage</span>${ICON.mic}</span>`,
    },
    whatsapp: {
      head: `${ICON.back}<span class="avatar"></span><span class="who"><b class="name"></b><small>online</small></span><span class="head-icons">${ICON.video}${ICON.phone}</span>`,
      bar: `<span class="field">${ICON.emoji}<span class="placeholder">Message</span>${ICON.clip}${ICON.camera}</span><span class="mic-btn">${ICON.mic}</span>`,
    },
    messenger: {
      head: `${ICON.back}<span class="avatar-wrap"><span class="avatar"></span><i class="active-dot"></i></span><span class="who"><b class="name"></b><small>Active now</small></span><span class="head-icons">${ICON.phone}${ICON.video}</span>`,
      bar: `<span class="m-icons">${ICON.plusCircle}${ICON.camera}${ICON.image}${ICON.mic}</span><span class="field"><span class="placeholder">Aa</span>${ICON.emoji}</span>${ICON.like}`,
    },
  };

  // "9:20 PM" plus n minutes
  function addMinutes(time, n) {
    const [, h, m, half] = time.match(/(\d+):(\d+) (AM|PM)/);
    const total = ((((+h % 12) + (half === 'PM' ? 12 : 0)) * 60 + +m + n) % 1440 + 1440) % 1440;
    const hh = Math.floor(total / 60);
    return `${hh % 12 || 12}:${String(total % 60).padStart(2, '0')} ${hh >= 12 ? 'PM' : 'AM'}`;
  }

  let current = 'romance';

  const LOOP_HOLD = 16000;   // how long the "tap here" screen waits before replaying
  let stepIndex = 0;
  let timer = null;
  let switchTimer = null;
  let paused = false;
  let calling = false;
  let lastInteraction = 0;
  let liveSession = null;

  const show = (el) => el.classList.add('is-shown');
  const hide = (el) => el.classList.remove('is-shown');

  function setPhase(phase, durationMs) {
    card.dataset.phase = phase;
    const current = PHASES.indexOf(phase);
    storyLines.forEach((line) => line.classList.toggle('is-active', line.dataset.for === phase));
    trackerItems.forEach((item) => {
      const index = PHASES.indexOf(item.dataset.for);
      item.classList.remove('is-active');
      item.classList.toggle('is-done', index < current || (index === current && !durationMs));
      if (index === current && durationMs) {
        item.style.setProperty('--dur', `${durationMs}ms`);
        void item.offsetWidth; // restart the fill
        item.classList.add('is-active');
      }
    });
  }

  /* ---- Put a scenario on the phone and in the copy ---- */
  function textRow(className, bold, rest) {
    const row = document.createElement('p');
    row.className = className;
    const b = document.createElement('b');
    b.textContent = bold;
    row.append(b, ` ${rest}`);
    return row;
  }

  function bubble(side, text) {
    const row = document.createElement('p');
    row.className = `msg ${side}`;
    row.textContent = text;
    return row;
  }

  function stampRow(app, day, time) {
    if (app === 'whatsapp') {
      const row = document.createElement('p');
      row.className = 'stamp';
      row.textContent = day;
      return row;
    }
    if (app === 'messenger') {
      const row = document.createElement('p');
      row.className = 'stamp';
      const when = { Today: '', Yesterday: 'Yesterday at ' }[day] ?? `${day.slice(0, 3)} at `;
      row.textContent = `${when}${time}`;
      return row;
    }
    return textRow('stamp', day, time);
  }

  // WhatsApp shows the time (and read ticks) inside each bubble.
  function withTime(row, time, outgoing) {
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = time;
    if (outgoing) meta.insertAdjacentHTML('beforeend', ICON.ticks);
    row.append(meta);
    return row;
  }

  function renderScenario(id) {
    const s = SCENARIOS[id];
    current = id;

    const app = s.app;
    let stampTime = null;
    let minute = 0;
    const say = (side, text) => {
      const row = bubble(side, text);
      if (app === 'whatsapp' && stampTime) withTime(row, addMinutes(stampTime, minute), side === 'me');
      minute += 1;
      return row;
    };
    const rows = [];
    s.thread.forEach(([kind, a, b]) => {
      if (kind === 'stamp') { stampTime = b; minute = 0; rows.push(stampRow(app, a, b)); return; }
      if (kind === 'read') {
        if (app === 'imessage') rows.push(textRow('receipt', 'Read', a));
        if (app === 'messenger') { const seen = document.createElement('p'); seen.className = 'receipt seen'; rows.push(seen); }
        return;
      }
      if (kind === 'photo') {
        const photo = bubble('them photo', '');
        photo.dataset.text = `[Photo: ${a}]`;
        photo.innerHTML = photoArt('0 0 80 100');
        minute += 1;
        rows.push(photo);
        return;
      }
      rows.push(say(kind, a));
    });
    const incoming = s.steps.map((text) => say('them', text));
    const caught = say('them flagged', s.flagged);
    stepped = [...incoming, caught];
    stepped.forEach((m, i) => { m.dataset.step = String(i + 1); });
    flagged = caught;
    // Mark the first bubble of each run from the same person (WhatsApp draws its tail there).
    let side = null;
    [...rows, ...stepped].forEach((row) => {
      const now = row.classList.contains('me') ? 'me' : row.classList.contains('them') ? 'them' : null;
      if (now && now !== side) row.classList.add('first');
      side = now;
    });
    thread.replaceChildren(...rows, ...stepped, typing);
    buildScript();

    chat.dataset.app = app;
    card.dataset.app = app;
    chatHead.innerHTML = LOOKS[app].head;
    composer.innerHTML = LOOKS[app].bar;
    const avatar = chatHead.querySelector('.avatar');
    if (s.avatar === 'photo') avatar.innerHTML = photoArt('6 22 68 68');
    else if (s.avatar) avatar.textContent = s.avatar;
    else avatar.innerHTML = UNKNOWN_AVATAR;
    chatHead.querySelector('.name').textContent = s.contact;
    // Messenger shows the contact's picture beside their messages.
    chat.style.setProperty('--av-letter', s.avatar && s.avatar !== 'photo' ? JSON.stringify(s.avatar) : '""');
    if (s.avatar === 'photo') chat.style.setProperty('--av-img', `url("data:image/svg+xml,${encodeURIComponent(photoArt('6 22 68 68'))}")`);
    else chat.style.removeProperty('--av-img');

    alertText.textContent = s.alert;
    const quoteName = document.createElement('span');
    quoteName.textContent = s.contact;
    sheetQuote.replaceChildren(quoteName, s.flagged);

    [['trust', s.trust], ['ask', s.ask]].forEach(([phase, [head, sub]]) => {
      const line = storyLines.find((l) => l.dataset.for === phase);
      line.querySelector('.story-head').textContent = head;
      line.querySelector('.story-sub').textContent = sub;
    });
    trackerItems[0].lastChild.nodeValue = s.first;

    scamButtons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.scenario === id)));
  }

  function selectScenario(id) {
    if (!SCENARIOS[id]) return;
    clearTimeout(switchTimer);
    if (calling) endCall();
    clearTimeout(timer);
    lastInteraction = 0;
    scamButtons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.scenario === id)));
    if (reduceMotion) { renderScenario(id); showFinalState(); return; }
    // Fade the old chat and story out, then start the new one from the top.
    card.classList.add('is-switching');
    storyLines.forEach((line) => line.classList.remove('is-active'));
    switchTimer = setTimeout(() => {
      renderScenario(id);
      card.classList.remove('is-switching');
      play(0);
    }, 300);
  }

  function reset() {
    stepped.forEach(hide);
    hide(typing);
    flagged.classList.remove('is-scanned');
    card.classList.remove('has-watch', 'has-flag', 'has-ready', 'is-calling');
    callStatus.textContent = '';
    caption.textContent = 'ScamCatch will start talking.';
  }

  // The playback, rebuilt for each scenario: [wait before this step in ms, what happens].
  // Each new message gets typing dots, then stays up long enough to read before the next one starts.
  const TYPING = 1000;
  const readFor = (text) => Math.max(1100, Math.min(3600, 500 + text.length * 30));
  let script = [];
  function buildScript() {
    const pace = SCENARIOS[current].pace || 1;   // shortens the trust and ask parts; the warning and voice keep their timing
    const rows = [
      [0, () => { reset(); setPhase('trust', trustFor); }],
      [300 * pace, () => card.classList.add('has-watch')],
      [300 * pace, () => show(typing)],
    ];
    let askAt = 0;
    stepped.forEach((m, i) => {
      rows.push([TYPING * pace, () => { hide(typing); show(m); }]);
      if (m === flagged) return;
      if (i === 0) askAt = rows.length;
      rows.push([readFor(msgText(m)) * pace, () => { show(typing); if (i === 0) setPhase('ask', askFor); }]);
    });
    const flagAt = rows.length;
    rows.push(
      [900 * pace, () => { flagged.classList.add('is-scanned'); card.classList.add('has-flag'); }],
      [1200, () => {
        setPhase('warn', 1700);
        phone.classList.remove('buzz');
        void phone.offsetWidth;
        phone.classList.add('buzz');
      }],
      [1700, () => { setPhase('voice'); card.classList.add('has-ready'); }],
      [LOOP_HOLD, () => {
        const idle = Date.now() - lastInteraction;
        if (calling) return;
        if (idle < 6000) { schedule(stepIndex - 1, 6000); return; }
        play(0);
      }],
    );
    const sum = (from, to) => rows.slice(from, to).reduce((total, [wait]) => total + wait, 0);
    const trustFor = sum(1, askAt + 1);            // the first bar fills until the ask begins
    const askFor = sum(askAt + 1, flagAt + 2);     // the second fills until the warning
    script = rows;
  }

  const delayOf = (index) => script[index][0];

  function schedule(index, wait) {
    clearTimeout(timer);
    stepIndex = index;
    timer = setTimeout(() => {
      if (paused) return;
      const step = script[stepIndex];
      stepIndex += 1;
      step[1]();
      if (stepIndex < script.length) schedule(stepIndex, delayOf(stepIndex));
    }, wait);
  }

  function play(from = 0) {
    clearTimeout(timer);
    if (calling) endCall();
    schedule(from, delayOf(from));
  }

  function pause() { paused = true; clearTimeout(timer); }
  function resume() {
    if (!paused) return;
    paused = false;
    if (!calling && stepIndex < script.length) schedule(stepIndex, delayOf(stepIndex));
  }

  // Jump straight to the "tap here to talk" screen.
  function showFinalState() {
    clearTimeout(timer);
    reset();
    stepped.forEach(show);
    flagged.classList.add('is-scanned');
    card.classList.add('has-watch', 'has-flag', 'has-ready');
    setPhase('voice');
    stepIndex = script.length - 1;
  }

  /* ---- The conversation the live voice starts from ---- */
  // Message text without the time stamp inside the bubble.
  const msgText = (m) => {
    if (m.dataset.text) return m.dataset.text;
    const copy = m.cloneNode(true);
    copy.querySelectorAll('.meta').forEach((meta) => meta.remove());
    return copy.textContent.trim();
  };

  function demoContext() {
    const s = SCENARIOS[current];
    const messages = [...card.querySelectorAll('.thread .msg')]
      .filter((m) => !m.dataset.step || m.classList.contains('is-shown') || calling)
      .map((m) => ({
        from: m.classList.contains('me') ? 'user' : 'contact',
        name: m.classList.contains('me') ? 'You' : s.contact,
        text: msgText(m),
      }));
    return {
      scenario: s.key,
      app: 'chat',
      contactName: s.contact,
      flaggedMessage: msgText(flagged),
      conversation: messages,
    };
  }

  function startCall() {
    if (calling) return;
    if (card.dataset.phase !== 'voice') showFinalState();
    clearTimeout(timer);
    calling = true;
    card.classList.add('is-calling');
    delete card.dataset.turn;
    callStatus.textContent = 'Connecting…';
    caption.textContent = 'ScamCatch will start talking.';

    const context = demoContext();
    const live = window.ScamCatchLive;
    if (live && typeof live.start === 'function') {
      // Live voice hooks in here: ScamCatchLive.start(context, { onStatus, onCaption }) → session with end()
      liveSession = live.start(context, {
        onStatus: (text) => { callStatus.textContent = text; },
        onCaption: (text) => { caption.textContent = text; },
        // after the voice finishes talking, the call screen shows it's the visitor's turn to talk back
        onTurn: (turn) => {
          card.dataset.turn = turn;
          callStatus.textContent = turn === 'you' ? 'Listening to you' : 'ScamCatch is talking';
        },
        onMicLevel: (level) => card.style.setProperty('--mic', level.toFixed(2)),
      });
    } else {
      setTimeout(() => {
        if (!calling) return;
        callStatus.textContent = 'Live voice isn’t connected in this build yet';
        caption.textContent = 'The voice will start from the chat you just watched.';
      }, 1400);
    }
  }

  function endCall() {
    if (!calling) return;
    calling = false;
    if (liveSession && typeof liveSession.end === 'function') liveSession.end();
    liveSession = null;
    card.classList.remove('is-calling');
    delete card.dataset.turn;
    card.style.removeProperty('--mic');
    callStatus.textContent = '';
    lastInteraction = Date.now();
    if (!reduceMotion) schedule(script.length - 1, LOOP_HOLD);
  }

  /* ---- Controls ---- */
  document.addEventListener('click', (event) => {
    const action = event.target.closest('[data-action]');
    if (!action) return;
    const kind = action.dataset.action;
    if (kind === 'talk') startCall();
    if (kind === 'end') endCall();
    if (kind === 'scenario') selectScenario(action.dataset.scenario);
    if (kind === 'replay') { lastInteraction = 0; reduceMotion ? showFinalState() : play(0); }
  });
  ['pointermove', 'focusin'].forEach((type) => phone.addEventListener(type, () => { lastInteraction = Date.now(); }));

  /* ---- System 1 & System 2: the story plays in order, one step at a time, left to right, like the vision's timeline ---- */
  // [step that lights up, how long it stays lit in ms]: the scam's steps rush by, the voice's steps take their time,
  // then a short rest with nothing lit before the story starts over. "catch" is the line where ScamCatch intervenes.
  const WHY_SEQUENCE = [['message', 1400], ['emotions', 1400], ['ask', 1400], ['catch', 2600], ['calm', 2600], ['think', 2600], ['check', 2600], ['rest', 1100]];
  const whyCard = document.querySelector('.card-why');
  let whyTimer = 0;
  let whyLit = null;
  function whyLight(index) {
    const [step, ms] = WHY_SEQUENCE[index];
    whyCard.dataset.active = step;
    if (whyLit) whyLit.classList.remove('is-lit');
    whyLit = whyCard.querySelector(`[data-step="${step}"]`);
    if (whyLit) whyLit.classList.add('is-lit');
    whyTimer = setTimeout(() => whyLight((index + 1) % WHY_SEQUENCE.length), ms);
  }
  function whyPlay() { if (!reduceMotion && !whyTimer) whyLight(0); }
  // off screen it stops and shows every step lit and still, which is also the reduced-motion view
  function whyStop() {
    clearTimeout(whyTimer);
    whyTimer = 0;
    delete whyCard.dataset.active;
    if (whyLit) whyLit.classList.remove('is-lit');
    whyLit = null;
  }

  /* ---- Section index + pause the demo when it's off screen ---- */
  const links = new Map([...document.querySelectorAll('.index a')].map((a) => [a.hash.slice(1), a]));
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      entry.target.classList.toggle('is-onscreen', entry.isIntersecting);
      if (entry.target.id === 'demo') entry.isIntersecting ? resume() : pause();
      if (entry.target.id === 'why') entry.isIntersecting ? whyPlay() : whyStop();
      if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
        links.forEach((a, id) => a.setAttribute('aria-current', id === entry.target.id ? 'true' : 'false'));
      }
    });
  }, { threshold: [0, 0.5] });
  document.querySelectorAll('.stage').forEach((stage) => observer.observe(stage));
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { pause(); whyStop(); return; }
    resume();
    if (whyCard.closest('.stage').classList.contains('is-onscreen')) whyPlay();
  });

  window.ScamCatchDemo = { context: demoContext, startCall, endCall, replay: () => play(0), scenario: selectScenario, scenarios: SCENARIOS };

  // scamcatch.io/?scam=irs opens on that scam
  const linked = new URLSearchParams(window.location.search).get('scam');
  renderScenario(SCENARIOS[linked] ? linked : 'romance');

  if (reduceMotion) showFinalState();
  else play(0);
})();
